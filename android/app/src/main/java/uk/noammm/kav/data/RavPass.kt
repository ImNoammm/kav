package uk.noammm.kav.data

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Paying for a bus ride with the rider's own Rav-Pass account: HopOn's payment service, licensed by the Ministry of
// Transport. Kav calls it as HopOn's app (Rav-Pass 10.2.16) does: JSON over HTTPS, signing up or in with a code by
// SMS, then the account's token on every call.
object RavPass {
    internal var base = "https://api-prod.hopon.co/mot/"

    // Each part of HopOn's app names itself in its User-Agent: signing in by its build, cards the same with a closing
    // ";", and the payment calls by version and "1".
    private val device get() = "${Build.MANUFACTURER} ${Build.MODEL} ${Build.PRODUCT}"
    private val signInAgent get() = "$device; 10.2.16 400001122; Android ${Build.VERSION.RELEASE}"
    private val cardAgent get() = "$signInAgent;"
    private val agent get() = "$device; 10.2.16 1; Android ${Build.VERSION.RELEASE};"

    // uid goes as X-HOPON-UID. HopOn's app sends Android's own id there; Kav sends a random one of the same shape.
    class Session(val token: String, val uid: String)

    fun newUid(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    // HopOn's refusal: its title, its reasons as the message, and its resultCode.
    class Refused(val title: String, message: String, val code: Int = 0) : Exception(message)

    class Unauthorized : Exception("not signed in")

    // The resultCodes HopOn's app acts on: no card it can charge, no station near the fix, and a station to choose by
    // hand.
    fun needsCard(e: Throwable) = e is Refused && (e.code == 7 || e.code == 8)
    const val NO_STATION = 10
    const val CHOOSE_STATION = 11

    // Where the phone is, as HopOn's app sends it: the fix, when it was taken (UTC) and how accurate it is in metres.
    class Fix(val lat: Double, val lon: Double, val timeUtc: Long, val accuracy: Float)

    // A station's own position as an exact fix taken now: a station chosen by hand goes this way, and HopOn takes it from
    // anywhere (an entrance and an exit both went through like this on Oct 13, far from the station).
    fun spot(at: Pair<Double, Double>) = Fix(at.first, at.second, System.currentTimeMillis(), 10f)

    class Price(val agorot: Long) {
        val text get() = "₪" + String.format(Locale.US, "%.2f", agorot / 100.0).removeSuffix(".00")
    }

    private fun price(o: JSONObject, key: String) = o.optLongOrNull(key)?.let(::Price)

    internal fun request(
        method: String, path: String, agent: String, uid: String, token: String?, body: JSONObject?, json: Boolean,
    ): Pair<Int, String> {
        val c = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 10_000; readTimeout = 30_000
            if (json) setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", if (uk.noammm.kav.ui.T.rtl) "he_IL" else "en_US")
            setRequestProperty("User-Agent", agent)
            setRequestProperty("X-HOPON-UID", uid)
            if (token != null) setRequestProperty("Authorization", "Token $token")
            if (body != null || method == "POST" || method == "PUT") doOutput = true
            if (body != null) setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        try {
            if (c.doOutput) c.outputStream.use { it.write(body?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) }
            return code to text.orEmpty()
        } finally {
            c.disconnect()
        }
    }

    // A refusal comes as {"message": title, "errors": [reasons], "resultCode": n}.
    internal fun refusal(o: JSONObject?): Refused? {
        o ?: return null
        val title = o.str("message")
        val reasons = o.optJSONArray("errors")?.let { a ->
            (0 until a.length()).mapNotNull { i -> (a.optJSONObject(i)?.str("message") ?: a.str(i))?.trim()?.ifBlank { null } }
        }.orEmpty()
        if (title == null && reasons.isEmpty()) return null
        val code = o.optInt("resultCode").takeIf { it != 0 } ?: o.optInt("responseCode")
        return Refused(title.orEmpty(), reasons.joinToString(" ").ifBlank { title.orEmpty() }, code)
    }

    private fun answer(code: Int, text: String, signedIn: Boolean = true): JSONObject? {
        val o = runCatching { JSONObject(text) }.getOrNull()
        if (code in 200..299) return o
        if (code == 401 && signedIn) throw Unauthorized()
        throw refusal(o) ?: IOException("HopOn HTTP $code")
    }

    private fun send(method: String, path: String, s: Session, body: JSONObject? = null, agent: String = this.agent): JSONObject? {
        val (code, text) = request(method, path, agent, s.uid, s.token, body, json = agent == this.agent)
        return answer(code, text)
    }

    // An Israeli number as HopOn's app sends it: the calling code apart, the number without its first 0.
    internal fun phoneParts(phone: String): Pair<String, String> {
        val d = phone.filter(Char::isDigit)
        val local = if (d.startsWith("972")) d.drop(3) else d
        return "972" to local.removePrefix("0")
    }

    // Sends the code. A number HopOn doesn't know yet is signed up by the code that answers it.
    fun sendCode(uid: String, phone: String) {
        val (country, number) = phoneParts(phone)
        val body = JSONObject().put("region_id", 0)
            .put("registration_params", JSONObject().put("country_code", country).put("phone_number", number))
        val (code, text) = request("POST", "passengers/sms", signInAgent, uid, null, body, json = false)
        answer(code, text, signedIn = false)
    }

    fun verify(uid: String, phone: String, code: String): Session {
        val (country, number) = phoneParts(phone)
        val body = JSONObject().put("validation_params",
            JSONObject().put("phone_number", number).put("country_code", country).put("code", code))
        val (status, text) = request("POST", "token/sms", signInAgent, uid, null, body, json = false)
        val data = answer(status, text, signedIn = false)?.optJSONObject("data") ?: throw IOException("no token")
        val token = data.str("token") ?: throw IOException("no token")
        return Session(token, uid)
    }

    // HopOn's settings: the terms in force and where to read them, how many may ride along, and whether its app adds
    // them as occasional passengers (extra_passengers_enabled), which it then counts as such when paying.
    class Settings(
        val terms: String?, val termsUrl: String?, val privacyUrl: String?, val motTermsUrl: String?,
        val pricesUrl: String?, val guestsMax: Int, val occasional: Boolean,
    )

    // Before signing in too, for the terms the rider agrees to.
    fun settings(uid: String, token: String? = null): Settings {
        val (code, text) = request("GET", "settings", agent, uid, token, null, json = true)
        val d = answer(code, text, signedIn = token != null)?.optJSONObject("data") ?: throw IOException("no settings")
        val urls = d.optJSONArray("urls")?.objects().orEmpty().associate { it.str("name") to it.str("url") }
        return Settings(
            terms = d.str("terms_version"), termsUrl = d.str("terms_url"), privacyUrl = urls["privacy_policy"],
            motTermsUrl = d.str("mot_terms_url"), pricesUrl = urls["ravpass_prices"],
            guestsMax = d.optInt("extra_passengers_max", 10), occasional = d.optBoolean("extra_passengers_enabled"),
        )
    }

    // The account holder. terms: the version of the terms they agreed to; profiles: their fare profiles' names.
    class Account(
        val first: String, val last: String, val email: String, val phone: String, val terms: String?,
        val profiles: List<String>,
    ) {
        val name get() = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")
        // What HopOn's app asks for before a card can be added.
        val hasDetails get() = first.isNotBlank() && last.isNotBlank() && email.isNotBlank()
    }

    fun account(s: Session): Account {
        val d = send("GET", "passengers/me", s)?.optJSONObject("data") ?: throw IOException("no account")
        val number = d.optLongOrNull("phone")?.toString()
        return Account(
            first = d.str("first_name").orEmpty(), last = d.str("last_name").orEmpty(), email = d.str("email").orEmpty(),
            phone = number?.let { if (it.length == 9) "0${it.take(2)}-${it.substring(2, 5)}-${it.drop(5)}" else it }.orEmpty(),
            terms = d.str("last_terms_agreement"),
            profiles = d.optJSONArray("profiles")?.objects().orEmpty().mapNotNull { it.str("profile_name") ?: it.str("name") },
        )
    }

    fun acceptTerms(s: Session, version: String) {
        send("PUT", "passengers/me", s, JSONObject().put("terms_version", version))
    }

    fun saveDetails(s: Session, first: String, last: String, email: String) {
        send("PUT", "passengers/me", s, JSONObject().put("first_name", first).put("last_name", last).put("email", email))
    }

    // A card on the account. HopOn's app counts it unless it is past its date, or neither active (status 2) nor still
    // being checked ("in_progress", as a card just added is) nor Google Pay.
    class Card(
        val id: Long, val brand: String, val last4: String, val expiresUtc: Long?, val active: Boolean,
        val checking: Boolean, val googlePay: Boolean, val default: Boolean,
    ) {
        fun expired(now: Long) = (expiresUtc != null && expiresUtc < now) || (!googlePay && !active && !checking)
    }

    fun cards(s: Session): List<Card> =
        send("GET", "passengers/me/payment_methods", s)?.optJSONArray("data")?.objects().orEmpty().map(::cardOf)

    internal fun cardOf(c: JSONObject): Card {
        val issuer = c.optJSONObject("issuer")
        val kind = issuer?.str("internal_name")
        return Card(
            id = c.optLong("id"), brand = issuer?.str("description") ?: kind.orEmpty(),
            last4 = c.str("public_identifier").orEmpty().takeLast(4), expiresUtc = time(c.str("expires_at")),
            active = c.optInt("payment_method_status_id") == 2, checking = kind == "in_progress",
            googlePay = c.str("wallet_provider") == "GOOGLEPAY", default = c.optBoolean("is_default"),
        )
    }

    // The page to add a card on: CreditGuard's, for HopOn, so the card is typed there and never in Kav.
    fun cardPage(s: Session): String {
        val d = send("POST", "passengers/me/payment_methods", s, agent = cardAgent)?.optJSONObject("data")
        return d?.str("redirect_url") ?: throw IOException("no card page")
    }

    // How the card page ends. After CreditGuard, HopOn's return link adds the card and sends the page on to one of these
    // marks, which HopOn's app reads and doesn't load (HopOn answers them with 404): the card added, or added but waiting
    // for the code the card's issuer sends by SMS (requires_2fa), an error, or the rider cancelling.
    enum class CardEnd { ADDED, CODE, FAILED, CANCELLED }

    fun cardEnd(url: String): CardEnd? {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val path = u.rawPath ?: return null
        val query = u.rawQuery.orEmpty().split("&").associate { it.substringBefore("=") to it.substringAfter("=", "") }
        fun asked(key: String) = query[key].let { it == "1" || it.equals("true", ignoreCase = true) }
        return when {
            "success_transaction_verification_redirect" in path ->
                if (asked("requires_2fa") || asked("requires_sfa")) CardEnd.CODE else CardEnd.ADDED
            "error_transaction_verification_redirect" in path -> CardEnd.FAILED
            "cancel_transaction_verification_redirect" in path -> CardEnd.CANCELLED
            else -> null
        }
    }

    // The 4-digit code the card's issuer sent by SMS, which finishes adding the card.
    fun verifyCard(s: Session, code: String) {
        send("POST", "passengers/me/payment_methods/verify_cc_pin_code", s, JSONObject().put("pinCode", code), agent = cardAgent)
    }

    class Stop(val code: String, val name: String, val lat: Double, val lon: Double, val sequence: Int)

    // A bus by its QR code: its line, and the stop HopOn places the rider at on it. to: where HopOn says the ride ends,
    // when it says. stopOnly: HopOn found the stop but not the line, which its app then names in what it asks next.
    class Bus(
        val qr: String, val line: String, val towards: String, val routeId: String?, val officeLineId: String?,
        val agencyId: String?, val color: String?, val from: Stop?, val to: Stop?, val choose: Boolean,
        val meanOfTransport: Int? = null, val bands: List<Band> = emptyList(), val stopOnly: Boolean = false,
    ) {
        private fun copy(
            line: String = this.line, towards: String = this.towards, routeId: String? = this.routeId,
            officeLineId: String? = this.officeLineId, from: Stop? = this.from, to: Stop? = this.to,
        ) = Bus(qr, line, towards, routeId, officeLineId, agencyId, color, from, to, choose, meanOfTransport, bands, stopOnly)

        // The ride chosen from HopOn's suggestions, when HopOn couldn't place the rider on this bus itself.
        fun with(s: Suggestion) = copy(s.line, s.to?.name.orEmpty(), s.routeId, s.officeLineId, s.from, s.to)

        // A line chosen from those around the rider, before HopOn names its run: boarded at the stop HopOn found, if any.
        fun onLine(n: Nearby) = copy(n.line, n.to, null, n.officeLineId, from.takeIf { stopOnly }, null)

        // A run of the line, boarded at `stop`.
        fun on(run: Run, stop: Stop?) = copy(routeId = run.routeId, from = stop)

        fun boardingAt(stop: Stop) = copy(from = stop)
    }

    // A fare's reach and its full price, from HopOn's fare table for the bus: "up to 15 km, ₪8".
    class Band(val km: Int, val price: Price)

    // One band per reach, the regular fare (the lowest fare code of each; HopOn lists 1x, 2x, and 3x without VAT).
    internal fun bandsOf(d: JSONObject): List<Band> = d.optJSONArray("fares")?.objects().orEmpty()
        .mapNotNull { f -> val km = f.optLongOrNull("radius")?.toInt() ?: return@mapNotNull null; Triple(km, f.optInt("fare_code"), f.optDoubleOrNull("tariff")) }
        .filter { it.first > 0 && it.third != null }
        .groupBy { it.first }.map { (km, all) -> Band(km, Price(Math.round(all.minBy { it.second }.third!!))) }
        .sortedBy { it.km }

    // A ride HopOn suggests on a bus it can't place the rider on: the line, where the ride starts and where it ends.
    class Suggestion(
        val line: String, val routeId: String?, val officeLineId: String?, val from: Stop, val to: Stop?,
        val fromCity: String, val toCity: String,
    )

    private fun stopOf(o: JSONObject?): Stop? {
        o ?: return null
        val code = o.str("code") ?: o.str("stop_code") ?: return null
        return Stop(code, o.str("name") ?: o.str("stop_name").orEmpty(),
            o.optDoubleOrNull("latitude") ?: o.optDoubleOrNull("stop_lat") ?: Double.NaN,
            o.optDoubleOrNull("longitude") ?: o.optDoubleOrNull("stop_lon") ?: Double.NaN, o.optInt("stop_sequence", -1))
    }

    // A query value as OkHttp encodes one for HopOn's app: as URLEncoder does, but a space as %20.
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // The bus whose code was scanned, from the phone's fix: HopOn finds the line and the stop on it from its own data.
    fun scan(s: Session, qr: String, at: Fix): Bus {
        val path = "fares?lat=${at.lat}&lon=${at.lon}&qr=${enc(qr)}&timestamp=${at.timeUtc}&accuracy=${at.accuracy}" +
            "&manual_stop_selection_supported=true"
        val d = send("GET", path, s)?.optJSONObject("data") ?: throw IOException("no bus")
        val route = d.optJSONObject("route")
        val stop = stopOf(d.optJSONObject("stop"))
        return Bus(
            qr = d.str("qr") ?: qr, line = route?.str("short_name").orEmpty(), towards = route?.str("long_name").orEmpty(),
            routeId = route?.str("route_id"), officeLineId = route?.str("office_line_id"), agencyId = d.str("agency_id"),
            color = d.str("agency_color"), from = stop, to = stopOf(d.optJSONObject("arrival_stop")),
            choose = d.optBoolean("requires_manual_selection"), meanOfTransport = d.optLongOrNull("mean_of_transport_id")?.toInt(),
            bands = bandsOf(d), stopOnly = stop != null && route == null,
        )
    }

    // The rides HopOn suggests when the code doesn't tell it which line the rider is on, as when it is scanned away from
    // the bus: the bus company's likely rides from `at`, or from anywhere when HopOn asks to choose by hand.
    fun likelyRides(s: Session, bus: Bus, at: Fix?): List<Suggestion> {
        val agency = bus.agencyId ?: return emptyList()
        val near = at.takeIf { !bus.choose }
        val body = JSONObject().put("agency_id", agency).put("mean_of_transport_id", bus.meanOfTransport).put("is_train", false)
            .put("departure_lat", near?.lat).put("departure_lon", near?.lon)
            .put("departure_date", local.format(java.util.Date())).put("result_size", 20)
        val d = send("POST", "services/mot_pricing/likely_rides", s, body)?.optJSONObject("data") ?: return emptyList()
        return suggestionsOf(d)
    }

    internal fun suggestionsOf(d: JSONObject): List<Suggestion> = d.optJSONArray("lines")?.objects().orEmpty().mapNotNull { l ->
        val from = l.str("departure_stop_code") ?: return@mapNotNull null
        Suggestion(
            line = l.str("route_short_name").orEmpty(), routeId = l.str("route_id"), officeLineId = l.str("office_line_id"),
            from = Stop(from, l.str("departure_stop_name").orEmpty(), Double.NaN, Double.NaN, -1),
            to = l.str("arrival_stop_code")?.let { Stop(it, l.str("arrival_stop_name").orEmpty(), Double.NaN, Double.NaN, -1) },
            fromCity = l.str("departure_city_name").orEmpty(), toCity = l.str("arrival_city_name").orEmpty(),
        )
    }

    // A run of the line as HopOn has it near the rider: its id, the stop HopOn reckons they board at, and its stops in
    // HopOn's order.
    class Run(val routeId: String?, val boarding: String?, val stops: List<Stop>)

    // The runs of the bus's line around the phone's fix, as HopOn's app asks for them once it knows the line.
    fun runs(s: Session, bus: Bus, at: Fix): List<Run> {
        val body = JSONObject().put("stop_lat", at.lat).put("stop_lon", at.lon).put("office_line_id", bus.officeLineId)
            .put("minutes_from_now", -5).put("preview_interval", 40).put("distance", 300).put("route_short_name", bus.line.ifBlank { null })
        if (bus.stopOnly) body.put("departure_stop_code", bus.from?.code)
        return runsOf(send("PUT", "api_gateway/station_service/route_details/me", s, body)?.optJSONObject("data"))
    }

    internal fun runsOf(d: JSONObject?): List<Run> = d?.optJSONArray("route_details")?.objects().orEmpty().map { r ->
        Run(r.str("route_id"), r.str("boarding_stop_code"), r.optJSONArray("stops")?.objects().orEmpty().mapNotNull(::stopOf))
    }

    // A line of the bus's company around the rider, to choose the ride by hand when HopOn can't tell it: its number and
    // the two ends of its route.
    class Nearby(val line: String, val officeLineId: String, val from: String, val to: String)

    // The company's lines within 300 m of the phone's fix that HopOn has live data for, as its app lists them.
    fun nearby(s: Session, bus: Bus, at: Fix): List<Nearby> {
        val agency = bus.agencyId ?: return emptyList()
        val body = JSONObject().put("stop_lat", at.lat).put("stop_lon", at.lon).put("distance", 300).put("preview_interval", 40)
            .put("minutes_from_now", -5).put("agency_id", agency).put("add_real_time_data", true)
        if (bus.stopOnly) body.put("stop_code", bus.from?.code)
        val d = send("PUT", "api_gateway/station_service/nearby_routes/me", s, body)?.optJSONObject("data")
        return linesOf(d?.optJSONArray("nearby_routes"), byNumber = true)
    }

    // The company's lines by number, as HopOn's app searches them.
    fun searchLines(s: Session, bus: Bus, number: String): List<Nearby> {
        val agency = bus.agencyId ?: return emptyList()
        val body = JSONObject().put("agency_id", agency).put("route_short_name", number)
        val d = send("PUT", "api_gateway/station_service/routes/me", s, body)?.optJSONObject("data")
        return linesOf(d?.optJSONArray("routes"), byNumber = false)
    }

    // In HopOn's order, by its priority and else the number, once each: by number around the rider, by line in a search.
    internal fun linesOf(a: JSONArray?, byNumber: Boolean): List<Nearby> {
        val all = a?.objects().orEmpty().sortedWith(compareBy<JSONObject>(
            { it.optLongOrNull("priority") == null }, { it.optLongOrNull("priority") },
            { if (it.optLongOrNull("priority") == null) it.str("route_short_name") else null },
        ))
        return all.distinctBy { if (byNumber) it.str("route_short_name") else it.str("office_line_id") }.mapNotNull { r ->
            fun end(name: String?, city: String?) = if (name != null && city != null && city !in name) "$name, $city" else name ?: city
            val sameCity = r.str("first_stop_city") == r.str("last_stop_city")
            Nearby(
                line = r.str("route_short_name") ?: return@mapNotNull null, officeLineId = r.str("office_line_id") ?: return@mapNotNull null,
                from = (if (sameCity) end(r.str("first_stop_name"), r.str("first_stop_city")) else r.str("first_stop_city")).orEmpty(),
                to = (if (sameCity) end(r.str("last_stop_name"), r.str("last_stop_city")) else r.str("last_stop_city")).orEmpty(),
            )
        }
    }

    // HopOn's price for the ride to `to`, for the rider and `guests` more, under the distance its fare reaches.
    class Quote(val total: Price?, val km: Int?, val notes: List<String>)

    private val local get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH).apply { timeZone = ISRAEL }

