package io.github.fotlab.fotlab.feature.camera.profile

import io.github.fotlab.fotlab.feature.camera.model.CameraLink

/** Selects a protocol profile from the identity a device reports during discovery. */
object CameraProfileResolver {

    private val CANON_ENTRY_MODELS = setOf(
        "1300D", "T6", "KISSX80", "1500D", "2000D", "KISSX90",
        "T7", "3000D", "4000D", "T100",
    )

    private val CANON_R_MODELS = setOf("EOSR", "EOSRP", "EOSR5", "EOSR6", "EOSR3", "EOSR5C")

    private val NIKON_NEW_Z_MODELS = setOf("Z62", "Z72", "ZFC", "Z30", "Z8", "Z9")

    /**
     * [preferAlternateGuid] selects the second known init GUID for brands that
     * answer to more than one variant; callers can flip it when an init attempt
     * times out.
     */
    fun resolve(
        manufacturer: String?,
        model: String?,
        vendorId: Int?,
        link: CameraLink,
        preferAlternateGuid: Boolean = false,
    ): CameraProfile {
        val maker = normalize(manufacturer.orEmpty())
        val product = normalize(model.orEmpty())
        val isCanon = maker.contains("CANON") || vendorId == UsbVendorIds.CANON
        val isNikon = maker.contains("NIKON") || vendorId == UsbVendorIds.NIKON
        val isSony = maker.contains("SONY") || vendorId == UsbVendorIds.SONY
        val isFuji = maker.contains("FUJI") || maker.contains("FUJIFILM") ||
            vendorId == UsbVendorIds.FUJIFILM

        val guid = guidFor(isCanon, isNikon, isSony, isFuji, preferAlternateGuid)
        val sessionId = if (link == CameraLink.USB || isFuji) 1 else 65

        return when {
            isFuji && link == CameraLink.USB -> CameraProfile.fujiUsb()
            isFuji -> CameraProfile.generic(sessionId).copy(
                id = "fuji-ip",
                family = CameraFamily.FUJIFILM,
                guidVariant = guid,
                initPort = 55_740,
                extendedInit = true,
            )

            isSony -> CameraProfile.sony(sessionId, guid)

            isNikon -> nikonProfile(product, sessionId, guid)

            isCanon -> canonProfile(product, link, sessionId, guid)

            else -> CameraProfile.generic(sessionId).copy(guidVariant = GuidVariant.ZERO)
        }
    }

    private fun canonProfile(
        product: String,
        link: CameraLink,
        sessionId: Int,
        guid: GuidVariant,
    ): CameraProfile {
        val base = CameraProfile.canon(sessionId, guid)
        return when {
            CANON_R_MODELS.any { product == it } -> base.copy(
                id = "canon-r-fixed",
                minChunkBytes = 1024 * 1024,
                maxChunkBytes = 1024 * 1024,
                preferredChunkBytes = 1024 * 1024,
            )
            product.startsWith("EOSR") -> base.copy(id = "canon-r")
            product.contains("EOSM") -> base.copy(id = "canon-eos-m")
            product.contains("EOS1D") || product.contains("5D") -> base.copy(id = "canon-pro")
            CANON_ENTRY_MODELS.any { product == it } -> base.copy(
                id = "canon-entry",
                preListDelayUsbMs = if (link == CameraLink.USB) 4_000L else 0L,
            )
            else -> base
        }
    }

    private fun nikonProfile(product: String, sessionId: Int, guid: GuidVariant): CameraProfile {
        val base = CameraProfile.nikon(sessionId, guid)
        return when {
            NIKON_NEW_Z_MODELS.any { product == it } -> base.copy(
                id = "nikon-z-new",
                skipPropertyDescription = true,
                objectEventDelayMs = 2_500L,
            )
            product.startsWith("Z") -> base.copy(
                id = "nikon-z",
                objectEventDelayMs = 2_500L,
            )
            else -> base
        }
    }

    private fun guidFor(
        canon: Boolean,
        nikon: Boolean,
        sony: Boolean,
        fuji: Boolean,
        preferAlternate: Boolean,
    ): GuidVariant = when {
        canon || nikon || sony -> if (preferAlternate) GuidVariant.ALTERNATE else GuidVariant.PRIMARY
        fuji -> GuidVariant.SECONDARY
        else -> GuidVariant.ZERO
    }

    /** Uppercase and strip separators so "EOS R5", "EOS-R5" and "EOS_R5" match. */
    fun normalize(value: String): String =
        value.uppercase().filter { it.isLetterOrDigit() }
}
