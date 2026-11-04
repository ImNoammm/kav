package uk.noammm.kav.data

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.GZIPInputStream

// The native sharing endpoint accepts the website's selected itinerary in Thrift's binary format.
// Its anonymous registration lasts only for this share. No phone identity or advertising ID is sent.
internal object MoovitShare {
    private const val APP = "moovit_2751703405"
    private const val VERSION = "5.199.1.1804"

    internal fun itineraryBytes(itinerary: JSONObject, schema: JSONObject): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        fun write(type: JSONArray, value: Any) {
            when (type.getInt(0)) {
                2 -> out.writeBoolean(if (value is Boolean) value else (value as Number).toInt() != 0)
                3 -> out.writeByte((value as Number).toInt())
                4 -> out.writeDouble((value as Number).toDouble())
                6 -> out.writeShort((value as Number).toInt())
                8 -> out.writeInt((value as Number).toInt())
                10 -> out.writeLong((value as Number).toLong())
                11 -> value.toString().toByteArray(Charsets.UTF_8).let { out.writeInt(it.size); out.write(it) }
                12 -> {
                    val fields = schema.getJSONArray(type.getString(1))
                    val o = value as JSONObject
                    for (i in 0 until fields.length()) {
                        val f = fields.getJSONArray(i)
                        val name = f.getString(1)
                        if (!o.has(name) || o.isNull(name)) continue
                        val t = f.getJSONArray(2)
                        out.writeByte(t.getInt(0)); out.writeShort(f.getInt(0)); write(t, o.get(name))
                    }
                    out.writeByte(0)
                }
                14, 15 -> {
                    val values = value as JSONArray
                    val t = type.getJSONArray(1)
                    out.writeByte(t.getInt(0)); out.writeInt(values.length())
                    for (i in 0 until values.length()) write(t, values.get(i))
                }
                13 -> {
                    val values = value as JSONObject
                    val k = type.getJSONArray(1); val v = type.getJSONArray(2)
                    out.writeByte(k.getInt(0)); out.writeByte(v.getInt(0)); out.writeInt(values.length())
                    for (key in values.keys()) {
                        write(k, if (k.getInt(0) in listOf(3, 6, 8, 10)) key.toLong() else key)
                        write(v, values.get(key))
                    }
                }
                else -> error("Unknown itinerary field type")
            }
        }
        write(JSONArray().put(12).put("MVTripPlanItinerary"), itinerary)
        return bytes.toByteArray()
    }

    internal fun namedItinerary(original: String, fromName: String, toName: String): JSONObject {
        val itinerary = JSONObject(original)
        val legs = itinerary.getJSONArray("legs")
        fun name(index: Int, side: String, label: String) {
            if (label.isBlank() || index !in 0 until legs.length()) return
            val leg = legs.getJSONObject(index)
            val body = leg.keys().asSequence().mapNotNull { leg.optJSONObject(it) }.firstOrNull() ?: return
            val location = body.optJSONObject("journey")?.optJSONObject(side) ?: return
            if (location.optString("caption").isBlank()) location.put("caption", label)
        }
        name(0, "origin", fromName); name(legs.length() - 1, "dest", toName)
        return itinerary
    }

    fun link(ctx: Context, trip: Transit.Itinerary, fromName: String, toName: String): String? {
        if (!MoovitWeb.owns(trip) || trip.original.isBlank()) return null
        val itinerary = namedItinerary(trip.original, fromName, toName)
        val schema = ctx.resources.openRawResource(uk.noammm.kav.R.raw.moovit_share_schema)
            .bufferedReader().use { JSONObject(it.readText()) }
        val at = trip.legs.firstNotNullOfOrNull { it.shape.firstOrNull() } ?: return null
        var revision: String? = null
        fun post(host: Int, path: String, body: ByteArray, auth: Map<String, String> = emptyMap()): JSONObject {
            for (attempt in 0..1) {
                val c = URL("https://app$host.moovitapp.com/services-app/services/$path").openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 5_000; c.readTimeout = 10_000
                    c.requestMethod = "POST"; c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/octet")
                    c.setRequestProperty("Accept", "application/json")
                    c.setRequestProperty("Accept-Encoding", "gzip")
                    c.setRequestProperty("User-Agent", "Dalvik/2.1.0")
                    c.setRequestProperty("api_key", APP); c.setRequestProperty("client_version", VERSION)
                    c.setRequestProperty("phone_type", "2")
                    auth.forEach { (k, v) -> c.setRequestProperty(k, v) }
                    if (auth.isNotEmpty()) revision?.let { c.setRequestProperty("Metro-Revision-Number", it) }
                    c.setFixedLengthStreamingMode(body.size)
                    c.outputStream.use { it.write(body) }
                    val fresh = c.getHeaderField("metro-revision-number")?.takeIf { it.isNotBlank() }
                    if (c.responseCode == 412 && attempt == 0 && fresh != null && fresh != revision) {
                        revision = fresh; continue
                    }
                    if (fresh != null) revision = fresh
                    check(c.responseCode == 200) { "Moovit sharing HTTP ${c.responseCode}" }
                    val input = c.inputStream.let { if (c.getHeaderField("Content-Encoding") == "gzip") GZIPInputStream(it) else it }
                    return input.bufferedReader().use { JSONObject(it.readText()) }
                } finally { c.disconnect() }
            }
            error("Moovit sharing revision changed")
        }
        val user = post(4, "UserAuth/CreateUser", Fields().apply {
            struct(1, Fields().apply { int(1, Math.round(at.first * 1e6).toInt()); int(2, Math.round(at.second * 1e6).toInt()) }.bytes())
            struct(3, Fields().apply { string(1, uk.noammm.kav.Prefs.lang(ctx).code); string(2, "IL"); string(3, "") }.bytes())
            string(4, "Android"); string(5, "${Build.VERSION.RELEASE}_${Build.VERSION.SDK_INT}"); int(6, 2)
            struct(7, Fields().apply { string(1, ""); string(2, ""); string(3, "") }.bytes())
            string(8, ""); string(9, ""); bool(10, true); int(11, 5); long(12, System.currentTimeMillis()); int(13, 1)
            string(15, UUID.randomUUID().toString().replace("-", "").take(16)); string(16, APP)
            string(19, UUID.randomUUID().toString()); string(20, UUID.randomUUID().toString().replace("-", ""))
            string(21, "com.tranzmate"); bool(22, true)
        }.bytes()).getJSONObject("1").getJSONObject("rec")
        val token = user.getJSONObject("7").getJSONObject("rec").getJSONObject("1").getJSONObject("rec")
            .getJSONObject("1").getJSONObject("rec").getJSONObject("3").getString("str")
        val auth = mapOf("user_key" to user.getJSONObject("1").getString("str"), "access-token" to token,
            "Metro-Revision-Metro-Id" to "1", "request-sequence-id" to "1")
        val answer = post(5, "V5/Sharing/ShareItinerary", Fields().apply {
            int(1, 1); string(2, itinerary.getString("guid")); struct(3, itineraryBytes(itinerary, schema))
        }.bytes(), auth)
        return answer.optJSONObject("1")?.optJSONObject("rec")?.optJSONObject("1")?.optString("str")
            ?.takeIf { TripLink.parse(it)?.shared?.source == Source.MOOVIT }
    }

    private class Fields {
        private val bytes = ByteArrayOutputStream()
        private val out = DataOutputStream(bytes)
        private fun field(type: Int, id: Int) { out.writeByte(type); out.writeShort(id) }
        fun int(id: Int, value: Int) { field(8, id); out.writeInt(value) }
        fun long(id: Int, value: Long) { field(10, id); out.writeLong(value) }
        fun bool(id: Int, value: Boolean) { field(2, id); out.writeBoolean(value) }
        fun string(id: Int, value: String) {
            field(11, id); val raw = value.toByteArray(Charsets.UTF_8); out.writeInt(raw.size); out.write(raw)
        }
        fun struct(id: Int, value: ByteArray) { field(12, id); out.write(value) }
        fun bytes(): ByteArray = bytes.toByteArray() + byteArrayOf(0)
    }
}
