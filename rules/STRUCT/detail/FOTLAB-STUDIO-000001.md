# Studio frontend rendering — Coil-only raster decode; RAW converted upstream

- ID: FOTLAB-STUDIO-000001
- Status: Draft
- Priority: P0
- Created: 2026-09-14
- Owner: —
- Related: `DNGLAB-SURVEY-000002` (§5 capability boundary — dnglab is RAW→DNG only, cannot decode jpg/png/RAW for display), `FOTLAB-NATIVE-000001` (single `external/` integration point; native layer converts RAW), `FOTLAB-STRUCT-000001` (single-module source layout; the render layer lives in the studio feature package)

> **Note on naming**: this item uses the standard `FOTLAB-STRUCT-NNNNNN` ID with a new `STUDIO` category
> code (exactly 6 chars, per `STRUCT.md` §"Encoding Rules"). It records a core architecture decision for
> the Studio screen's image-rendering layer, and also fixes the **different** fidelity contract for the
> Library grid/thumbnail viewer.

## Background & Goal

fotlab renders user photos in two surfaces with very different fidelity needs:

- **Library viewer** — a grid / thumbnail browser. It only needs a *small, fast* representation of each
  photo so scrolling stays smooth and memory stays low. Precision is irrelevant here.
- **Studio** — the inspection / editing surface. The user is looking at (and will edit) the actual photo,
  so it MUST show the **true developed image** (real demosaic, white balance, camera→sRGB color), not a
  camera-baked proxy.

Both surfaces share one hard rule: the frontend render layer is **Coil-only** and receives **only
Coil-supported rasters** (see R1–R3). The two surfaces differ only in *how the RAW→raster asset is
produced upstream* (R4 for Studio, R6 for Library). Two constraints shape the design:

1. **Frontend rendering must be simple and reliable.** We do not hand-roll bitmap decoding or gesture
   handling; we reuse a mature, officially-maintained loader and viewer.
2. **The decode backend has a hard capability boundary.** `rawler` / `dnglab` is a RAW→DNG converter and
   **cannot** decode standalone jpg/png or any camera RAW for on-screen display (`DNGLAB-SURVEY-000002` §5).
   Likewise, Coil — our chosen loader — does **not** natively decode camera RAW either (see §"Constraints").

## Requirement

