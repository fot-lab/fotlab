package io.github.fotlab.fotlab.feature.camera.discovery

/**
 * Keyword-based brand fallback for discovery probes that cannot read an
 * authoritative `manufacturer` field (mDNS, manual IP entry, ...).
 *
 * The caller is expected to already know whether a manufacturer was present —
 * this object is used only when no reliable manufacturer is available. When a
 * probe did read one (SSDP `<manufacturer>`, USB vendor-id label, ...) the
 * caller stores that value verbatim; we do **not** remap it here.
 *
 * The keyword table below is ordered by priority; the first brand whose
 * keywords hit any of the candidate strings wins. Look-up is case-insensitive.
 */
object BrandMatcher {

    /**
     * Scan the given name-ish strings (mDNS instance name, SSDP friendlyName,
     * model name, hostname, ...) against the keyword table and return the brand
     * key on first hit, or `null` when nothing matched.
     */
    fun matchFromNames(vararg candidates: String?): String? {
        val haystack = candidates
            .filterNotNull()
            .map { it.uppercase() }
            .filter { it.isNotEmpty() }
        if (haystack.isEmpty()) return null
        for ((brand, keywords) in KEYWORD_TABLE) {
            if (keywords.any { kw -> haystack.any { it.contains(kw) } }) {
                return brand
            }
        }
        return null
    }

    /**
     * Keyword table in priority order. Each entry is (display brand, Set of
     * uppercase substrings). A brand matches when any keyword is contained in
     * any of the candidate strings passed to [matchFromNames].
     */
    private val KEYWORD_TABLE: List<Pair<String, Set<String>>> = listOf(
        "SONY" to setOf(
            "SONY", "ILCE", "ILCF", "DSLR-A", "SLT-A", "NEX-", "ZV-E", "ZVE-",
        ),
        "Nikon" to setOf("NIKON"),
        "Canon" to setOf("CANON", "EOS", "IXUS", "POWERSHOT"),
        "FUJIFILM" to setOf("FUJIFILM", "FUJI", "X-T", "X100", "GFX"),
        "Lumix" to setOf("LUMIX", "PANASONIC", "DC-G", "DMC-G"),
        "Leica" to setOf("LEICA", "Q2", "Q3", "M11", "M10"),
        "SIGMA" to setOf("SIGMA", "FP", "SD1"),
        "OLYMPUS" to setOf("OLYMPUS", "OM-D", "OMSYS"),
        "PENTAX" to setOf("PENTAX"),
        "Ricoh" to setOf("RICOH"),
        "SAMSUNG" to setOf("SAMSUNG", "NX-"),
        "Kodak" to setOf("KODAK"),
        "CASIO" to setOf("CASIO"),
        "HP" to setOf("HP"),
    )
}
