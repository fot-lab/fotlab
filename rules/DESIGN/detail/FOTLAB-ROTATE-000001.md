# Crop & Rotate (Kotlin-side, on the rendered PNG)

- ID: FOTLAB-ROTATE-000001
- Status: Draft
- Priority: P2
- Created: 2026-10-14
- Owner: —
- Related: `FOTLAB-PIPELN-000001` (loader/develop/process split), `FOTLAB-STUDIO-000001` (Studio engine / canvas), `FOTLAB-UIXDES-000001` (Compose UI shell)

> **Draft** — awaiting user approval. All decisions below are proposals, not committed behaviour.

## Background & Goal

The Studio screen (`FOTLAB-STUDIO-000001`) renders a RAW/source file into an **sRGB presentation PNG** on the Kotlin side (see `StudioEngine`: `RawDecoder.developToPng` for rawler paths, Coil for generic sources). Today that PNG is shown on the canvas and is the final thing the user sees.

We want a **crop & rotate** capability that is applied **after** the PNG is produced — entirely on the Kotlin side, on the rendered raster — and then presented back to the user (and later reused for export). It must **not** feed back into the native develop pipeline (no re-decoding the RAW), so it stays a cheap, interactive, display/export-stage geometric transform.

Goals:

- G1 — Let the user crop (free-rect, aspect-ratio presets, center/shape masks optional later) and rotate/flip the rendered PNG interactively in the Studio canvas.
- G2 — Keep crop & rotate **non-destructive** as a parameter model, so the original PNG pixels are preserved and the transform can be re-applied (or undone) without re-developing.
- G3 — Implement the transform with **first-party Android/AndroidX primitives** + Compose UI, mirroring the shell rule `FOTLAB-UIXDES-000001` (first-party-only UI stack). Evaluate a third-party crop view only as a fallback.
- G4 — Stay memory-safe on large PNGs (>= 50 MP) by operating on a downscaled preview for interaction and on full resolution only at the final apply/export.

## Research

### Open-source implementations surveyed

**T8RIN / Image Toolbox** (`ru.tech.imageresizershrinker`, F-Droid + Play) — the most relevant "image toolbox" reference.
- Crop features: regular crop, **free-rotation crop**, **free-corners crop** (perspective correction), crop-by-aspect-ratio (custom ratios saved), **shape-mask crop** (oval/squircle/star/heart/…), center crop, image cutting (batch).
- Rotate/flip features: standalone rotate + flip, mirror, "Flip And Rotate" in filters, PDF rotate, Auto-Perspective / Auto-Crop / Crop-To-Content filters.
- Tech stack: the crop/rotate geometry is **custom-implemented** (Kotlin + Jetpack Compose + a first-party "Image Toolbox Libs" module, plus native C++ helpers `Aire`/`Trickle` for filters). It does **not** depend on a dedicated third-party crop library (no uCrop / Android-Image-Cropper in its stack). This is the key precedent: a serious image editor ships crop/rotate as **its own Compose-based code**, not a vendored crop widget.
- UX lesson to copy: free-rotation crop (rotate and crop in one gesture), aspect-ratio presets, and a live preview are the expected baseline for a photo editor.