    fun quote(s: Session, bus: Bus, to: Stop, guests: Int): Quote {
        val body = JSONObject().put("route_id", bus.routeId).put("extra_passengers", guests).put("qr", bus.qr)
            .put("is_train", false).put("departure_stop_code", bus.from?.code).put("arrival_stop_code", to.code)
            .put("departure_date", local.format(java.util.Date()))
        val d = send("POST", "services/mot_pricing/next_ride", s, body)?.optJSONObject("data") ?: throw IOException("no price")
        return quoteOf(d, guests)
    }

    // As HopOn's app adds it up: the price for everyone when HopOn gives one, else the rider's own after discounts plus
    // the guests' (each at the single price when no total for them comes).
    internal fun quoteOf(d: JSONObject, guests: Int): Quote {
        val all = d.optLongOrNull("total_for_all_passengers")
        val mine = d.optLongOrNull("total_after_discount")
        val others = d.optLongOrNull("extra_passengers_price") ?: d.optLongOrNull("single_price")?.let { it * guests }
        val total = all ?: if (mine != null && (guests == 0 || others != null)) mine + (others ?: 0) else null
        val notes = d.optJSONArray("fare_information")?.objects().orEmpty()
            .mapNotNull { (it.str("text") ?: it.str("message") ?: it.str("benefit"))?.trim()?.ifBlank { null } }
        return Quote(total?.let(::Price), d.optDoubleOrNull("radius")?.toInt(), notes)
    }

