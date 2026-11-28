package io.github.fotlab.fotlab.feature.camera.protocol.opcode

/**
 * Sony 0x92xx vendor extension opcodes — USB transport only.
 *
 * Sony's 0x92xx command family answers with empty or truncated data on PTP/IP;
 * the standard PTP opcodes must be used instead on network links. See
 * [PtpCanon] / [PtpNikon] for how other vendors handle the same boundary.
 *
 * Parameter shapes differ from the standard commands:
 * - CONTENT_LIST pages by timestamp cursor (u64 + u32 count + class byte)
 * - PARTIAL_OBJECT takes packed-format + 64-bit offset, no standard counterpart
 */
object PtpSony {
    // ── Discovery / capability ────────────────────────────────────────────
    const val CAPABILITY: Int = 0x9202

    // ── Storage ───────────────────────────────────────────────────────────
    const val STORAGE_IDS: Int = 0x9209

    // ── Listing ───────────────────────────────────────────────────────────
    /** Sony's CONTENT_LIST returns full object records, not bare handles. */
    const val CONTENT_LIST: Int = 0x923C

    // ── Transfer ──────────────────────────────────────────────────────────
    /** 5 params: handle(low 24-bit) + packedFormat + offset(64-bit) + size. */
    const val PARTIAL_OBJECT: Int = 0x923D

    const val THUMB: Int = 0x923E

    // ── Write ─────────────────────────────────────────────────────────────
    const val DELETE_OBJECT: Int = 0x9250

    /** Packed (class byte + format) value assumed for a handle the listing
     *  has not seen — still-image class (0x01) + undefined format (0x0001). */
    const val STILL_IMAGE_PACKED_FORMAT: Long = 0x01000001L

    /** PAGE_SIZE for CONTENT_LIST paging. */
    const val CONTENT_LIST_PAGE_SIZE: Int = 500
}
