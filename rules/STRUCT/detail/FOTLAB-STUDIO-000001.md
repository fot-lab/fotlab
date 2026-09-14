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
- **Single integration point** — both RAW→raster paths live in the native-integration module
  (`FOTLAB-NATIVE-000001`), not in `external/` (per `STRUCT.md` principle 5) and not in the UI.

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

## Impacted Modules

- `app` / studio feature package (render layer — Coil + viewer class, R1/R5).
- `app` / library feature package (grid/thumbnail viewer — Coil + viewer class; consumes embedded-preview
  rasters, R6).
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
