package uk.noammm.kav.data

import java.io.ByteArrayOutputStream

/**
 * Apache Thrift TBinaryProtocol, the wire format of Moovit's mobile API.
 * Bodies are BARE structs (no RPC envelope), so this is just field read/write,
 * big-endian, fixed width, proven end to end against the live API.
 *
 * TType bytes: bool=2 byte=3 double=4 i16=6 i32=8 i64=10 string=11 struct=12
 *              map=13 set=14 list=15
 */
object TType {
    const val STOP = 0; const val BOOL = 2; const val BYTE = 3; const val DOUBLE = 4
    const val I16 = 6; const val I32 = 8; const val I64 = 10; const val STRING = 11
    const val STRUCT = 12; const val MAP = 13; const val SET = 14; const val LIST = 15
}

/** Writer. */
class TWriter {
    private val b = ByteArrayOutputStream(256)
    fun bytes(): ByteArray = b.toByteArray()

    fun byte(v: Int): TWriter { b.write(v and 0xFF); return this }
    fun i16(v: Int): TWriter { b.write((v ushr 8) and 0xFF); b.write(v and 0xFF); return this }
    fun i32(v: Int): TWriter { for (s in intArrayOf(24, 16, 8, 0)) b.write((v ushr s) and 0xFF); return this }
    fun i64(v: Long): TWriter { for (s in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) b.write(((v ushr s) and 0xFF).toInt()); return this }
    fun str(s: String): TWriter { val r = s.toByteArray(Charsets.UTF_8); i32(r.size); b.write(r); return this }

    fun field(type: Int, id: Int): TWriter { byte(type); i16(id); return this }
    fun stop(): TWriter { byte(TType.STOP); return this }

    fun i16Field(id: Int, v: Int) = field(TType.I16, id).i16(v)
    fun i32Field(id: Int, v: Int) = field(TType.I32, id).i32(v)
    fun i64Field(id: Int, v: Long) = field(TType.I64, id).i64(v)
    fun boolField(id: Int, v: Boolean) = field(TType.BOOL, id).byte(if (v) 1 else 0)
    fun strField(id: Int, v: String): TWriter { field(TType.STRING, id); str(v); return this }
    fun structField(id: Int, inner: TWriter): TWriter { field(TType.STRUCT, id); b.write(inner.bytes()); byte(TType.STOP); return this }
    fun i32ListField(id: Int, vs: List<Int>): TWriter {
        field(TType.LIST, id).byte(TType.I32).i32(vs.size); for (v in vs) i32(v); return this
    }
    fun <T> listField(id: Int, elemType: Int, items: List<T>, w: (TWriter, T) -> Unit): TWriter {
        field(TType.LIST, id).byte(elemType).i32(items.size); for (it in items) w(this, it); return this
    }
}

/** Reader → nested maps/lists, keyed by field id. Values: Boolean/Int/Long/String,
 *  Map<Int,Any?> for structs, List<Any?> for lists. */
class TReader(private val d: ByteArray) {
    private var p = 0

    /** Responses that cover several entities are concatenated BARE structs, so a
     *  caller has to keep reading until the buffer runs out. */
    fun hasMore(): Boolean = p < d.size
    private fun u(i: Int) = d[i].toInt() and 0xFF
    fun byte(): Int = u(p++)
    fun i16(): Int { val v = (u(p) shl 8) or u(p + 1); p += 2; return v.toShort().toInt() }
    fun i32(): Int { var v = 0; repeat(4) { v = (v shl 8) or u(p++) }; return v }
    fun i64(): Long { var v = 0L; repeat(8) { v = (v shl 8) or u(p++).toLong() }; return v }
    fun dbl(): Double = Double.fromBits(i64())
    fun str(): String { val n = i32(); val s = String(d, p, n, Charsets.UTF_8); p += n; return s }

    fun readStruct(): Map<Int, Any?> {
        val out = LinkedHashMap<Int, Any?>()
        while (true) {
            val t = byte()
            if (t == TType.STOP) return out
            val id = i16()
            out[id] = readValue(t)
        }
    }

    private fun readValue(t: Int): Any? = when (t) {
        TType.BOOL -> byte() != 0
        TType.BYTE -> byte()
        TType.DOUBLE -> dbl()
        TType.I16 -> i16()
        TType.I32 -> i32()
        TType.I64 -> i64()
        TType.STRING -> str()
        TType.STRUCT -> readStruct()
        TType.LIST, TType.SET -> { val et = byte(); val n = i32(); List(n) { readValue(et) } }
        TType.MAP -> { val kt = byte(); val vt = byte(); val n = i32()
            val m = LinkedHashMap<Any?, Any?>(); repeat(n) { m[readValue(kt)] = readValue(vt) }; m }
        else -> throw IllegalStateException("bad thrift type $t at $p")
    }
}