    // Whether others may ride along, as HopOn's app asks with every price it shows: a payment method on the account can
    // block them (block_extra_passengers). price: the ride's, sent as plan_price, or 1 as its app sends it at a station.
    fun guestsAllowed(s: Session, qr: String, price: Price?): Boolean {
        val plan = (price?.agorot?.takeIf { it > 0 } ?: 1L).toDouble()
        val d = send("GET", "passengers/me/payment_information?type=rav_pass&plan_price=$plan&qr=${enc(qr)}", s)
        return d?.optJSONArray("data")?.objects().orEmpty().none { it.optBoolean("block_extra_passengers") }
    }

    // A validated ride: each passenger's code for the inspector, its reference (UTN), and until when it is valid and
    // free transfers last.
    class Ticket(
        val id: Long, val line: String, val towards: String, val agency: String, val color: String?, val from: String,
        val codes: List<String>, val utn: String, val passenger: String, val profile: String, val guests: Int,
        val cost: Price?, val boughtUtc: Long, val validUntilUtc: Long, val transferUntilUtc: Long,
        val agencyCode: String? = null, val uncompleted: Boolean = false, val toCode: String? = null,
        val fromCode: String? = null,
    ) {
        fun valid(now: Long) = validUntilUtc > now

        // An Israel Railways entrance still waiting for its exit ticket: no exit station yet. HopOn sends uncompleted_ride
        // as null or false for one.
        val needsExit get() = agencyCode == RAILWAYS && toCode.isNullOrEmpty()
    }

