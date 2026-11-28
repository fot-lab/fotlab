package io.github.fotlab.fotlab.feature.camera.protocol.opcode

/**
 * Nikon vendor extension opcodes — 0x90xx / 0x94xx family.
 *
 * Nikon keeps the standard GetObjectInfo (0x1008) for per-object metadata,
 * but uses vendor commands for listing (0x90C7) and range reads (0x9431).
 * LARGE_OBJECT_LENGTH (0x9421) repairs the 64-bit size of objects that the
 * standard ObjectInfo truncates into a 32-bit sentinel.
 */
object PtpNikon {
    // ── Session ───────────────────────────────────────────────────────────
    const val SESSION: Int = 0x90C4

    // ── Listing ───────────────────────────────────────────────────────────
    const val OBJECT_HANDLES: Int = 0x90C7

    // ── Transfer ──────────────────────────────────────────────────────────
    /** Same 3-param shape as standard GetPartialObject (0x101B). */
    const val PARTIAL_OBJECT: Int = 0x9431

    const val THUMB: Int = 0x9203

    // ── Repair ───────────────────────────────────────────────────────────
    /** Repairs the 64-bit size after ObjectInfo returned a u32 sentinel. */
    const val LARGE_OBJECT_LENGTH: Int = 0x9421

    // ── Tuning ────────────────────────────────────────────────────────────
    const val MAX_CHUNK_BYTES: Int = 5 * 1024 * 1024 // 5 MiB
}
