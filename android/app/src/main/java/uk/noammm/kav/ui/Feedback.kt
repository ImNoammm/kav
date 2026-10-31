package uk.noammm.kav.ui

import android.content.Context
import android.os.Build
import java.net.URLEncoder
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.Source
import uk.noammm.kav.data.StopNames
import uk.noammm.kav.data.Updates

// "Found a bug?" and "Request a feature?" open GitHub's forms (.github/ISSUE_TEMPLATE) with what Kav knows about itself
// already filled in. GitHub takes a form's text fields from the address, by their ids; the labels come from the form,
// whoever opens it. Never a server's address or anything about the rider.
internal fun feedbackLink(ctx: Context, bug: Boolean): String =
    if (bug) issueLink(
        "bug_report.yml",
        listOf(
            "version" to Updates.installedVersion(ctx),
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "device" to deviceName(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty()),
            "setup" to setupLine(KavLive.source, KavPlan.source, T.lang, StopNames.english),
        ),
    )
    else issueLink("feature_request.yml", listOf("version" to Updates.installedVersion(ctx)))

internal fun issueLink(form: String, fields: List<Pair<String, String>>): String =
    "$REPO_URL/issues/new?template=$form" + fields.filter { it.second.isNotBlank() }.joinToString("") { (id, value) ->
        // Spaces as %20 rather than +: a space to whatever decodes the query.
        "&$id=" + URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

// "Google Pixel 8", "Samsung SM-S918B": the maker once, even where the model already starts with it.
internal fun deviceName(maker: String, model: String): String {
    val m = maker.trim().replaceFirstChar { it.titlecase(java.util.Locale.ROOT) }
    return if (m.isEmpty() || model.startsWith(m, ignoreCase = true)) model.trim() else "$m ${model.trim()}"
}

// In English, for whoever reads the issue: where live times and trips are set to come from, each its own choice.
internal fun setupLine(live: Source, plan: Source, lang: Lang, englishNames: Boolean): String {
    fun from(s: Source, server: String, own: String) = when (s) { Source.KAV -> server; Source.OWN -> own }
    return "Live: ${from(live, "Kav's server", "own server")}. " +
        "Trips: ${from(plan, "Kav's planner", "own server's planner")}. " +
        "Language: ${if (lang == Lang.HE) "Hebrew" else "English"}, stop names in ${if (englishNames) "English" else "Hebrew"}."
}