    // Israel Railways' agency code at HopOn.
    const val RAILWAYS = "2"

    internal fun ticketOf(d: JSONObject) = Ticket(
        id = d.optLong("id"), line = d.str("line_number") ?: d.str("route_short_name").orEmpty(),
        towards = d.str("destination_station_name").orEmpty(), agency = d.str("agency").orEmpty(), color = d.str("agency_color"),
        from = d.str("boarding_station_name").orEmpty(), codes = d.optJSONArray("encrypted_qr_data")?.strings().orEmpty(),
        utn = d.str("utn").orEmpty(), passenger = d.str("passenger_name").orEmpty(), profile = d.str("profile_name").orEmpty(),
        guests = d.optInt("extra_passengers_count"), cost = price(d, "total_cost"),
        boughtUtc = time(d.str("validation_date")) ?: 0L, validUntilUtc = time(d.str("valid_until")) ?: 0L,
        transferUntilUtc = time(d.str("transfer_until")) ?: 0L,
        agencyCode = d.str("agency_code"), uncompleted = d.optBoolean("uncompleted_ride"), toCode = d.str("destination_station_code"),
        fromCode = d.str("boarding_station_code"),
    )

    // A ticket kept on the phone, in HopOn's own shape so ticketOf reads it back.
    internal fun json(t: Ticket): JSONObject = JSONObject()
        .put("id", t.id).put("line_number", t.line).put("destination_station_name", t.towards).put("agency", t.agency)
        .put("agency_color", t.color).put("boarding_station_name", t.from).put("encrypted_qr_data", JSONArray(t.codes))
        .put("utn", t.utn).put("passenger_name", t.passenger).put("profile_name", t.profile).put("extra_passengers_count", t.guests)
        .put("total_cost", t.cost?.agorot).put("validation_date", t.boughtUtc.toString())
        .put("valid_until", t.validUntilUtc.toString()).put("transfer_until", t.transferUntilUtc.toString())
        .put("agency_code", t.agencyCode).put("uncompleted_ride", t.uncompleted).put("destination_station_code", t.toCode)
        .put("boarding_station_code", t.fromCode)

