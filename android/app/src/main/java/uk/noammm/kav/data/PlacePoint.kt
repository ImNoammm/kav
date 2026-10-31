package uk.noammm.kav.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Where a picked Google suggestion puts a trip's end, the one point the trip, Recents and favourites keep: a town starts
// at its hub in the timetable (TownHub), anything else at Google's point.
object PlacePoint {
    suspend fun of(p: Transit.Place, net: suspend () -> Net?): Transit.Place {
        if (p.googleId == null || p.type != Transit.PLACE_TOWN) return p
        val at = try {
            withContext(Dispatchers.Default) { net()?.let { n -> TownHub.point(n, p.lat, p.lon) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return at?.let { (lat, lon) -> Transit.Place(p.name, p.detail, lat, lon, p.type, p.mode) } ?: p
    }
}
