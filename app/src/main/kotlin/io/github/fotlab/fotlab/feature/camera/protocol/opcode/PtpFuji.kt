package io.github.fotlab.fotlab.feature.camera.protocol.opcode

/**
 * Fujifilm USB vendor extension opcodes — 0xD2xx / 0xDFxx family.
 *
 * Fuji does not expose a real OpenSession flow; instead the device is
 * addressed with a fixed storage ID and the listing command. Both
 * OBJECT_HANDLES and OBJECT_HANDLES_FALLBACK are known to answer on
 * different Fuji models — the profile tries the primary and falls back
 * if it refuses.
 *
 * Fuji has no documented PTP/IP support; all known Fuji vendor opcodes are
 * USB-only.
 */
object PtpFuji {
    // ── Session / device info ─────────────────────────────────────────────
    const val SESSION: Int = 0xDF01
    const val DEVICE_INFO: Int = 0xDF00

    // ── Listing ───────────────────────────────────────────────────────────
    const val OBJECT_HANDLES: Int = 0xD212
    const val OBJECT_HANDLES_FALLBACK: Int = 0xD401

    // ── Transfer ──────────────────────────────────────────────────────────
    const val PARTIAL_OBJECT: Int = 0xD244

    // ── Fallback values used when GetStorageIDs refuses ──────────────────
    /** Fuji cameras often report only this single storage. */
    const val STORAGE_ID_FALLBACK: Long = 0x10000001L

    // ── Chunk tuning ──────────────────────────────────────────────────────
    const val MIN_CHUNK_BYTES: Int = 1 * 1024 * 1024 // 1 MiB
    const val MAX_CHUNK_BYTES: Int = 3 * 1024 * 1024 // 3 MiB
    const val PREFERRED_CHUNK_BYTES: Int = 1 * 1024 * 1024 // 1 MiB
}
