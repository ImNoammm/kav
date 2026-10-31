package uk.noammm.kav.ui

// How bus line badges look: Kav's own plate in the operator's colour, or a grey one over a strip of it.
enum class LineBadgeAppearance {
    KAV, STRIP;

    companion object {
        // A stored choice. There have only ever been the two, so anything stored but Kav's own is the strip, whatever
        // name an earlier build gave it.
        fun of(stored: String?): LineBadgeAppearance = if (stored == null || stored == KAV.name) KAV else STRIP
    }
}
