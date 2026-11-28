package io.github.fotlab.fotlab.feature.camera.protocol.opcode

/**
 * Canon 0x91xx vendor extension opcodes.
 *
 * Canon replaces most of the standard browsing family (0x1004–0x100B) with
 * 0x91xx commands. These work on both USB and PTP/IP — unlike Sony's 0x92xx,
 * Canon does not segment its vendor extensions by transport.
 *
 * The OBJECT_HANDLES / OBJECT_HANDLES_ALT pair are two endpoints Canon cameras
 * answer for the same listing; OBJECT_HANDLES_ALT (0x917A) is used when the
 * primary refuses.
 */
object PtpCanon {
    // ── Storage ───────────────────────────────────────────────────────────
    const val STORAGE_IDS: Int = 0x9101

    // ── Session ───────────────────────────────────────────────────────────
    const val SESSION: Int = 0x910A

    // ── Listing ───────────────────────────────────────────────────────────
    const val OBJECT_HANDLES: Int = 0x9116
    const val OBJECT_HANDLES_ALT: Int = 0x917A

    // ── Object info ────────────────────────────────────────────────────────
    const val OBJECT_INFO_EX: Int = 0x9173

    // ── Transfer ──────────────────────────────────────────────────────────
    /** Same 3-param shape as standard GetPartialObject (0x101B). */
    const val PARTIAL_OBJECT: Int = 0x9174
    const val THUMB: Int = 0x910B

    // ── Properties ────────────────────────────────────────────────────────
    const val SET_PROPERTY: Int = 0x9110
}
