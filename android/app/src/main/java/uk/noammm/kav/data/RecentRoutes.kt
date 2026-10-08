package uk.noammm.kav.data

import android.content.Context
import org.json.JSONObject
import java.io.File

// The routes a recent trip's search found, so the trip reopens on them instead of planning again, as Moovit's Recent
// Journeys do. A file per trip, for the newest trips only; older ones plan afresh when opened.
object RecentRoutes {
    class Saved(val routes: List<Moovit.Itinerary>, val resolved: Moovit.Resolved, val sections: List<Moovit.Section>)

    private fun dir(ctx: Context) = File(ctx.filesDir, "recent-routes")
    private fun file(ctx: Context, key: String) = File(dir(ctx), key.replace('>', '_') + ".json")

    fun save(ctx: Context, key: String, routes: List<Moovit.Itinerary>, resolved: Moovit.Resolved, sections: List<Moovit.Section>) {
        val f = file(ctx, key)
        val tmp = File(f.path + ".tmp")
        try {
            dir(ctx).mkdirs()
            tmp.writeText(JourneyFile.routesJson(routes, resolved, sections).toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) {
            tmp.delete()
        }
    }

    fun load(ctx: Context, key: String): Saved? =
        runCatching { JourneyFile.routes(JSONObject(file(ctx, key).readText())) }.getOrNull()

    // Drops the routes of every trip but these.
    fun keep(ctx: Context, keys: Collection<String>) {
        val wanted = keys.mapTo(HashSet()) { file(ctx, it).name }
        dir(ctx).listFiles()?.forEach { if (it.name !in wanted) it.delete() }
    }
}