    // Pays for the bus whose code was scanned: the rider and `guests` riding along, all on one ticket. occasional: HopOn's
    // settings have its app count them as occasional passengers too (Settings.occasional).
    fun buy(s: Session, qr: String, guests: Int, occasional: Boolean): Ticket {
        val body = JSONObject().put("qr", qr)
        if (guests > 0) body.put("extra_passengers", guests).put("extra_passengers_count", guests)
        if (guests > 0 && occasional) body.put("occasional_passengers_count", guests)
        val d = send("POST", "travel/bus", s, body)?.optJSONObject("data") ?: throw IOException("no ticket")
        return ticketOf(d)
    }

    // Rides paid at a station: Israel Railways ("train"), and light rail, Carmelit and cable cars, which HopOn's app asks
    // for as "other".
    enum class Kind(val type: String) { TRAIN("train"), OTHER("other") }

    // The station HopOn places the rider at, with the code a ride from it is paid with (qr), its line and the most a
    // ride from it can cost.
    class Station(
        val qr: String, val stop: Stop?, val agencyId: String?, val color: String?, val line: String, val towards: String,
        val maxPrice: Price?,
    )

    internal fun stationOf(d: JSONObject): Station {
        val route = d.optJSONObject("route")
        return Station(
            qr = d.str("qr").orEmpty(), stop = stopOf(d.optJSONObject("stop")), agencyId = d.str("agency_id"),
            color = d.str("agency_color"), line = route?.str("short_name").orEmpty(), towards = route?.str("long_name").orEmpty(),
            maxPrice = price(d, "max_price"),
        )
    }

