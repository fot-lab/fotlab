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

    /**
     * Path variant of [loadRawlerImage]: decode a RAW that this process already copied into its own
     * private storage, addressed by real filesystem [path]. The native side memory-maps the file
     * (`RawSource::new`), so unlike the `ByteArray` variant the source bytes are never read into the
     * Java heap nor copied a second time inside Rust — the two full-size copies on the open path
     * disappear (`rules/REVIEW/detail/OPTIMZ-PERFRM-000002.md`). Everything after the decode is
     * identical: the returned handle behaves exactly like [loadRawlerImage]'s
     * (`FOTLAB-RAWLER-000004` §lifecycle). Null on failure / absent library.
     */
    fun loadRawlerImageFromFile(path: String): RawlerImageLoaded? =
        runCatching { decodeRawlerImageFromPath(path) }.getOrNull()

    /** Grayscale raw-preview PNG from an already-loaded image — no re-decode; null on failure. */
    fun previewRawlerImage(loaded: RawlerImageLoaded): ByteArray? =
        runCatching { loaded.previewPng() }.getOrNull()

    /**
     * Whether a resident decode can be developed at quarter resolution — i.e. whether
     * `DemosaicAlgorithm.SUPERPIXEL` will actually resolve to superpixel for it rather than falling
     * back to the CFA default. Answered natively from the decoded sensor/CFA metadata, using the same
     * guard the develop pipeline itself applies, so the Studio menu can grey that entry out instead of
     * letting the pick quietly render something else. `false` on an absent library, which keeps the
     * entry disabled rather than promising something it cannot do.
     */
    fun supportsDownsample(loaded: RawlerImageLoaded): Boolean =
        runCatching { loaded.supportsDownsample() }.getOrDefault(false)

    /**
     * Render an already-loaded image and return the finished PNG — the single develop/grade entry.
     *
     * Replaces the old `developRawlerImage` / `gradeRawlerImageToPng` pair: there is one trunk
     * natively now, and [stages] says where the output lands. Passing `develop = false` with the
     * [cache] handle from [takeDemosaicedCameraImage] skips decode→demosaic entirely, which is
     * what makes a grade-only edit cheap.
     *
     * [kelvin] > 0 overrides the white balance for that colour temperature; ≤ 0 keeps as-shot.
     * Null on failure (a bad LUT path makes the native grader error) or when the library is absent.
     */
    fun renderRawlerImage(
        loaded: RawlerImageLoaded,
        params: DevelopParams,
        stages: PipelineStages,
        gradeParams: GradeParams,
        cache: DemosaicedCameraImage?,
        kelvin: Float,
    ): ByteArray? = runCatching {
        loaded.renderPng(params, stages, gradeParams, cache, kelvin)
    }.getOrNull()

    /**
     * Hand over the demosaiced camera-space buffer the last render produced, leaving nothing
     * behind — so at most one is ever alive. Null when the last render did not build one (it was
     * itself a cached render, or nothing has been rendered yet).
     *
     * Kotlin owns the handle from here: keep it, pass it back to [renderRawlerImage] as `cache`,
     * and drop the previous one before taking a new one. Dropping it on a file switch is all the
     * cleanup that needs.
     */
    fun takeDemosaicedCameraImage(loaded: RawlerImageLoaded): DemosaicedCameraImage? =
        runCatching { loaded.takeDemosaicedCameraImage() }.getOrNull()

    /**
     * Auto-exposure metering of a resident [loaded] image with rawalchemy's 5-strategy meter.
     * [mode] is one of `"average" | "center-weighted" | "highlight-safe" | "hybrid" | "matrix"`.
     *
     * Develops the cached decode with [params] into a linear ProPhoto buffer (no re-decode), meters
     * it, and returns the **EV offset (stops)** that would drive the image to the metering target —
     * relative to the current develop state, so the caller adds any already-applied exposure to
     * obtain an absolute value. This only measures; it applies nothing. [targetGray] `null` = the
     * native default (0.18). Unknown mode / native error / absent library degrades to `null`.
     */
    fun meterAutoExposure(
        loaded: RawlerImageLoaded,
        params: DevelopParams,
        mode: String,
        targetGray: Float?,
    ): Float? = runCatching { loaded.meterAutoExposure(params, mode, targetGray) }.getOrNull()

    /**
     * Log curves the native grading engine accepts (sorted natively), for the Studio LOG chooser.
     * These are display names — vendor spelled out, curve written the vendor's way, e.g.
     * "FUJIFILM F-Log2 C" — and they are exactly the strings [GradeParams.logSpace] takes back;
     * the display↔engine mapping lives in the cxx shim, so nothing here needs to know it.
     * Empty when the library / the `rawalchemy` feature is absent — the chooser then only offers
     * "none".
     */
    fun supportedGradeLogSpaces(): List<String> =
        runCatching { supportedLogSpaces().toList() }.getOrDefault(emptyList())

    /**
     * The demosaic algorithms the Studio dropdown may offer, in display order: rawler's own
     * debayers (`RAWLER …`) followed by the RawTherapee kernels ported in `rawtrp_demosaic`
     * (`RAWTRP …`), each carrying the [DemosaicAlgorithm] the menu sends back
     * (`FOTLAB-NATIVE-000004` D5). Not a develop call — it only enumerates, so it is cheap
     * enough to read once and cache.
     *
     * Named `demosaicAlgorithms` rather than `demosaicCandidates` for the reason this facade
     * renames everything: the generated binding *is* a top-level `demosaicCandidates()` in this
     * same package, and a member of the same name would shadow it and recurse forever.
     *
     * Empty when the library is absent. The menu then renders with no entries instead of
     * crashing the screen — the same degradation every call here gets, and the right one,
     * because the list is data rather than a promise the app can keep without the `.so`.
     */
    fun demosaicAlgorithms(): List<DemosaicCandidate> =
        runCatching { demosaicCandidates() }.getOrDefault(emptyList())
}
