package io.github.fotlab.fotlab_rawler

/**
 * Kotlin-side facade over the `rawler_fotlab` native library (R8 / `FOTLAB-STUDIO-000001`).
 *
 * This file is the **only** hand-written file in this package, and it is committed. The
 * UniFFI bindings it calls (`identify`, `decodeToPng`, `developToPng`) are **generated**
 * from `app/src/binding/rust/rawler_fotlab` by CI and dropped into a build directory
 * (`app/build/generated/uniffi/main/kotlin`), never into `src/` — generated code is a build
 * artifact and is not tracked (`FOTLAB-STRUCT-000002` R1). The generated code lands in this
 * same package (see `app/src/binding/rust/rawler_fotlab/uniffi.toml` `package_name`), so the
 * top-level functions are called below without an import.
 *
 * **This object is the single cross-language boundary.** No other code in the app may call the
 * generated native functions (`identify` / `decodeToPng` / `developToPng`) directly — every
 * business/runtime caller (the sniffer, the decoder, the Studio engine) must go through one of
 * the renames defined here (`identifyFormat` / `decodeRawToPng` / `developRawToPng`). The facade
 * renames the calls on purpose: the generated bindings are top-level functions with the upstream
 * names, and a same-named member here would shadow them and recurse.
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

    /** Call #2 (decode): grayscale raw-preview PNG bytes, or null if rawler cannot decode / the library is absent. */
    fun decodeRawToPng(raw: ByteArray): ByteArray? = runCatching { decodeToPng(raw) }.getOrNull()

    /** Develop call: demosaic + calibrate into a linear PNG using [params], or null on failure / absent library. */
    fun developRawToPng(raw: ByteArray, params: DevelopParams): ByteArray? =
        runCatching { developToPng(raw, params) }.getOrNull()

    /**
     * Load (decode once) a RAW into a resident [RawlerImageLoaded] held by Kotlin as a UniFFI
     * handle — the slow decode runs exactly once here. Returns null on failure / absent library.
     * This object is the cache the preview/develop calls reuse (`FOTLAB-RAWLER-000004`).
     */
    fun loadRawlerImage(raw: ByteArray): RawlerImageLoaded? =
        runCatching { decodeRawlerImage(raw) }.getOrNull()

    /** Grayscale raw-preview PNG from an already-loaded image — no re-decode; null on failure. */
    fun previewRawlerImage(loaded: RawlerImageLoaded): ByteArray? =
        runCatching { loaded.previewPng() }.getOrNull()

    /** Develop an already-loaded image into a linear PNG — no re-decode; null on failure. */
    fun developRawlerImage(loaded: RawlerImageLoaded, params: DevelopParams): ByteArray? =
        runCatching { loaded.developToPng(params) }.getOrNull()

    /**
     * Develop an already-loaded image into a finished sRGB PNG, overriding the white balance with the
     * multipliers for a target color temperature ([kelvin] Kelvin) — no re-decode; null on failure.
     * The Kelvin→multiplier projection happens natively; only the `f32` crosses the FFI.
     */
    fun developRawlerImageAtKelvin(loaded: RawlerImageLoaded, params: DevelopParams, kelvin: Float): ByteArray? =
        runCatching { loaded.developToPngAtKelvin(params, kelvin) }.getOrNull()

    /**
     * Develop an already-loaded image into linear ProPhoto-D50, hand it to the rawalchemy grading
     * engine (Boost / LOG / LUT per [gradeParams]) and return the graded result encoded straight to
     * PNG — direct 0..1→0..255 quantization, **no** transfer function, since the grade already
     * encoded the image (`FOTLAB-RAWLER-000006` decision 4). The Studio grade bar calls this; null
     * on failure (a bad LUT path makes the native grader error) or when the library is absent.
     */
    fun gradeRawlerImageToPng(
        loaded: RawlerImageLoaded,
        params: DevelopParams,
        gradeParams: GradeParams,
    ): ByteArray? = runCatching { loaded.developAndGradeToPng(params, gradeParams) }.getOrNull()

    /** Kelvin variant of [gradeRawlerImageToPng] — the WB override carries into a grade re-render. */
    fun gradeRawlerImageToPngAtKelvin(
        loaded: RawlerImageLoaded,
        params: DevelopParams,
        kelvin: Float,
        gradeParams: GradeParams,
    ): ByteArray? = runCatching { loaded.developAndGradeToPngAtKelvin(params, kelvin, gradeParams) }.getOrNull()

    /**
     * Names of the log curves the native grading engine accepts (sorted natively), for the Studio
     * LOG chooser. Empty when the library / the `rawalchemy` feature is absent — the chooser then
     * only offers "none".
     */
    fun supportedGradeLogSpaces(): List<String> =
        runCatching { supportedLogSpaces().toList() }.getOrDefault(emptyList())
}
