package uk.noammm.kav.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Lang(val code: String, val label: String) {
    EN("en", "English"),
    HE("he", "עברית"),
}

object T {
    var lang by mutableStateOf(Lang.EN)

    var wanted by mutableStateOf<Lang?>(null)
        private set

    fun switchTo(l: Lang) { wanted = if (l == lang) null else l }

    fun commit() { wanted?.let { lang = it; wanted = null } }

    operator fun invoke(en: String, he: String): String = if (lang == Lang.HE) he else en

    val rtl: Boolean get() = lang == Lang.HE


    fun ltr(s: String): String = if (rtl) "\u2066" + s + "\u2069" else s

    // A count and what it counts, singular for one: count(3, "1 stop", "תחנה אחת", "stops", "תחנות") is "3 stops".
    fun count(n: Long, one: String, oneHe: String, many: String, manyHe: String): String =
        if (n == 1L) invoke(one, oneHe) else invoke("$n $many", "$n $manyHe")

    fun count(n: Int, one: String, oneHe: String, many: String, manyHe: String): String =
        count(n.toLong(), one, oneHe, many, manyHe)

    val locale: java.util.Locale
        get() = java.util.Locale.forLanguageTag(if (rtl) "he-IL" else "en-GB")
}

fun mirrorX(f: Float): Float = if (T.rtl) 1f - f else f