    // The station HopOn places the rider at: from the phone's fix, or from a station's own position (spot).
    fun station(s: Session, kind: Kind, at: Fix): Station {
        val path = "fares?lat=${at.lat}&lon=${at.lon}&timestamp=${at.timeUtc}&accuracy=${at.accuracy}&transport_type=${kind.type}" +
            "&manual_stop_selection_supported=true"
        return stationOf(send("GET", path, s)?.optJSONObject("data") ?: throw IOException("no station"))
    }

    // The station a train ride ends at, the same way.
    fun checkout(s: Session, at: Fix): Station {
        val path = "checkout?lat=${at.lat}&lon=${at.lon}&timestamp=${at.timeUtc}&accuracy=${at.accuracy}" +
            "&manual_stop_selection_supported=true"
        return stationOf(send("GET", path, s)?.optJSONObject("data") ?: throw IOException("no station"))
    }

    // HopOn's price for a ride from the station, for the rider and `guests` more: the train's as a train, the rest as
    // HopOn's app asks it. to: where the ride ends, when known.
    fun stationQuote(s: Session, kind: Kind, st: Station, to: String?, guests: Int): Quote {
        val body = JSONObject().put("qr", st.qr).put("departure_stop_code", st.stop?.code).put("arrival_stop_code", to)
            .put("extra_passengers", guests).put("is_train", kind == Kind.TRAIN).put("departure_date", local.format(java.util.Date()))
        val d = send("POST", "services/mot_pricing/next_ride", s, body)?.optJSONObject("data") ?: throw IOException("no price")
        return quoteOf(d, guests)
    }

