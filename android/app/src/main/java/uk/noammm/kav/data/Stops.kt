package uk.noammm.kav.data

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Moovit's stop database, kept on the phone.
 *
 * The Live tab needs the Moovit ids of the stops around you, and the only keyless way
 * to those is paging the metro's stop entities a hundred at a time, in id order. The
 * first pages are the lowest ids, which are nowhere in particular: forty stops "near"
 * Rosh HaAyin taken from the first four thousand ids were spread from Herzliya to
 * Elad. So the whole database is paged once, in the background, tightening the map
 * as it goes, and written here so the next launch starts complete.
 *
 * The file is the app's own: id, coordinates and name per stop, about a megabyte for
 * the country. A partial file is kept too, with the id paging stopped at, so a page
 * walk cut short by leaving the tab carries on from where it was.
 */
object StopStore {
    private const val MAGIC = 1263620145   // "KST1" as big-endian ASCII

    class Saved(val stops: List<Moovit.Stop>, val nextId: Int, val complete: Boolean)

    private fun file(ctx: Context) = File(ctx.filesDir, "moovit-stops.bin")

    fun load(ctx: Context): Saved? = runCatching {
        val f = file(ctx)
        if (!f.exists()) return null
        DataInputStream(f.inputStream().buffered()).use { inp ->
            if (inp.readInt() != MAGIC) return null
            val complete = inp.readBoolean()
            val nextId = inp.readInt()
            val n = inp.readInt()
            val out = ArrayList<Moovit.Stop>(n)
            repeat(n) {
                val id = inp.readInt(); val lat = inp.readInt(); val lon = inp.readInt()
                out.add(Moovit.Stop(id, lat / 1e6, lon / 1e6, inp.readUTF()))
            }
            Saved(out, nextId, complete)
        }
    }.getOrNull()

    fun save(ctx: Context, stops: List<Moovit.Stop>, nextId: Int, complete: Boolean) {
        runCatching {
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeBoolean(complete)
                out.writeInt(nextId)
                out.writeInt(stops.size)
                for (s in stops) {
                    out.writeInt(s.id)
                    out.writeInt((s.lat * 1e6).toInt()); out.writeInt((s.lon * 1e6).toInt())
                    out.writeUTF(s.name.take(120))
                }
            }
            tmp.renameTo(f)
        }
    }
}