- **R1 — Coil is the only decode/display loader.** Both surfaces MUST render images through **Coil**
  exclusively (Coil 3.x; on Android it decodes via `ImageDecoder` (API 28+) with a `BitmapFactory`
  fallback, plus Coil's own SVG / video-frame decoders). No first-party bitmap decode, no direct
  `BitmapFactory` / `ImageDecoder` calls, no hand-written RAW/vendor-format parser in the UI path.
- **R2 — Only Coil-supported rasters reach the render layer.** The file/URI handed to Coil MUST be one of
  Coil's natively supported raster formats. Coil's native set (Android platform decoders): **JPEG, PNG,
  GIF, WebP, BMP, HEIF/HEIC (API 28+), AVIF (device-dependent, API 31+)**, plus Coil SVG and video frames.
- **R3 — Format preference for any derived/preview asset.** When fotlab *produces* an asset to display
  (e.g. a RAW→raster proxy, or an exported preview), prefer, in order:
  1. **PNG** — lossless, best for previews / round-tripping / transparency;
  2. **JPEG** — smallest and most universally compatible, acceptable when lossless is not required;
  3. **any other Coil-supported format** (WebP, HEIF/HEIC, AVIF, …) when a specific need justifies it.
- **R4 — Studio requires PRECISE RAW decode (full conversion); the cheap preview is forbidden there.**
  When a RAW file is opened in **Studio**, the native-integration layer MUST run a **precise, full decode**
  — the `rawler` develop pipeline (demosaic + black/white normalization + white balance + camera→sRGB
  color matrix) producing the *actual rendered sensor image* — and emit a Coil-supported raster (PNG
  preferred per R3). Coil MUST NOT load the RAW directly. The embedded-preview shortcut (R6) is
  **explicitly forbidden in Studio**: the user is inspecting/editing and must see the true developed
  photo, not the camera's baked-in JPEG. This follows `DNGLAB-SURVEY-000002` §5.3.
- **R5 — Decode vs. view transform are separate concerns.** Coil owns *decode*; zoom/pan/scale is owned by
  an official Android viewer class (PhotoView, or Jetpack Compose `zoomable` / `SubsamplingScaleImageView`
  for very large images). The viewer consumes the Bitmap/ImageBitmap Coil yields; it does not decode.
- **R6 — Library viewer uses embedded-preview extraction (cheap path), scoped to Library ONLY.** In the
  **Library** grid/thumbnail viewer, a RAW file's preview is produced by **extracting the embedded JPEG
  preview** that the camera wrote into the RAW container — the same mechanism Android's `MediaStore` /
  `MediaProvider` and open-source file managers (e.g. Material Files by zhanghai) use. This is
  byte-reading the container: **no demosaic, no sensor decode** (see Constraints), so it is an O(1) seek
  and milliseconds even for 50MP+ files, and the extracted JPEG is already a Coil-supported raster (R2).
  This shortcut MUST stay **confined to the Library viewer**; it is not a substitute for Studio's precise
  decode (R4).
- **R7 — Scope boundary between the two surfaces.** Embedded-preview extraction (R6) ∈ **Library only**.
  Precise full decode (R4) ∈ **Studio only**. Neither surface ever hands a RAW file to Coil (R1/R2).
- **R8 — Every input passes through a first-party format-sniffing wrapper before routing.** The wrapper
  runs the **Coil-side** sniffer and the **rawler/dnglab-side** sniffer (RAW-center; `rawler::get_decoder`,
  `rawler/src/decoders/mod.rs:909`) **in parallel with a timeout**, and emits a **dictionary** keyed by
  sniffer — `Map<Sniffer, Verdict>` where each `Verdict = { format?, canDecode }`. The Coil-side sniffer
  MUST reuse the platform/Coil native format detector (Android `BitmapFactory` with `inJustDecodeBounds`,
  i.e. the same decoder Coil wraps — **no hand-rolled magic bytes**) and reports `canDecode = true` only
  when the platform returns a non-null `outMimeType`. The wrapper classifies only — it never decodes
  pixels. The **route decision** is a separate pure function of that dictionary; its rules are TBD (see Q6).
  A dictionary (not a fixed matrix) is used so future sniffers are added as new keys without restructuring
  the result type.

## Constraints

- **Coil cannot decode camera RAW.** Verified against Coil's current docs (coil-kt.github.io/coil, 2026):
  Coil lists GIF / SVG / video-frame and ordinary raster support; the docs contain **no** mention of RAW
  (ARW / CR2 / CR3 / NEF / RAF / DNG / ORF / RW2). Coil's decode set is the Android platform decoder set,
  which does not include any vendor RAW. Coil *can* be extended with a custom `Decoder` (its
  "Extending the Image Pipeline" capability), but **no official RAW decoder ships** — bridging a RAW
  library (rawler/dnglab, LibRaw, …) to emit a raster is the app's job, which R4/R6 assign upstream.
- **dnglab cannot decode jpg/png/RAW for display** — see `DNGLAB-SURVEY-000002` §5. So neither the
  frontend loader nor the RAW backend is a general image viewer; the boundary in R4 is mandatory.
- **RAW thumbnails come from the embedded preview, not from decoding pixels.** Verified by two facts:
  (a) Android's `MediaStore`/`MediaProvider` and open-source file managers generate RAW thumbnails via the
  system thumbnail API (`ContentResolver.loadThumbnail` (API 29+) / `ThumbnailUtils.createImageThumbnail`
  (API 23+), per Android's "Generate media thumbnails" guide), which reads the **embedded JPEG preview**
  inside the RAW container — independent of any bundled RAW library; (b) the `raw-preview-extractor`
  project documents that nearly every modern camera embeds a **full JPEG preview** in CR2/CR3 (Canon),
  NEF (Nikon), ARW (Sony), DNG (Adobe), and that extracting it needs *"no demosaicing, no sensor data, no
  dependencies: just byte reading"*. Edge formats without a preview (e.g. 2001 Nikon D1H, old .CRW,
  preview-less DNG) yield no thumbnail — the same limitation applies to fotlab's Library path (R6).
  Proprietary RAW (ARW/CR2/NEF/RAF) has **no** platform pixel decoder; DNG decode support is OEM/version
  dependent and, where present, also relies on the embedded preview IFD.
- **Two-tier fidelity is intentional.** Library = cheap embedded preview (fast grid scroll, low memory);
  Studio = precise full decode via dnglab (faithful inspection/editing). This mirrors the Snapseed-style
  preview-proxy + on-demand full-res strategy, and keeps Coil as the sole render layer in both surfaces
  (R1).
- **Format detection is content-based on both sides; file extensions are untrusted.** (a) dnglab/`rawler`
  sniffs inputs by **magic bytes / container structure, never the filename** — verified in source:
  `is_mrw` reads `\0MRM` (`rawler/src/decoders/mrw.rs:31`), `is_raf` reads `FUJIFILM` (`raf.rs:63`),
  `is_ari` reads `ARRI` (`ari.rs:18`), `is_ciff` reads `HEAPCCDR` (`formats/ciff/mod.rs:41`),
  `is_x3f` reads `FOVb` (`x3f.rs:6`), `is_exif` reads `FF D8 FF E1` (`formats/jfif.rs:314`); the TIFF
  branch then matches the EXIF `Make` tag read from bytes. (b) Coil detects standard rasters via the
  platform `BitmapFactory` / `ImageDecoder`, which sniff by **file-header magic**; its `coil-gif`
  (GIF / `GIF87a`) and `coil-svg` (`<svg`) decoders also use magic bytes. **Only Coil's `coil-video`
  (video frames via `MediaMetadataRetriever`) keys off the file extension.** Consequence: renaming a file
  cannot fool either side — an ARW renamed `.jpg` is still rejected by Coil, a PNG renamed `.arw` is still
  `Unsupported` in dnglab. Therefore the native-integration layer (R4/R6) MUST emit **genuine** PNG/JPEG
  bytes (a real encoded stream), not merely rename a RAW; Coil validates the bytes, not the name.
- **Single integration point** — both RAW→raster paths live in the native-integration module
  (`FOTLAB-NATIVE-000001`), not in `external/` (per `STRUCT.md` principle 5) and not in the UI.

## Format Sniffing Wrapper (router entry)

Every incoming image byte stream MUST pass through a single first-party format-sniffing wrapper
**before any routing decision** (R8). It classifies only — it never decodes pixels or renders. The
wrapper owns the *format decision*; the *route decision* is a separate pure function of the emitted
matrix (rules TBD — Open Question Q6).

### Flow
1. **Entry** — a seekable header slice (first **1 MiB**: enough for magic bytes and the TIFF/EXIF `Make`
   tag — and any embedded-preview IFD entry — that rawler needs) enters `FormatSniffer.sniff(...)`.
2. **Parallel dispatch** — the wrapper launches two sniffers concurrently, each on its own daemon worker
   thread (`SniffThreads`), and awaits both:
   - **Coil-side sniffer** — reuses the **platform/Coil native** format detector (Android `BitmapFactory`
     `inJustDecodeBounds` → `outMimeType`; this is exactly the decoder Coil wraps for rasters, so its
     verdict equals Coil's raster-decode capability). **No hand-rolled magic bytes.** Outputs
     `Verdict { format = mime, canDecode = (mime != null) }`. Only a non-null `outMimeType` means Coil can
     render it as a raster; SVG (Coil's vector path, invisible to `BitmapFactory`) is the single minimal
     content exception.
   - **rawler/dnglab-side sniffer** — calls `rawler::get_decoder`
     (`rawler/src/decoders/mod.rs:909`). A returned decoder ⇒ `canDecode == true` with the RAW format name;
     `RawlerError::Unsupported` (CLI maps to `AppError::UnsupportedFile`, **exit code 7**) ⇒
     `canDecode == false`, `identifiedFormat == null`.
3. **Timeout** — a bounded timeout wraps the `awaitBoth`. The **default is 5 s** and is a **user preference**
   (`MediaPreference.sniffTimeoutMs`, DataStore `studio_prefs`), read by `StudioEngine` on every open; the
   settings screen that writes it is not built yet (see Q6), so the stored value is still the default. It is
   a preference (not a constant) so it can be tuned per device without
   code changes. If it elapses before **at least one** sniffer returns, the wrapper returns
   `SniffResult.Timeout` (a hard error — the caller must surface "unsupported / retry", never silently fall
   through to either side). Neither sniffer can be cancelled cooperatively (`BitmapFactory` and the native
   rawler call ignore `Thread.interrupt()`), so the deadline is enforced by **interrupting and abandoning**
   their daemon worker threads instead of waiting for them: the caller is released immediately and a late
   result is discarded.
4. **Settle** — proceed as soon as at least one sniffer returns within T, or once both return before T
   ends.
5. **Compose dictionary** — assemble `Map<Sniffer, Verdict>` (one entry per sniffer; extensible by
   adding keys) and emit a `SniffResult`:
   ```
   { COIL:   { format?, canDecode? },
     RAWLER: { format?, canDecode? } }
   ```
6. **Route (R8 / Q6, resolved)** — the dictionary is handed to the pure `route()` function. Precedence:
   `rawler.canDecode` → `RawToRaster` (rawler decodes to PNG, frontend renders the raster); else
   `coil.canDecode` → `ToCoil` (render the original source with Coil); else → `Unsupported`
   (UI shows "Unsupported Format! 不支持的格式！"). The wrapper itself does **not** hard-code the decision.

### Routing (R8 / Q6 — resolved)
The route is a pure function of the sniff dictionary (implemented in `FormatSniffer.kt`, unit-testable):

| Condition | Route | What happens |
| --- | --- | --- |
| `rawler.canDecode == true` | `RawToRaster` | **Two native calls.** Call #1 already ran inside `FormatSniffer.sniff` (`RawlerProbe` → `Verdict{format, canDecode}`). Call #2 is `RawDecoder.decodeToPng(format, source)` — today `RawlerFotlabDecoder` delegates to `RawlerFotlabBridge`, which calls the **`librawler_fotlab.so` native library** (rawler/dnglab via **UniFFI**); the returned PNG is rendered by Coil. |
| else `coil.canDecode == true` | `ToCoil` | The original source is handed to Coil (raster/SVG path). |
| else | `Unsupported` | Studio shows the "Unsupported Format! 不支持的格式！" dialog; no decode. |

**The raw path is two calls, never one.** Call #1 is *identification only* (`RawlerProbe.sniff`, run in
parallel inside `FormatSniffer.sniff`): it answers "RAW? which format? can rawler decode it?" and emits a
`Verdict`. Call #2 is *decode only* (`RawDecoder.decodeToPng`), made solely after the route resolves to
`RawToRaster`, and it receives the `format` from call #1 so the native side decodes the already-identified
RAW instead of re-identifying it. The two are separate native-integration seams.

`RawToRaster` wins over `ToCoil` when both report `canDecode` (rawler's raster is authoritative for RAW,
and Coil cannot decode RAW anyway). The native rawler decode bridge is **now wired**: `StudioEngine` sets
`rawDecoder = RawlerFotlabDecoder()` in `prepare()`, which delegates to `RawlerFotlabBridge`
(`app/src/kotlin/io/github/fotlab/fotlab/binding/dnglab/rawler_fotlab`) → the `librawler_fotlab.so`
native library over UniFFI. When `librawler_fotlab.so` is absent the bridge returns `null` and the source
falls through to `Unsupported`, so the app still runs without the native artifact.

### Native build (rawler_fotlab — R1 / FOTLAB-NATIVE-000001)
The raw path needs two native calls, both served by **one** first-party native library,
**`librawler_fotlab.so`** (the only library that carries a FotLab name; upstream `rawler` is compiled from
its own source and keeps its name):

- **Naming rule**: only `rawler_fotlab` / `dnglab_fotlab` (and names beginning with either) may name
  first-party artifacts. Anything produced *directly* by upstream source keeps the upstream name. Cargo
  `crate-type` keywords such as `cdylib` are build descriptors, never library names.
- **Source (our tree)**: `app/src/rust/binding/dnglab/rawler_fotlab/` — the first-party binding crate,
  exposing two UniFFI functions:
  - `identify(raw) -> Option<String>` — call #1, identification only (wraps `rawler::decode_dummy`).
  - `decode_to_png(raw) -> Vec<u8>` — call #2, decode the identified RAW to PNG (wraps `rawler::decode` + `image` PNG encode).
  It is a **standalone** Cargo workspace and reaches upstream through a *path* dependency
  (`rawler = { path = "../../../../../../external/dnglab/rawler" }`), so the pinned submodule is compiled
  as-is. It is deliberately **not** a member of the dnglab workspace and `external/dnglab/Cargo.toml` is
  never edited (`FOTLAB-NATIVE-000001` R4 — upstream is read-only). `uniffi.toml` sets the Kotlin
  `package_name` to the facade's package.
- **Kotlin glue**: `app/src/kotlin/io/github/fotlab/fotlab/binding/dnglab/rawler_fotlab/` holds exactly one
  **hand-written, committed** file — the facade `RawlerFotlabBridge.kt`. The UniFFI bindings it calls are
  **generated** and land in the build directory (`app/build/generated/uniffi/main/kotlin`, declared as a
  Kotlin source dir in `app/build.gradle.kts`), never in `src/`, so no generated code is committed and no
  `.gitignore` entry is needed (`FOTLAB-STRUCT-000002` R1). The facade renames the calls
  (`identifyFormat` / `decodeRawToPng`) because the generated functions are top-level and would otherwise
  be shadowed. App code only calls the facade; `RawlerProbe` (call #1) and `RawlerFotlabDecoder` (call #2) use it.
  JNA (`net.java.dev.jna:jna:<v>@aar`) is the runtime the generated bindings need on Android.
- **Build & artifact passing**: `build_rust.yaml` builds the library (`cargo ndk -o …` for
  `arm64-v8a`/`armeabi-v7a`/`x86`/`x86_64`) and generates the Kotlin bindings with the crate's own
  `uniffi-bindgen` bin (`cargo run --features cli --bin uniffi-bindgen`), which guarantees generator and
  runtime versions match; it uploads `jniLibs/` + `kotlin/` as the `rawler_fotlab` artifact.
  `build_gradle.yaml` then downloads it into `app/build/generated/jniLibs/<abi>/` and
  `app/build/generated/uniffi/main/kotlin/` before Gradle runs — both are build directories, so no
  downloaded or generated artifact is placed under `src/` (`FOTLAB-STRUCT-000002` R1). Upstream source is
  compiled from the submodule in place; only our binding carries the `rawler_fotlab` name.
- **Local builds**: because the bindings are generated in CI, a build without the native artifact cannot
  compile the facade. All builds run in CI (`rules/ACTION.md`).
- **Preview quality**: the PNG encode is 8-bit, bayer shown as grayscale; a proper demosaic/gamma pass is
  future work (FOTLAB-NATIVE-000001).

### Why run both sniffers in parallel
- Coil's sniffer is a **positive whitelist** — it knows only standard rasters and returns UNKNOWN for any
  RAW.
- rawler's sniffer is a **RAW-center negative test** — it returns `Unsupported` for everything outside its
  RAW set, including plain JPEG/PNG **and** corrupt files.
- Neither alone can drive routing. The only reliable "truly unsupported" signal is
  `coil.UNKNOWN + rawler.Unsupported`. Running both concurrently bounds latency (the slow/uncertain side
  can't block the fast side) and yields a cross-validated verdict.

### Hard rules
- Classification only — **no pixel decode**, **no render**.
- **Extensions ignored** — both sniffers are content-based (see Constraints); renaming can't fool either
  side.
- Timeout ⇒ distinct error state, never a default route.

## Acceptance Criteria

- A Studio render call accepts only a Coil-supported raster URI; loading a RAW file directly through Coil
  is rejected/avoided by construction (RAW is pre-converted upstream).
- **Studio** shows a RAW (ARW/CR2/CR3/NEF/RAF/DNG/…) via the **full `rawler` develop pipeline** (precise,
  demosaiced, white-balanced, camera→sRGB); the embedded-preview shortcut is never used there.
- **Library** shows a RAW thumbnail by **extracting the embedded JPEG preview** (no full decode); grid
  scrolling stays fast and memory stays low. An old/preview-less RAW that has no embedded preview falls
  back gracefully (no thumbnail) rather than triggering a full decode.
- Produced preview/derived assets are PNG by default, JPEG when size matters, never a format outside
  Coil's native set (R3).
- Zoom/pan uses an official viewer class; no custom gesture math in the decode path (R5).
- The embedded-preview path is provably absent from Studio code and the precise-decode path is provably
  absent from Library hot-path code (R7).
- Preview/proxy assets emitted by the native layer are **genuine encoded rasters** (Coil validates the
  bytes, not the filename); an extension-renamed RAW is never passed to Coil as if it were a raster.
- Every render/routing entry goes through `FormatSniffer`; no code path may invoke Coil decode or rawler
  decode without a prior `SniffResult` (R8).
- `SniffResult` carries the full 2×2 matrix; the route decision is a pure function of that matrix (rules
  TBD, Q6). A timeout yields a distinct error state, not a default route.
- Extension-renamed files are classified by content, not name (both sniffers are content-based).

## Impacted Modules

- `app` / studio feature package (render layer — Coil + viewer class, R1/R5).
- `app` / library feature package (grid/thumbnail viewer — Coil + viewer class; consumes embedded-preview
  rasters, R6).
- `app` / media package — **`FormatSniffer`** (first-party format-sniffing wrapper, R8): runs the
  Coil-side and rawler-side sniffers in parallel with a timeout, emits the 2×2 matrix. No pixel decode.
- Native-integration module (`external/dnglab` consumer) — **two paths**: (a) Library = extract embedded
  RAW preview (cheap, R6); (b) Studio = precise `rawler` RAW→raster conversion (R4).

## Open Questions

- Q1 — For 50MP+ Studio previews, should the native layer emit a full-res PNG or a tiled/mipmap proxy
  consumed by `SubsamplingScaleImageView`? (Affects memory; see Snapseed-style pyramid strategy.) Library
  is unaffected — it uses the embedded preview.
- Q2 — Preferred container for the Studio RAW→raster asset: PNG (lossless, larger) vs. JPEG (smaller,
  lossy) vs. WebP? Defaults to PNG per R3 unless Q1 forces a trade-off.
- Q3 — Does Studio need HEIF/AVIF output for sharing, or is PNG/JPEG sufficient for v1?
- Q4 — For R6, is the embedded preview extracted by the native layer (reusing rawler/DNG IFD parsing) or
  by a small first-party container reader? Either way it MUST NOT reach Coil as a RAW and MUST stay in the
  native-integration module.
- Q5 — If video-frame rendering is ever added (Coil `coil-video`, the only extension-based path), the
  asset naming must keep a correct video extension; photo paths (jpg/png/webp/raw) are unaffected.
- Q6 — **Routing rules over the sniff dictionary (R8) — RESOLVED.** Precedence (user-specified):
  `rawler.canDecode` → RAW→raster (rawler decodes to PNG, frontend renders it); else `coil.canDecode` →
  straight to Coil (render the original source); else → unsupported (UI shows
  "Unsupported Format! 不支持的格式！"). Implemented as the pure `route()` function in `FormatSniffer.kt`;
  `RawToRaster` wins when both report `canDecode`. The timeout `T` is a user preference defaulting to 5 s
  (`MediaPreference`, UI pending). A single-side hang (one sniffer returned, the other wedged) still fails
  the whole sniff → `SniffResult.Timeout` → `Unsupported`: the deadline wraps both callers, and the wedged
  call's daemon worker thread is interrupted and abandoned rather than waited on.

## Change History

- 2026-09-14 — Initial architecture item. Codified that the Studio frontend renders **Coil-only**
  rasters (R1–R2), with a PNG → JPEG → other-Coil-format preference for any derived asset (R3), that
  camera RAW is converted to a raster by the native layer *before* reaching Coil (R4, tied to
  `DNGLAB-SURVEY-000002` §5), and that decode (Coil) and view-transform (official viewer class) are
  separate (R5). Recorded the verified constraint that Coil 3.x does **not** natively decode camera RAW
  (ARW/CR2/CR3/NEF/RAF/DNG/ORF/RW2), so a custom bridge is the app's responsibility, which R4 places
  upstream.
- 2026-09-14 — Split the RAW→raster contract into **two tiers** after researching how Android's
  `MediaStore`/`MediaProvider` and open-source file managers (Material Files by zhanghai) preview camera
  RAW: they extract the **embedded JPEG preview** (no demosaic, byte-read), not decode pixels
  (`raw-preview-extractor` confirms CR2/CR3/NEF/ARW/DNG embed a full JPEG preview). Added **R4** (Studio =
  precise full `rawler` decode, embedded-preview shortcut forbidden) vs **R6** (Library = cheap
  embedded-preview extraction, scoped to Library only), with **R7** pinning the scope boundary. Added the
  embedded-preview constraint and the two-tier-fidelity note; updated Acceptance Criteria and Impacted
  Modules so Library and Studio each own one path.
- 2026-09-14 — Added the **content-based format detection** constraint: dnglab/`rawler` sniffs by magic
  bytes (not filename; cited `is_mrw`/`is_raf`/`is_ari`/`is_ciff`/`is_x3f`/`is_exif` source), and Coil
  detects rasters/SVG/GIF by header magic while only `coil-video` keys off the extension. Consequence:
  renaming can't fool either side, so the native layer must emit **genuine** PNG/JPEG bytes, not a renamed
  RAW. Added the matching Acceptance Criterion and Open Question Q5 (video-frame extension caveat).
- 2026-09-14 — Added **R8 + the first-party Format Sniffing Wrapper** design: every input passes through
  `FormatSniffer`, which runs the **Coil-side** (positive R2 whitelist) and **rawler/dnglab-side**
  (`rawler::get_decoder`, exit code 7 on `UnsupportedFile`) sniffers **in parallel with a timeout**, emits
  a 2×2 capability matrix `[[coil.id?, coil.dec?],[rawler.id?, rawler.dec?]]`, and leaves the *route
  decision* as a separate pure function (rules TBD → Open Question Q6). Rationale: Coil's sniffer is a
  positive whitelist (RAW ⇒ UNKNOWN) while rawler's is a RAW-center negative test (JPEG/PNG/corrupt ⇒
  `Unsupported`); only `coil.UNKNOWN + rawler.Unsupported` is a reliable "truly unsupported" signal, so
  both must run and be cross-validated. Timeout ⇒ hard error, never a silent fallback.
- 2026-09-14 — Refined R8 / wrapper per review: (1) the sniff result is now a **dictionary**
  `Map<Sniffer, Verdict>` (one key per sniffer; extensible by adding keys) instead of a fixed 2×2 matrix;
  (2) the **Coil-side sniffer reuses the platform/Coil native detector** — Android `BitmapFactory` with
  `inJustDecodeBounds` (the same decoder Coil wraps) reading `outMimeType` — so **no hand-rolled magic
  bytes**; `canDecode == true` iff `outMimeType != null`. SVG (Coil's vector path, invisible to
  `BitmapFactory`) is the single minimal content exception. Updated R8, the wrapper flow, Q6, and the
  `app/media/FormatSniffer.kt` skeleton accordingly.
- 2026-09-14 — Made the R8 sniff **timeout a user preference** (not a hard-coded constant): added
  `MediaPreference` (DataStore `studio_prefs`, key `studio_sniff_timeout_ms`) with `DEFAULT_SNIFF_TIMEOUT_MS = 5_000`
  (5 s) and a `Flow` + setter for a future settings UI. `FormatSniffer.sniff(header, timeoutMillis = DEFAULT_SNIFF_TIMEOUT_MS)`
  now defaults to that preference; the settings screen to override it is **not yet wired** (Q6). Replaced the
  previous `var timeoutMillis = 2_000L`. Updated the R8 flow step and Q6 accordingly.
- 2026-09-14 — **Implemented the R8 studio render pipeline** (Q6 resolved): added the pure `route()` function
  + `Route` sealed interface (`RawToRaster` / `ToCoil` / `Unsupported`) in `app/media/FormatSniffer.kt`. Wired
  `StudioEngine` (now `prepare(context)`-initialized) to run every opened node through `FormatSniffer.sniff`
  → `route` → `renderResult` (`Idle`/`Loading`/`Ready(model)`/`Unsupported`); the rawler path calls the new
  `RawDecoder.decodeToPng` seam (rendered as a PNG `ByteBuffer` by Coil), the Coil path renders the original
  `Uri`, and `Unsupported` shows the "Unsupported Format! 不支持的格式！" dialog in `StudioScreen`. Added
  `RawDecoder`/`StubRawDecoder` (native bridge TODO) and the three strings. Documented the three-way route
  table and closed Q6 (single-side-timeout behaviour left noted as open).
- 2026-09-14 — **Clarified the raw path is two separate native calls, not one.** Call #1 = identification
  only (`RawlerProbe.sniff`, run inside `FormatSniffer.sniff`) → `Verdict{format, canDecode}`; Call #2 =
  decode only (`RawDecoder.decodeToPng(format, source)`), made solely after routing to `RawToRaster` and
  handed the `format` from call #1 so the native side decodes the already-identified RAW instead of
  re-identifying. `decodeToPng` now takes `format: String`; `RawlerProbe`/`RawDecoder` docs and the Routing
  table both state the two-call split.
- 2026-09-14 — **Wired the rawler native binding (rawler_fotlab, UniFFI).** Added `external/dnglab/rawler_fotlab/`
  (cdylib `rawler_fotlab`, dnglab workspace member) exposing `identify` (call #1) + `decode_to_png` (call #2)
  over the upstream `rawler` rlib; `RawlerFotlabBridge.kt` + generated `rawler_fotlab.kt` in
  `app/src/kotlin/binding/dnglab/rawler_fotlab/`; `RawlerFotlabDecoder` implements `RawDecoder` and
  `StudioEngine.prepare()` wires it (graceful null fallback when the .so is absent). CI: new `build_rust.yaml`
  builds the .so + Kotlin bindings and uploads the `rawler_fotlab` artifact; `build_gradle.yaml` downloads it
  into `jniLibs`/binding dir; `build.yaml` orders `rust` before `apk`. Naming rule: only our binding uses
  `rawler_fotlab`; external `rawler` keeps its original name.
- 2026-09-14 — **Moved the binding crate out of the submodule and stopped committing generated code.**
  The first-party crate now lives at `app/src/rust/binding/dnglab/rawler_fotlab/` and is a standalone
  workspace with a *path* dependency on `external/dnglab/rawler`; the upstream `external/dnglab/Cargo.toml`
  member list is restored so upstream stays read-only (the previous layout added our crate as a dnglab
  workspace member and modified upstream — `FOTLAB-NATIVE-000001` R1/R4/C2/C4). `uniffi.toml` now uses the
  correct Kotlin key `package_name` (the earlier `namespace` was ignored). The generated bindings are
  written to `app/build/generated/uniffi/main/kotlin` instead of `src/`, so the dirty `.gitignore`
  UniFFI entry is gone and the facade `RawlerFotlabBridge.kt` (renamed calls `identifyFormat` /
  `decodeRawToPng` to avoid shadowing the generated top-level functions) is committed. Added the missing
  Android runtime dependency JNA (`@aar`) and made the native build use `cargo ndk -o` plus the crate's own
  `uniffi-bindgen` bin for version parity. Also fixed the `InputStream.readNBytes` (API 33+) call that broke
  the sniff header read on `minSdk 26`.
- 2026-09-14 — **Sniff input widened to 1 MiB and the sniff timeout made authoritative.** `StudioEngine`
  now feeds `FormatSniffer` the first 1 MiB instead of 64 KiB, so TIFF/BMFF-based RAW containers whose
  `Make`/preview IFD entries sit past the first block are still identified by content. Because neither
  sniffer can be cancelled once started (`BitmapFactory` and the native rawler call ignore
  `Thread.interrupt()`), `FormatSniffer.sniff` now runs each sniffer on a dedicated **daemon** worker thread
  (`SniffThreads`) and, on deadline, interrupts and **abandons** those threads instead of waiting — the
  caller is always released at the timeout and a late result is dropped (`tryResume`/`completeResume`).
  Closed the previously open single-side-timeout question: a wedged sniffer fails the whole sniff to
  `SniffResult.Timeout`, but never blocks the caller.
