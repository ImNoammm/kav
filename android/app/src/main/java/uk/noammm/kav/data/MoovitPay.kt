package uk.noammm.kav.data

// Paying for a ride with the rider's own Moovit payment account ("IsraelMot", the Ministry of
// Transport service Pango runs). Every call goes out as a Moovit user made only for paying: the SMS
// code ties the account to that user, and Kav plans and searches with a different one.
object MoovitPay {
    // Paying runs in Moovit's IsraelMot context and signing in runs in the one Moovit's settings name
    // (DEFAULT_PAYMENT_CONTEXT, "Login@Default"). A code sent in one is refused in the other ("The code you entered
    // doesn't match the one sent"). Kav reads the setting as Moovit's app does, and its recipe holds both for when
    // Moovit can't be asked.
    private val CONTEXT get() = KavRecipe.moovit.payContext
    private val LOGIN get() = Moovit.setting("DEFAULT_PAYMENT_CONTEXT")?.ifBlank { null } ?: KavRecipe.moovit.payLoginContext
    const val BUS = 3

    // Moovit's MVPaymentRegistrationStep.
    const val STEP_PHONE = 1
    const val STEP_PAYMENT_METHOD = 8
    const val STEP_TERMS = 10

    class Refused(val title: String, message: String) : Exception(message)

    class Unauthorized : Exception("not signed in")

    class Terms(
        val title: String, val text: String, val agree: String,
        val links: List<Pair<String, String>>, val button: String, val version: Int,
    )

    // The card on the account, when Moovit asks a newly connected phone to confirm it with its CVV.
    class Card(val type: String, val last4: String)

    class Steps(val missing: List<Int>, val terms: Terms?, val card: Card?)

    // exists: the number has a payment account (isAccountExist). moved: this user now holds it (isMigratedUser); until
    // then the same code is sent again with takeOver, as Moovit's app does without asking.
    class Verified(val exists: Boolean, val moved: Boolean, val missing: List<Int>, val card: Card?)

    class Account(val name: String, val phone: String, val connected: Boolean)

    class Price(val agorot: Long, val code: String) {
        val text get() = "₪" + String.format(java.util.Locale.US, "%.2f", agorot / 100.0).removeSuffix(".00")
    }

    // A price as Moovit's app keeps one (MotActivationPrice): what is charged, the full fare (the charge itself
    // when Moovit leaves it out) and why the charge is lower, such as a free transfer.
    class Cost(val price: Price, val full: Price, val reasons: List<String>)

    class Fare(
        val code: Int, val radius: Int, val regionId: Int, val originRegionId: Int,
        val price: Price, val full: Price?, val from: String?, val to: String?,
        internal val reasons: List<String>, internal val cases: List<String>,
    ) {
        val cost get() = Cost(price, full ?: price, reasons)
    }

    class Offer(val context: String, val profile: String, val fares: List<Fare>)

    // mode is Moovit's MVPTBTransitType (1 city bus .. 7 train station); radius is the fare's reach in metres.
    // anonymous: a guest's ticket rather than the account holder's own.
    class Ticket(
        val id: Int, val ref: String, val boughtUtc: Long, val title: String, val price: Price?,
        val profile: String, val agency: String, val active: Boolean, val qr: String, val endsUtc: Long,
        val passenger: String, val radius: Int, val mode: Int, val fromStopId: Int, val toStopId: Int,
        val anonymous: Boolean = false,
    ) {
        // A train entrance still waiting for its exit ticket.
        val needsExit get() = mode == 7 && fromStopId != 0 && toStopId == 0
    }

    // The free-ride window Moovit shows while a ticket is in use ("You've got free rides until 14:52").
    class Window(val fromUtc: Long, val untilUtc: Long, val title: String, val text: String)

    class Wallet(val tickets: List<Ticket>, val window: Window?)

    class Charge(val name: String, val atUtc: Long, val amount: Price?, val full: Price?)