    // Pays the way in at the station: the ticket whose code opens the gate. Others ride along on light rail and the
    // like, not on the train, as in HopOn's app.
    fun enter(s: Session, kind: Kind, st: Station, guests: Int): Ticket {
        val body = JSONObject().put("qr", st.qr)
        if (kind == Kind.OTHER && guests > 0)
            body.put("extra_passengers", guests).put("extra_passengers_count", guests).put("occasional_passengers_count", guests)
        return ticketOf(send("POST", "travel/train", s, body)?.optJSONObject("data") ?: throw IOException("no ticket"))
    }

    // The price of the train ride from the entrance to the station checkout found, which HopOn's app asks for before
    // the exit ticket: it is what gives HopOn the ride's destination, and closing the ride fails without it.
    fun exitQuote(s: Session, st: Station, entrance: Ticket): Quote {
        val body = JSONObject().put("qr", st.qr).put("departure_stop_code", entrance.fromCode).put("arrival_stop_code", st.stop?.code)
            .put("extra_passengers", entrance.guests).put("is_train", true).put("departure_date", local.format(java.util.Date()))
        val d = send("POST", "services/mot_pricing/next_ride", s, body)?.optJSONObject("data") ?: throw IOException("no price")
        return quoteOf(d, entrance.guests)
    }

