package uk.noammm.kav.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.Moovit

// The road a route takes, from Kav's server: empty until it comes, or if the route has none.
@Composable
internal fun rememberKavShape(route: Int): List<Pair<Double, Double>> {
    val shape by produceState(KavLive.shapeNow(route), route) {
        value = KavLive.shapeNow(route)
        if (route <= 0 || value.isNotEmpty()) return@produceState
        // One that didn't come, while the server restarts say, is asked for again a little later.
        for (wait in longArrayOf(0, 5_000, 15_000)) {
            delay(wait)
            withContext(Dispatchers.IO) { runCatching { KavLive.shape(route) }.getOrNull() }?.let { value = it; return@produceState }
        }
    }
    return shape
}

// Bus positions from Kav's server for these routes while the screen is on, asked again when the
// server expects newer ones. Empty when live locations come from Moovit.
@Composable
internal fun rememberKavSpots(routes: List<Int>): Map<String, KavLive.Spot> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val on = KavLive.enabled
    val asked = remember(routes) { routes.distinct().take(KavLive.FOLLOW_MAX).sorted() }
    // Each Moovit refresh can bring a slightly different set of routes; that alone asks no more than every 10 s.
    val last = remember { longArrayOf(0L) }
    val spots by produceState(emptyMap<String, KavLive.Spot>(), asked, on, lifecycle) {
        if (!on || asked.isEmpty()) { value = emptyMap(); return@produceState }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay((last[0] + 10_000 - System.currentTimeMillis()).coerceAtLeast(0))
            while (true) {
                last[0] = System.currentTimeMillis()
                val wait = try {
                    val answer = withContext(Dispatchers.IO) { KavLive.ask(asked) }
                    value = answer.spots
                    answer.next - System.currentTimeMillis() / 1000
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    30L
                }
                delay(wait.coerceIn(5, 30) * 1000L)
            }
        }
    }
    return spots
}

// How fresh a bus's position is, coloured as the search results colour live times: green, yellow, red.
internal enum class Freshness { LIVE, ESTIMATED, OFF }

internal fun freshnessOf(sampleUtc: Long, now: Long): Freshness = when {
    now - sampleUtc < KavLive.FRESH_S -> Freshness.LIVE
    now - sampleUtc < KavLive.STALE_S -> Freshness.ESTIMATED
    else -> Freshness.OFF
}

internal fun freshColour(f: Freshness): androidx.compose.ui.graphics.Color = when (f) {
    Freshness.LIVE -> K.realtime
    Freshness.ESTIMATED -> K.problem
    Freshness.OFF -> K.critical
}

// What a bus card says about a position it has.
internal fun locationHeadline(sampleUtc: Long, now: Long): Pair<String, androidx.compose.ui.graphics.Color> =
    when (freshnessOf(sampleUtc, now)) {
        Freshness.LIVE -> T("Location updated recently", "המיקום עודכן לאחרונה") to K.realtime
        Freshness.ESTIMATED -> T("Location is estimated", "המיקום משוער") to K.problem
        Freshness.OFF -> T("Location may not be accurate", "ייתכן שהמיקום אינו מדויק") to K.critical
    }

// When the bus last reported where it was, and how fast it went then when Kav's server says.
internal fun updatedLabel(a: Moovit.Arrival): String {
    val at = clockFormat().format(java.util.Date(a.sampleUtc * 1000))
    val speed = when {
        !a.hasLocation || a.speed < 0 -> ""
        a.speed == 0 -> T(" · standing still", " · עומד במקום")
        else -> T(" · ${a.speed} km/h", " · ${a.speed} קמ״ש")
    }
    return T("Location updated: $at", "המיקום עודכן: $at") + speed
}

// A trip's live layer with its buses where Kav's server last saw them, when that's newer than Moovit.
@Composable
internal fun rememberFresher(trip: Moovit.Itinerary, r: Moovit.Resolved): Moovit.Resolved {
    val routes = remember(trip, r.live) { KavLive.tripRoutes(trip, r) }
    val spots = rememberKavSpots(routes)
    return remember(r, spots) { if (spots.isEmpty()) r else r.withLive(KavLive.fresher(r.live, spots)) }
}