    // A billing statement: the open one for the current period, or a finished one.
    class Statement(val price: Price?, val full: Price?, val atUtc: Long, val monthly: Boolean)

    class Billing(val current: Statement?, val past: List<Statement>)

    // Modes paid at a station rather than by a code on the vehicle, as Moovit's MVRouteType.
    const val TRAM = 0
    const val RAIL = 2
    const val CABLE = 5

    class Station(
        val stopId: Int, val name: String, val price: Price?, val full: Price?, val fareCode: Int, val radius: Int,
        val context: String, val destinationStopId: Int,
        internal val reasons: List<String>, internal val cases: List<String>,
    ) {
        val cost get() = price?.let { Cost(it, full ?: it, reasons) }
    }

    // Moovit either names the station and its price, or asks the rider to pick from a list of its stops.
    class StationStep(val station: Station?, val pickOrigin: List<Int>, val pickDestination: List<Int>, val title: String)

    // Moovit's GetPrice answer: my ticket, and each other passenger's, who rides anonymously.
    class Quote(val main: Cost?, val other: Cost?)

    // Moovit's fare summary (MotPricesSummaryViewModel): my ticket and `guests` other passengers, added up once
    // for the charge and once for the full fares. Every purchase includes my own ticket.
    class Summary(val me: Cost?, val other: Cost?, val guests: Int) {
        val count get() = guests + 1
        val total get() = add(me?.price, other?.price)
        val full get() = add(me?.full, other?.full)
        private fun add(mine: Price?, each: Price?) =
            if (mine == null || (guests > 0 && each == null)) null
            else Price(mine.agorot + guests * (each?.agorot ?: 0), mine.code)
    }

    // Without an answer from GetPrice, Moovit's app falls back to the fare itself for me and to its full fare
    // for everyone else.
    fun summary(quote: Quote?, fare: Cost?, guests: Int) =
        Summary(quote?.main ?: fare, quote?.other ?: fare?.let { Cost(it.full, it.full, emptyList()) }, guests)

    // The end of a train ride: the fare to the station Moovit places the rider at, or a list to pick from.
    class Exit(val stopId: Int, val name: String, val price: Price?, val full: Price?, val pick: List<Int>, val title: String)

    // Binary Thrift both ways, as Moovit's app sends these. Null for an empty answer.
    // Moovit's app numbers each screen flow and sends the number with every call in it, so the SMS and the code
    // that answers it arrive as one flow. A new sign-in starts a new one.
    private val flows = java.util.concurrent.atomic.AtomicInteger((1..200).random())
    @Volatile private var flow = flows.incrementAndGet()
    fun newFlow() { flow = flows.incrementAndGet() }

    private fun call(user: MoovitSession, path: String, body: TWriter): Map<Int, Any?>? {
        val headers = Moovit.authHeaders(user) + mapOf("flow-sequence-id" to flow.toString(), "analytics-flow-key-id" to flow.toString())
        val (code, raw) = Moovit.post(Moovit.APP4, path, body.stop().bytes(), headers)
        val s = runCatching { TReader(raw).readStruct() }.getOrNull()
        if (code == 204 || (code == 200 && raw.isEmpty())) return null
        // Calls with nothing to say (accepting the terms, sending the SMS) answer 200 with a body that
        // is not a struct; Moovit's app never reads it.
        if (code == 200) return s
        if (code == 401) throw Unauthorized()
        val title = s?.str(1)
        if (title != null) throw Refused(title, s.str(2) ?: title)
        throw java.io.IOException("$path HTTP $code")
    }

    private fun Map<Int, Any?>.str(id: Int) = (this[id] as? String)?.takeIf { it.isNotEmpty() }
    private fun Map<Int, Any?>.int(id: Int) = (this[id] as? Number)?.toInt()
    private fun Map<Int, Any?>.long(id: Int) = (this[id] as? Number)?.toLong()
    private fun Map<Int, Any?>.bool(id: Int) = this[id] == true
    @Suppress("UNCHECKED_CAST")
    private fun Map<Int, Any?>.rec(id: Int) = this[id] as? Map<Int, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun Map<Int, Any?>.recs(id: Int) = (this[id] as? List<*>).orEmpty().mapNotNull { it as? Map<Int, Any?> }
    private fun Map<Int, Any?>.ints(id: Int) = (this[id] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() }
    private fun Map<Int, Any?>.strs(id: Int) = (this[id] as? List<*>).orEmpty().mapNotNull { it as? String }

