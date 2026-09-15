package io.github.fotlab.fotlab_rawler

/**
 * Kotlin-side facade over the `rawler_fotlab` native library (R8 / `FOTLAB-STUDIO-000001`).
 *
 * This file is the **only** hand-written file in this package, and it is committed. The
 * UniFFI bindings it calls (`identify`, `decodeToPng`) are **generated** from
 * `app/src/binding/rust` by CI and dropped into a build directory
 * (`app/build/generated/uniffi/main/kotlin`), never into `src/` — generated code is a build
 * artifact and is not tracked (`FOTLAB-STRUCT-000002` R1). The generated code lands in this
 * same package (see `app/src/binding/rust/uniffi.toml` `package_name`), so the top-level
 * functions are called below without an import.
 *
 * The facade deliberately renames the calls (`identifyFormat` / `decodeRawToPng`): the
 * generated bindings are top-level functions with the upstream names, and a same-named
 * member here would shadow them and recurse.
 *
 * Every call is wrapped so a missing / not-yet-loaded `librawler_fotlab.so` degrades
 * gracefully to `null` (the Studio pipeline then falls through to Unsupported) instead of
 * crashing — `runCatching` catches the `UnsatisfiedLinkError`/`ExceptionInInitializerError`
 * raised when the library is absent. The native side itself is panic-hardened
 * (`FOTLAB-CRASH-000001`): rawler panics are caught inside Rust and never cross the FFI
 * boundary, so malformed/non-RAW input returns `null` rather than aborting the process.
 */
object RawlerFotlabBridge {

    /** Call #1 (identify): camera make/model if rawler recognizes the bytes, else null. */
    fun identifyFormat(raw: ByteArray): String? = runCatching { identify(raw) }.getOrNull()

    /** Call #2 (decode): PNG bytes, or null if rawler cannot decode / the library is absent. */
    fun decodeRawToPng(raw: ByteArray): ByteArray? = runCatching { decodeToPng(raw) }.getOrNull()
}
