package io.github.fotlab.fotlab.feature.gallery

/**
 * The seven display modes of the gallery content region (`FOTLAB-UIXDES-000004` R9).
 *
 * `DetailList` is a single-column list; `Grid1`…`Grid6` are N-column grids. The enum ordinal order
 * is exactly the cycle order of R9, so [cycle] is just the next entry modulo `entries.size`.
 * [fromColumns] resolves the integer persisted in the `DataStore` back to a mode; any unknown
 * value falls back to [DEFAULT] (a 3-column grid), the default of R9.
 */
enum class GalleryLayoutMode(val columns: Int) {

    DetailList(0),
    Grid1(1),
    Grid2(2),
    Grid3(3),
    Grid4(4),
    Grid5(5),
    Grid6(6);

    /** True for any grid mode; `false` only for [DetailList]. */
    val isGrid: Boolean get() = this != DetailList

    companion object {

        /** The default mode of R9: a 3-column grid. */
        val DEFAULT: GalleryLayoutMode = Grid3

        /** Next mode in the fixed cycle order of R9, wrapping around to the first. */
        fun cycle(current: GalleryLayoutMode): GalleryLayoutMode =
            entries[(current.ordinal + 1) % entries.size]

        /** Resolve a stored column count back to a mode; unknown values fall back to [DEFAULT]. */
        fun fromColumns(columns: Int): GalleryLayoutMode =
            entries.firstOrNull { it.columns == columns } ?: DEFAULT
    }
}