    private fun latlon(at: Pair<Double, Double>) =
        TWriter().i32Field(1, (at.first * 1e6).toInt()).i32Field(2, (at.second * 1e6).toInt())

    // MVMissingPaymentRegistrationSteps field 5: the card Moovit wants confirmed, if it asks for one.
    private fun cardOf(steps: Map<Int, Any?>?): Card? {
        val pm = steps?.rec(5)?.takeIf { it.bool(4) } ?: return null
        val c = pm.rec(2)?.rec(1) ?: return null
        return Card(c.str(1).orEmpty(), c.str(2) ?: return null)
    }

    // What still stands between this user and paying, with the terms when accepting them is one step.
    // login: what signing in needs (the phone), else what paying needs (the terms, the card).
    fun steps(user: MoovitSession, login: Boolean = false): Steps {
        val root = call(user, "PaymentContext/GetMissingSteps", TWriter().strField(1, if (login) LOGIN else CONTEXT))?.rec(1)
            ?: return Steps(emptyList(), null, null)
        val terms = root.rec(4)?.let { t ->
            val cta = t.rec(6) ?: t.rec(3)
            Terms(
                title = t.str(1).orEmpty(), text = t.str(2).orEmpty(), agree = cta?.str(1).orEmpty(),
                links = cta?.recs(2).orEmpty().map { it.str(1).orEmpty() to it.str(2).orEmpty() },
                button = t.str(7).orEmpty(), version = t.int(8) ?: 1,
            )
        }
        return Steps(root.ints(2), terms, cardOf(root))
    }

    // Moovit's app accepts the terms before it asks for the phone number.
    fun acceptTerms(user: MoovitSession, version: Int) {
        call(user, "PaymentContext/TosConfirmation", TWriter().strField(1, CONTEXT).i32Field(2, version))
    }

    // An Israeli number the way Moovit's app sends it, "054-123-4567", with the calling code apart.
    fun sendCode(user: MoovitSession, phone: String) {
        val d = phone.filter(Char::isDigit).let { if (it.startsWith("972")) "0" + it.drop(3) else it }
        val shown = if (d.length == 10) "${d.take(3)}-${d.substring(3, 6)}-${d.drop(6)}" else d
        call(user, "PaymentContext/GenerateVerificationToken",
            TWriter().strField(1, shown).strField(2, "+972").strField(3, LOGIN))
    }

    // The SMS code. Asked first without takeOver, then again with it when the account exists.
    fun verify(user: MoovitSession, code: String, takeOver: Boolean): Verified {
        val body = TWriter().strField(1, LOGIN).strField(2, code).boolField(3, !takeOver)
        val root = call(user, "PaymentContext/RegistrationVerification", body) ?: return Verified(true, false, emptyList(), null)
        val steps = root.rec(2)
        return Verified(exists = root.bool(3), moved = root.bool(1), missing = steps?.ints(2).orEmpty(),
            card = cardOf(steps))
    }

    // Confirm the account's card on this phone with its CVV, as Moovit's app does after a move. The CVV
    // goes to Moovit once and is kept nowhere.
    fun confirmCard(user: MoovitSession, cvv: String) {
        call(user, "PTB/Accounts/SetBillingAccount", TWriter().strField(1, CONTEXT).strField(2, cvv))
    }

    fun account(user: MoovitSession): Account? {
        val acc = call(user, "PaymentContext/GetPaymentAccount", TWriter())?.rec(1) ?: return null
        val person = acc.rec(3)
        return Account(
            name = listOfNotNull(person?.str(1), person?.str(2)).joinToString(" "),
            phone = person?.str(6).orEmpty(),
            connected = acc.recs(2).any { it.str(1) == CONTEXT && it.int(2) == 2 },
        )
    }

