package io.github.fotlab.fotlab.feature.camera.model

/** USB vendor ids used for discovery and as a fallback when a device reports no name. */
object UsbVendorIds {
    const val CANON: Int = 1193
    const val NIKON: Int = 1200
    const val SONY: Int = 1356
    const val FUJIFILM: Int = 1227
    const val PANASONIC: Int = 1242
    const val SAMSUNG: Int = 1256
    const val KODAK: Int = 1034
    const val RICOH: Int = 1482
    const val OLYMPUS: Int = 1972
    const val CASIO: Int = 1350
    const val LEICA: Int = 1999
    const val SIGMA: Int = 2583
    const val HP: Int = 1008
    const val OTHER: Int = 9842

    val CAMERA_VENDORS: Set<Int> = setOf(
        CANON, NIKON, SONY, FUJIFILM, PANASONIC, SAMSUNG, KODAK, RICOH,
        OLYMPUS, CASIO, LEICA, SIGMA, HP, OTHER,
    )

    fun label(vendorId: Int): String = when (vendorId) {
        CANON -> "Canon"
        NIKON -> "Nikon"
        SONY -> "Sony"
        FUJIFILM -> "Fujifilm"
        PANASONIC -> "Panasonic"
        SAMSUNG -> "Samsung"
        KODAK -> "Kodak"
        RICOH -> "Ricoh"
        OLYMPUS -> "Olympus"
        CASIO -> "Casio"
        LEICA -> "Leica"
        SIGMA -> "Sigma"
        HP -> "HP"
        else -> "Camera"
    }
}
