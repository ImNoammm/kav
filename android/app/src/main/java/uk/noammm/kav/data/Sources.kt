package uk.noammm.kav.data

import org.json.JSONObject

// Where something comes from: Kav's own server, or the rider's own copy of it (server/selfhost). Live locations and trip
// planning each have their own (KavLive.source, KavPlan.source), and share the one own server the rider sets. A stored
// choice that's none of these reads as the old switches had it (Sources.fromOld).
enum class Source(val key: String) {
    KAV("kav"), OWN("own");

    companion object {
        fun of(key: String?): Source? = entries.firstOrNull { it.key == key }
    }
}

internal object Sources {
    // The two settings from before they were apart: live times from a server (the rider's own when one was set up, else
    // Kav's), and Kav's planner, which planned on that same server. What either left off lands on Kav's server.
    fun fromOld(liveFromKav: Boolean, planWithKav: Boolean, ownSet: Boolean): Pair<Source, Source> {
        val server = if (ownSet) Source.OWN else Source.KAV
        return (if (liveFromKav) server else Source.KAV) to (if (liveFromKav && planWithKav) server else Source.KAV)
    }

    // The same from a backup: its own choices where it has them, else its old switches; null for what it doesn't hold.
    fun fromBackup(o: JSONObject, ownSet: Boolean): Pair<Source?, Source?> {
        val old = if (o.has("liveFromKav") || o.has("planWithKav")) {
            fromOld(o.optBoolean("liveFromKav"), o.optBoolean("planWithKav"), ownSet)
        } else null
        return (Source.of(o.optString("liveSource")) ?: old?.first) to (Source.of(o.optString("planSource")) ?: old?.second)
    }

    // The server the daily timetable comes from: the rider's own when either choice uses it, else Kav's.
    fun timetableFrom(live: Source, plan: Source, own: String): String =
        if (own.isNotEmpty() && (live == Source.OWN || plan == Source.OWN)) own else KavLive.KAV_SERVER
}