    private fun priceOf(m: Map<Int, Any?>?): Price? {
        val agorot = m?.long(1) ?: return null
        return Price(agorot, m.str(4) ?: "ILS")
    }

    // MVPTBActivationPrice: price, full price, discount reasons.
    private fun costOf(m: Map<Int, Any?>?): Cost? {
        val price = priceOf(m?.rec(1)) ?: return null
        return Cost(price, priceOf(m?.rec(2)) ?: price, m?.strs(3).orEmpty())
    }

    // MVCurrencyAmount as Moovit's app sends one back: agorot, no id or symbol, the currency code.
    private fun amount(p: Price) = TWriter().i64Field(1, p.agorot).i32Field(2, 0).strField(3, "").strField(4, p.code)

    // The fares for the bus whose QR code was scanned, priced from where Moovit is told the rider is.
    fun price(user: MoovitSession, qr: String, at: Pair<Double, Double>, transitType: Int = BUS): Offer {
        val body = TWriter().strField(1, qr).structField(2, latlon(at)).i32Field(3, transitType)
        val root = call(user, "PTB/Activations/GetActivationPriceV2", body) ?: throw java.io.IOException("no price")
        val regions = root.recs(5).associate { (it.int(1) ?: 0) to it.str(3) }
        val fares = root.recs(2).flatMap { f ->
            f.recs(4).mapNotNull { rp ->
                val ap = rp.rec(2) ?: return@mapNotNull null
                Fare(
                    code = f.int(1) ?: return@mapNotNull null, radius = f.int(2) ?: 0,
                    regionId = rp.int(1) ?: return@mapNotNull null, originRegionId = f.int(5) ?: 0,
                    price = priceOf(ap.rec(1)) ?: return@mapNotNull null, full = priceOf(ap.rec(2)),
                    from = f.int(5)?.let { regions[it] }, to = rp.int(1)?.let { regions[it] },
                    reasons = ap.strs(3), cases = ap.strs(4),
                )
            }
        }
        return Offer(root.str(1).orEmpty(), root.str(3).orEmpty(), fares)
    }

    private fun quoteOf(intent: TWriter, user: MoovitSession): Quote {
        val root = call(user, "PTB/Activations/GetPriceFullDetails", TWriter().structField(1, intent))
            ?: throw java.io.IOException("no quote")
        return Quote(costOf(root.rec(1)), costOf(root.rec(2)))
    }

    fun quote(user: MoovitSession, offer: Offer, fare: Fare, at: Pair<Double, Double>): Quote {
        val bus = TWriter()
            .structField(1, TWriter().i32Field(1, fare.code).i32Field(2, fare.radius))
            .structField(2, regionPrice(fare))
        if (fare.originRegionId != 0) bus.i32Field(3, fare.originRegionId)
        bus.strField(4, offer.context).structField(5, latlon(at))
        return quoteOf(TWriter().structField(1, bus), user)
    }

    fun quote(user: MoovitSession, s: Station, routeType: Int): Quote {
        val station = TWriter().i32Field(1, routeType).i32Field(2, s.stopId)
        if (s.destinationStopId != 0) station.i32Field(3, s.destinationStopId)
        s.price?.let { station.structField(4, amount(it)) }
        s.full?.let { station.structField(5, amount(it)) }
        station.structField(6, TWriter().i32Field(1, s.fareCode).i32Field(2, s.radius)).strField(7, s.context)
        return quoteOf(TWriter().structField(2, station), user)
    }

    private fun regionPrice(fare: Fare): TWriter {
        val activationPrice = TWriter().structField(1, amount(fare.price))
        fare.full?.takeIf { it.agorot > fare.price.agorot }?.let { activationPrice.structField(2, amount(it)) }
        if (fare.reasons.isNotEmpty()) activationPrice.listField(3, TType.STRING, fare.reasons) { w, s -> w.str(s) }
        if (fare.cases.isNotEmpty()) activationPrice.listField(4, TType.STRING, fare.cases) { w, s -> w.str(s) }
        return TWriter().i32Field(1, fare.regionId).structField(2, activationPrice)
    }