    // The exit ticket for the entrance `rideId`, at the station checkout found, once exitQuote has priced it.
    fun exit(s: Session, st: Station, rideId: Long): Ticket {
        val body = JSONObject().put("qr", st.qr).put("id", rideId)
        return ticketOf(send("PUT", "travel/train", s, body)?.optJSONObject("data") ?: throw IOException("no ticket"))
    }

    // The account's latest ride, or null before the first.
    fun latest(s: Session): Ticket? {
        val (code, text) = request("GET", "passengers/me/history/latest", agent, s.uid, s.token, null, json = true)
        if (code == 404) return null
        val d = answer(code, text)?.optJSONObject("data") ?: return null
        return if (d.has("id")) ticketOf(d) else null
    }

    class Ride(
        val id: Long, val line: String, val towards: String, val from: String, val boardedUtc: Long, val amount: Price?,
        val full: Price?, val guests: Int, val cancelled: Boolean,
    )

    // The rides of `months` months of charges, starting `offset` months back, and whether older ones remain.
    fun billings(s: Session, offset: Int, months: Int = 6): Pair<List<Ride>, Boolean> {
        val d = send("GET", "passengers/me/billings?number_of_months=$months&months_offset=$offset", s)?.optJSONObject("data")
            ?: return emptyList<Ride>() to false
        return billingsOf(d) to d.optBoolean("more_to_load")
    }

    // Each charge on the card lists the rides in it.
    internal fun billingsOf(d: JSONObject): List<Ride> = d.optJSONArray("billings")?.objects().orEmpty()
        .flatMap { it.optJSONArray("rides")?.objects().orEmpty() }.map { r ->
            Ride(
                id = r.optLong("id"), line = r.str("line_number").orEmpty(), towards = r.str("line_destination_name").orEmpty(),
                from = r.str("boarding_station_name").orEmpty(), boardedUtc = time(r.str("boarding_date")) ?: 0L,
                amount = price(r, "amount"), full = price(r, "original_price"), guests = r.optInt("extra_passengers_count"),
                cancelled = r.str("cancel_date") != null,
            )
        }.sortedByDescending { it.boardedUtc }

    private val ISRAEL = TimeZone.getTimeZone("Asia/Jerusalem")

    // HopOn's times: ISO with an offset ("2026-10-13T18:20:00+0300"), and in its history "2026-10-13 15:20:00" in UTC.
    internal fun time(s: String?): Long? {
        s ?: return null
        s.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssZ" to null, "yyyy-MM-dd'T'HH:mm:ssXXX" to null, "yyyy-MM-dd'T'HH:mm:ss.SSSXXX" to null,
            "yyyy-MM-dd HH:mm:ss" to TimeZone.getTimeZone("UTC"), "yyyy-MM-dd'T'HH:mm:ss" to ISRAEL,
        )
        for ((p, zone) in patterns) {
            val f = SimpleDateFormat(p, Locale.ENGLISH).apply { isLenient = false; zone?.let { timeZone = it } }
            val pos = java.text.ParsePosition(0)
            val d = f.parse(s, pos) ?: continue
            if (pos.index == s.length) return d.time
        }
        return null
    }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }
    private fun JSONArray.str(i: Int): String? = if (isNull(i)) null else optString(i).takeIf { it.isNotBlank() && it != "null" }
    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key)) null else when (val v = opt(key)) { is Number -> v.toLong(); is String -> v.toLongOrNull(); else -> null }
    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else when (val v = opt(key)) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }
    private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).mapNotNull { str(it) }
}
