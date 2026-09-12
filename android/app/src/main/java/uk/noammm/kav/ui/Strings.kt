package uk.noammm.kav.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The languages Kav speaks. Each label is written in its own language. */
enum class Lang(val code: String, val label: String) {
    EN("en", "English"),
    HE("he", "עברית"),
}

/**
 * Kav's own words, both languages side by side at the point of use.
 *
 * Android's string resources are the usual home for this, and they were the first
 * plan. They do not fit: a third of Kav's text is built in plain functions - `dur`,
 * `modeName`, `depNote`, `stepInstruction`, the `Tab` enum, the `Sort` and
 * `ResultFilter` enums - where `stringResource` cannot reach at all and a Context
 * would have to be threaded through each one just to call `getString`. Nor do
 * positional format args survive a sentence whose word order changes between the
 * two languages.
 *
 * Keeping the pair at the call site costs none of that: a template interpolates the
 * way Kotlin already interpolates, every function can read it, and a translation is
 * impossible to lose track of because it sits on the same line as the sentence it
 * translates.
 *
 * The one thing this does not give us is the system language. Kav asks the rider
 * instead, in onboarding, because the stop names coming back from Moovit are Hebrew
 * whatever the phone is set to - the choice here is about Kav's own chrome, and a
 * phone in English is not evidence of which one its owner wants to read.
 *
 * [lang] is snapshot state, so changing it recomposes every screen that reads a
 * word. There is no Activity to recreate and no locale to fight with.
 */
object T {
    var lang by mutableStateOf(Lang.EN)

    /**
     * The language asked for, before it is taken up.
     *
     * [lang] is read by every word on screen and by the layout direction with it, so
     * setting it turns the whole app over between two frames: correct, and
     * indistinguishable from a glitch. A request goes here instead, and
     * [LanguageSwitch] hides the turnover behind a short dip. Set [lang] directly
     * only where there is nothing on screen to animate - the value restored at launch.
     */
    var wanted by mutableStateOf<Lang?>(null)
        private set

    /** Asking again for the language already in force cancels a switch still in the air. */
    fun switchTo(l: Lang) { wanted = if (l == lang) null else l }

    /** Called from inside the dip, where the change cannot be read as a flicker. */
    fun commit() { wanted?.let { lang = it; wanted = null } }

    operator fun invoke(en: String, he: String): String = if (lang == Lang.HE) he else en

    /** Is the interface being read right to left? */
    val rtl: Boolean get() = lang == Lang.HE

    /**
     * The chevron that means onward, and the one that means back. Compose mirrors the
     * layout around these from LocalLayoutDirection, but a chevron is only a character:
     * left alone it keeps its shape and ends up pointing back the way the eye came.
     */
    val onward: String get() = if (rtl) "‹" else "›"
    val backward: String get() = if (rtl) "›" else "‹"

    /**
     * Keeps a run written in Latin and digits in the order it was written. Hebrew turns
     * the paragraph around, and the bidi algorithm turns "3 / 7" into "7 / 3" and
     * "Kav 1.2" into "1.2 Kav", because a neutral between two numbers takes the
     * paragraph's own direction. Isolating the run says: this much is an island, read
     * it left to right and put the island where the sentence wanted it.
     */
    fun ltr(s: String): String = if (rtl) "\u2066" + s + "\u2069" else s

    /**
     * For the dates Kav formats itself. A month or weekday name is words, so it has to
     * follow Kav's language; leaving it on the phone's default put "Mon 15 Sep" in the
     * middle of an otherwise Hebrew sheet.
     */
    val locale: java.util.Locale
        get() = java.util.Locale.forLanguageTag(if (rtl) "he-IL" else "en-GB")
}

/**
 * Mirrors one horizontal fraction of a glyph Kav draws itself. Compose mirrors layout
 * around `LocalLayoutDirection`, but a Canvas is painted inside its own box with x
 * running left to right whichever language is on, so a chevron that means "onward"
 * keeps pointing the way the eye came. This is the Canvas twin of [T.onward].
 *
 * Only glyphs that carry a direction go through it. A gear, a walker, a bus and the
 * play triangle on Start are pictures rather than directions, and the platform leaves
 * those alone in Hebrew too.
 */
fun mirrorX(f: Float): Float = if (T.rtl) 1f - f else f