    // Buy the chosen fare for `count` riders, me first: each ticket that comes back carries a QR for the inspector.
    fun buy(user: MoovitSession, offer: Offer, fare: Fare, at: Pair<Double, Double>, count: Int = 1): List<Ticket> {
        val body = TWriter()
            .strField(1, offer.context)
            .structField(2, latlon(at))
            .structField(3, TWriter().i32Field(1, fare.code).i32Field(2, fare.radius))
            .structField(4, regionPrice(fare))
            .i32Field(5, count)
        if (fare.originRegionId != 0) body.i32Field(9, fare.originRegionId)
        return ticketsOf(call(user, "PTB/Activations/SetActivationV2", body)?.rec(1))
    }

    private fun ticketsOf(group: Map<Int, Any?>?): List<Ticket> {
        val ref = group?.str(1).orEmpty()
        return group?.recs(2).orEmpty().map { ticketOf(it, ref) }.ifEmpty { throw java.io.IOException("no ticket") }
    }

    private fun ticketOf(a: Map<Int, Any?>, groupRef: String) = Ticket(
        id = a.int(1) ?: 0, ref = a.str(19) ?: groupRef, boughtUtc = a.long(2) ?: 0, title = a.str(3).orEmpty(),
        price = priceOf(a.rec(4)?.rec(1)), profile = a.str(5).orEmpty(), agency = a.str(7).orEmpty(),
        active = (a.int(10) ?: 1) == 1, qr = a.str(12).orEmpty(), endsUtc = a.long(15) ?: 0,
        passenger = a.str(18).orEmpty(), radius = a.rec(17)?.int(2) ?: 0, mode = a.int(16) ?: 0,
        fromStopId = a.int(13) ?: 0, toStopId = a.int(14) ?: 0, anonymous = a.bool(6),
    )

    // The station at `at` for a mode (TRAM, RAIL, CABLE). Kav sends the station's own position, so the
    // accuracy is the station's, not a phone fix. origin and destination are stops picked from Moovit's list.
    fun station(user: MoovitSession, at: Pair<Double, Double>, routeType: Int, origin: Int = 0, destination: Int = 0): StationStep {
        val body = TWriter().structField(1, latlon(at)).i32Field(2, routeType)
        if (destination != 0) body.i32Field(3, destination)
        if (origin != 0) body.i32Field(4, origin)
        body.i32Field(7, 10).i32Field(8, 0)
        val step = call(user, "PTB/Activations/GetStationInfo", body)?.rec(1) ?: throw java.io.IOException("no station")
        step.rec(1)?.let { s ->
            val ap = s.rec(4)
            return StationStep(Station(
                stopId = s.int(1) ?: 0, name = s.str(2).orEmpty(), price = priceOf(ap?.rec(1)), full = priceOf(ap?.rec(2)),
                fareCode = s.rec(6)?.int(1) ?: 0, radius = s.rec(6)?.int(2) ?: 0, context = s.str(7).orEmpty(), destinationStopId = s.int(5) ?: 0,
                reasons = ap?.strs(3).orEmpty(), cases = ap?.strs(4).orEmpty(),
            ), emptyList(), emptyList(), "")
        }
        step.rec(3)?.let { return StationStep(null, it.ints(1), emptyList(), it.str(2).orEmpty()) }
        step.rec(2)?.let { return StationStep(null, emptyList(), it.ints(1), it.str(2).orEmpty()) }
        throw java.io.IOException("no station")
    }

