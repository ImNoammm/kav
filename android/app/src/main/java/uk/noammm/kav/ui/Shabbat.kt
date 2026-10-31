package uk.noammm.kav.ui

import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.Transit

// Shabbat, when public transport mostly stops: in Israel, from Friday noon to Saturday 21:00, wide enough for the end
// of Shabbat in any season.
internal fun inShabbat(epoch: Long): Boolean {
    val day = KavLive.dayOf(epoch)
    val second = KavLive.secondOfDay(epoch)
    return day == 5 && second >= 12 * 3600 || day == 6 && second < 21 * 3600
}

// A search made during Shabbat that found nothing to ride. searchAt: when the rider wants to leave.
internal fun nothingOnShabbat(searchAt: Long, routes: List<Transit.Itinerary>): Boolean =
    inShabbat(searchAt) && routes.none { it.rides.isNotEmpty() }

// The line above routes that set out after a day off ends, which the planner marks when a search falls on one and
// nothing runs then.
internal fun afterDayOff(day: Transit.DayOff): String = when (day) {
    Transit.DayOff.SHABBAT -> T(
        "No public transport during Shabbat. These leave after it ends:",
        "אין תחבורה ציבורית בשבת. אלה יוצאים אחרי צאת השבת:",
    )
    Transit.DayOff.HOLIDAY -> T(
        "No public transport during the holiday. These leave after it ends:",
        "אין תחבורה ציבורית בחג. אלה יוצאים אחרי צאת החג:",
    )
}

internal val SHABBAT_NOTHING get() = T(
    "No public transport during Shabbat. Try a time after it ends.",
    "אין תחבורה ציבורית בשבת. נסו שעה אחרי צאת השבת.",
)
