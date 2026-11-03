package uk.noammm.kav.data

import org.json.JSONObject

// Live locations, planning and timetables have independent providers and share one own-server
// configuration. Unknown stored choices use the legacy switches (Sources.fromOld).
enum class Source(val key: String) {
    KAV("kav"), OWN("own"), BUS_NEARBY("bus-nearby"), EGG("egg"), MOOVIT("moovit");

    val hopOn get() = this == BUS_NEARBY || this == EGG
    // Another app's own service rather than a copy of Kav's server: nothing of it to set up or tap through.
    val provider get() = hopOn || this == MOOVIT
    // Moovit only plans: live locations never come from it.
    val live get() = this != MOOVIT

    companion object {
        fun of(key: String?): Source? = entries.firstOrNull { it.key == key }
        fun liveOf(key: String?): Source? = of(key)?.takeIf { it.live }

        // Each choice's options, in the order Settings offers them.
        val LIVE = listOf(KAV, BUS_NEARBY, EGG, OWN)
        val PLANNING = listOf(MOOVIT, BUS_NEARBY, EGG, KAV, OWN)
        val TIMETABLE = listOf(MOOVIT, BUS_NEARBY, EGG, KAV, OWN)
    }
}

internal object Sources {
    // The two settings from before they were apart: live times from a server (the rider's own when one was set up, else
    // Kav's), and Kav's planner, which planned on that same server, only with live times from it too. Live times they
    // left to Moovit land on Kav's server; trips they left to Moovit stay with Moovit, now from its website, as do a new
    // rider's, who has neither switch yet.
    fun fromOld(liveFromKav: Boolean, planWithKav: Boolean, ownSet: Boolean): Pair<Source, Source> {
        val server = if (ownSet) Source.OWN else Source.KAV
        return (if (liveFromKav) server else Source.KAV) to (if (liveFromKav && planWithKav) server else Source.MOOVIT)
    }

    // The same from a backup: its own choices where it has them, else its old switches; null for what it doesn't hold.
    fun fromBackup(o: JSONObject, ownSet: Boolean): Pair<Source?, Source?> {
        val old = if (o.has("liveFromKav") || o.has("planWithKav")) {
            fromOld(o.optBoolean("liveFromKav"), o.optBoolean("planWithKav"), ownSet)
        } else null
        return (Source.liveOf(o.optString("liveSource")) ?: old?.first) to (Source.of(o.optString("planSource")) ?: old?.second)
    }

    // The timetable for a rider who hasn't picked one: the week from the rider's own server when either other choice
    // uses it, as it came before it was a choice of its own, else Moovit's, the default.
    fun timetableOf(live: Source, plan: Source, ownSet: Boolean): Source =
        if (ownSet && (live == Source.OWN || plan == Source.OWN)) Source.OWN else Source.MOOVIT

    // The server the week on the phone comes from: the rider's own when it's the timetable picked, else none: GitHub
    // builds it (Timetable.RELEASE), never Kav's server. A provider picked still has the week as the phone's own copy.
    fun timetableFrom(timetable: Source, own: String): String? = if (own.isNotEmpty() && timetable == Source.OWN) own else null
}