    // Pay at the station: the entrance ticket, whose QR opens the gate. picked: the station came from the list.
    fun enter(user: MoovitSession, s: Station, at: Pair<Double, Double>, routeType: Int, count: Int, picked: Boolean): List<Ticket> {
        val body = TWriter().strField(1, s.context).structField(2, latlon(at)).i32Field(3, count).i32Field(5, routeType)
        if (s.destinationStopId != 0) body.i32Field(6, s.destinationStopId)
        if (picked && s.stopId != 0) body.i32Field(7, s.stopId)
        return ticketsOf(call(user, "PTB/Activations/SetActivationByLocation", body)?.rec(1))
    }

    // What leaving the train at `at` costs, or the stations to pick from when Moovit can't tell.
    fun exitPrice(user: MoovitSession, at: Pair<Double, Double>): Exit {
        val root = call(user, "PTB/Activations/FinishTrainEstimatedPrice", TWriter().strField(1, CONTEXT).structField(2, latlon(at)))
            ?: throw java.io.IOException("no exit price")
        root.rec(1)?.let { e ->
            return Exit(e.int(3) ?: 0, e.str(4).orEmpty(), priceOf(e.rec(1)), priceOf(e.rec(2)), emptyList(), "")
        }
        val pick = root.rec(2) ?: throw java.io.IOException("no exit price")
        return Exit(0, "", null, null, pick.ints(1), pick.str(2).orEmpty())
    }

    // Leave the train: the exit ticket for the gate. fromStopId and ref are the entrance ticket's; manual when
    // the exit station was picked from the list. cancel instead drops an unused entrance, free of charge.
    fun exit(user: MoovitSession, at: Pair<Double, Double>, fromStopId: Int, ref: String, manual: Boolean, cancel: Boolean = false): List<Ticket> {
        val body = TWriter().strField(1, CONTEXT).structField(2, latlon(at)).i32Field(3, fromStopId).strField(4, ref)
            .boolField(5, manual).boolField(6, cancel)
        val group = call(user, "PTB/Activations/FinishTrainActivation", body)?.rec(1) ?: return emptyList()
        return group.recs(2).map { ticketOf(it, group.str(1).orEmpty()) }
    }

    // Today's tickets, newest first, with the free-ride window when one is running.
    fun tickets(user: MoovitSession): Wallet {
        val root = call(user, "PTB/Activations/GetCurrentActivations", TWriter()) ?: return Wallet(emptyList(), null)
        val tickets = root.recs(1).flatMap { g -> g.recs(2).map { ticketOf(it, g.str(1).orEmpty()) } }
            .sortedByDescending { it.boughtUtc }
        val info = root.rec(2)
        val bar = info?.rec(4)
        val window = if (bar != null && (bar.long(2) ?: 0L) > 0L) Window(
            bar.long(1) ?: 0L, bar.long(2) ?: 0L, info.rec(2)?.str(2).orEmpty(), info.rec(2)?.str(3).orEmpty(),
        ) else null
        return Wallet(tickets, window)
    }

    // What was charged in one month (1..12), as Moovit's app lists ride history, a month at a time.
    fun history(user: MoovitSession, month: Int, year: Int): List<Charge> {
        val body = TWriter().structField(1, TWriter().i32Field(1, month).i32Field(2, year))
        val root = call(user, "PTB/Activations/TransactionsHistory", body) ?: return emptyList()
        return root.recs(1).map { c ->
            Charge(c.str(2).orEmpty(), c.long(3) ?: 0L, priceOf(c.rec(4)), priceOf(c.rec(6)))
        }.sortedByDescending { it.atUtc }
    }

    // The running total for the current billing period and the statements already closed.
    fun billing(user: MoovitSession): Billing {
        val root = call(user, "PTB/Activations/GetBillingInfo", TWriter()) ?: return Billing(null, emptyList())
        val current = root.rec(1)?.let { Statement(priceOf(it.rec(1)), priceOf(it.rec(2)), it.long(5) ?: 0L, it.int(4) == 2) }
        val past = root.recs(2).map { Statement(priceOf(it.rec(2)), null, it.long(5) ?: 0L, it.int(6) == 2) }
        return Billing(current, past.sortedByDescending { it.atUtc })
    }
}