**CanHub / Android-Image-Cropper** (`com.canhub.cropper`, the maintained fork of ArthurHub's `Android-Image-Cropper`) — the de-facto third-party crop widget.
- Provides `CropImageView` (View-based, *not* Compose-native) with pinch-zoom, drag, aspect-ratio lock, `setRotatedDegrees(...)`, and `getCroppedImage()`.
- Strengths: mature, handles gestures, EXIF/orientation, memory damping (`setRequestedSize`, sampling).
- Weaknesses for us: (a) it is a `View`, so embedding it inside the existing Compose Studio canvas means an `AndroidView` bridge or a separate activity/fragment; (b) it **bakes** a new Bitmap on crop — we would have to keep the crop rect ourselves to stay non-destructive; (c) it draws its own overlay chrome, conflicting with our own canvas/studio chrome.

**Yalantis / uCrop** — gesture crop with rotation + zoom. Same View-based, baked-output caveats as above; less actively maintained than CanHub.

**Net**: the two viable integration shapes are (A) vendored `CropImageView` for the interaction, or (B) a **custom Compose crop/rotate** following the Image Toolbox precedent. We recommend (B) — see Requirement R2.

### Google / official building blocks

There is **no official Google "crop & rotate" library**. The real first-party primitives are:

- `android.graphics.Matrix` — `postRotate(deg)`, `postScale(-1,1)`/`postScale(1,-1)` for flip, `postTranslate` for crop offset; compose with `Matrix.concat`.
- `Bitmap.createBitmap(src, x, y, w, h)` — rectangular crop (axis-aligned sub-rect).
- `Bitmap.createBitmap(src, 0, 0, w, h, matrix, filter)` — apply an arbitrary `Matrix` (rotate/flip) with bilinear resampling when `filter = true`. This is the single call that does rotate/flip; for **free (arbitrary-angle) rotation** the caller must (1) rotate around the image centre, and (2) recompute the axis-aligned **bounding box** of the rotated rect so no corners are clipped — see R3.
- `androidx.compose.ui.graphics.Matrix` + `Canvas` (Compose) — draw the transformed bitmap and the crop overlay inside our existing Compose canvas.
- `androidx.exifinterface.media.ExifInterface` — read `TAG_ORIENTATION` if/when crop/rotate is later applied to source files that carry EXIF (the rendered PNG is already orientation-normalized, so this is mainly for export round-tripping).
- `com.android.camera.action.CROP` (the legacy "system cropper" intent) is **deprecated and vendor-inconsistent** — explicitly out of scope.

**Conclusion**: "Google's official crop/rotate" = the Android `graphics` platform primitives + Compose `Canvas`, not a dependency. This matches `FOTLAB-UIXDES-000001` (first-party-only stack).

## Requirement

### R1 — Placement: a post-render, Kotlin-side transform

- Crop & rotate operates on the **rendered sRGB presentation PNG** that `StudioEngine` already holds (`StudioRenderResult.Ready`), never on the RAW/source.
- It is a **terminal stage** layered after the develop/process split (`FOTLAB-PIPELN-000001`). It does not re-enter `RawDecoder`/`developToPng`.
- Output is a new `Bitmap` for display and (later) export; the source PNG bytes are retained unchanged.

### R2 — Non-destructive parameter model

- Crop/rotate state is captured as a single value object, e.g. `CropRotateParams(rotationDegrees: Float, flipHorizontal: Boolean, flipVertical: Boolean, cropRect: RectF, aspectRatioLocked: Boolean?)`.
- The Studio engine keeps the **original** `Bitmap` plus the current `CropRotateParams`; the transformed bitmap is produced by a pure function `applyCropRotate(src: Bitmap, params: CropRotateParams): Bitmap`.
- Undo/reset = restore `CropRotateParams` to identity; re-apply without re-developing. Parameters are the unit of persistence for the future export path.

### R3 — Rotate & flip (the geometry)

- Rotate by **90° multiples**: swap width/height and emit the rotated sub-bitmap (no resampling needed; use `Matrix.postRotate` + `createBitmap` with `filter = false`).
- **Free rotation** (arbitrary angle, e.g. horizon correction): build `Matrix` = translate-to-centre ∘ `postRotate(deg)` ∘ translate-back; compute the rotated bounding box
  `W' = |w·cosθ| + |h·sinθ|`, `H' = |w·sinθ| + |h·cosθ|`; allocate a `W'×H'` target and draw with `filter = true` (bilinear). Bilinear is the platform default; a Lanczos-ish filter in linear light is a **future** optimization (see Open Questions Q4).
- Flip: `postScale(-1, 1)` / `postScale(1, -1)` about the centre, combined into the same `Matrix`.

### R4 — Crop

- Axis-aligned rect crop via `Bitmap.createBitmap(src, rect.left, rect.top, rect.width, rect.height)`.
- **Aspect-ratio presets** (1:1, 4:3, 3:4, 16:9, 3:2, free) constrain the drag handles; custom ratios saved as user preference (mirrors Image Toolbox).
- Crop rect is expressed in **source bitmap coordinates** (before rotate), and is reconciled with the rotation so the final rect maps correctly after R3.

### R5 — Interactive UI (Compose, first-party)

- A crop/rotate overlay drawn in the existing Studio Compose canvas: draggable corner/edge handles, a rotate slider (±45° free) plus 90°/−90° snap buttons, flip H/V buttons, and aspect-ratio chips.
- Interaction runs on a **downscaled preview** bitmap; the full-resolution `applyCropRotate` runs only on commit (R6).
- Reuse `androidx.compose.foundation.gestures` for drag/pinch; no third-party gesture lib.

### R6 — Memory & performance

- Never hold two full-resolution `Bitmap`s simultaneously during interaction: the preview is a sampled `Bitmap` (`BitmapFactory.Options.inSampleSize` or Coil `ImageRequest` size); the full-res transform allocates the result, then releases the source reference it no longer needs.
- Large-PNG guardrails (>= 50 MP) follow the performance rules in `FOTLAB-PIPELN-000001` / the PERFOR items; the interactive preview cap is a tunable (default e.g. longest side ≤ 2048 px).

### R7 — Dependencies (first-party default)

- Default implementation uses **only** `android.graphics`, `androidx.compose.*`, `androidx.exifinterface` — no new third-party dependency.
- A third-party `CropImageView` (CanHub) is a **fallback only**, gated behind a decision in R2; if adopted it must be wrapped so the crop rect + rotation stay in `CropRotateParams` (non-destructive) and rendered through our own Compose chrome.

## Constraints

- C1 — Operates on the gamma-encoded sRGB presentation PNG, not linear/ProPhoto data; resampling is therefore bilinear-in-sRGB (acceptable for a presentation/export transform; see Q4).
- C2 — Must not trigger RAW re-decode; crop/rotate cost is bounded by PNG pixel count, not RAW complexity.
- C3 — First-party UI stack only (`FOTLAB-UIXDES-000001`); a third-party crop widget needs explicit sign-off (R7).
- C4 — Output dimensions must be explicitly derived from `W'×H'` after rotate+crop; no silent aspect distortion.
- C5 — Interactive path is preview-resolution only; full-res apply is explicit (commit/export).

## Acceptance Criteria

- AC1 — Opening crop/rotate on a rendered PNG shows the image with draggable crop handles and rotate/flip controls; no re-develop (RAW decode) occurs while interacting.
- AC2 — Applying 90°/−90°/180° rotate and H/V flip produces a correctly oriented bitmap with dimensions swapped where expected; pixels are not blurred (filter = false for 90° multiples).
- AC3 — Free rotation up to ±45° produces an axis-aligned bitmap with **no clipped corners** (full rotated bounding box) and bilinear smoothing.
- AC4 — Setting an aspect-ratio preset constrains the crop rect to that ratio; a custom ratio can be saved and reused.
- AC5 — Reset restores the original framing exactly (identity `CropRotateParams`); the original PNG bytes are unchanged.
- AC6 — On a ≥ 50 MP PNG, interaction stays responsive (preview-resolution path) and full-res apply completes without OOM under the PERFOR guardrails.
- AC7 — No new third-party dependency is introduced unless R7 fallback is explicitly approved.

## Impacted Modules

- `feature/studio/StudioEngine.kt` — holds source PNG + `CropRotateParams`; exposes `applyCropRotate` and a `cropRotateParamsState` flow.
- Studio Compose canvas / fun-bar — crop/rotate entry, overlay, controls (new UI module or extension of the current studio screen).
- (Future) Export path — consumes `CropRotateParams` to produce the final file.
- `FOTLAB-PIPELN-000001` — documents where this terminal stage sits relative to loader/develop/process.

## Open Questions

- Q1 — Scope of v1: free-rect + 90° rotate + flip + aspect presets, or also shape-mask / perspective (free-corners) crop? Recommend v1 = rect + rotate + flip + aspect; perspective deferred.
- Q2 — Should crop/rotate params persist per-node (Room) or stay session-only until export? Tie to `FOTLAB-DATABS-*` once export lands.
- Q3 — Is crop/rotate a Studio-only edit, or also available on Library thumbnails/preview? Recommend Studio-only for v1.
- Q4 — Resampling quality: bilinear-in-sRGB (platform default, ships now) vs Lanczos-in-linear (better for large free rotations, needs a custom resampler). Defer Lanczos.
- Q5 — If a third-party `CropImageView` is chosen for speed, how do we keep it non-destructive and visually consistent with our Compose chrome?

## Change History

- 2026-10-14 — Created as Draft (`FOTLAB-ROTATE-000001`). Captures background, open-source + Google-primitive research (focus on T8RIN/Image Toolbox), non-destructive Kotlin-side design, and acceptance criteria. Awaiting user approval.
