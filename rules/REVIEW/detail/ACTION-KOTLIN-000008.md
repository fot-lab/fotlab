# Android Bitmap.compress exposes no JPEG chroma-subsampling control — 4:4:4 falls back to platform default

- ID: ACTION-KOTLIN-000008
- Status: Implemented
- Priority: P2
- Created: 2026-10-06
- Owner: —
- Related: ACTION-KOTLIN-000007 (platform API & data-layer audit), FOTLAB-UIXDES-000005 (export icon rule)

## Background & Goal

The studio fun bar's share action (`Icons.Filled.IosShare`) originally exported only a
compressed PNG. The requirement was to extend it with a PNG / JPEG drop-up menu where the
JPEG branch is specified as "4:4:4 at 95%".

Before authoring the JPEG branch we confirmed the existing PNG path already used the native
encoder — `bmp.compress(Bitmap.CompressFormat.PNG, 100, out)` (see `encodeExport` in
`app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioScreen.kt`). So the JPEG
branch reuses the same `Bitmap.compress` call rather than introducing a second pipeline; the
two formats are data-driven entries of one `StudioExportFormat` enum.

## Finding

The capability boundary, verified against **official Android documentation only** (not AOSP /
Skia source, per the user's instruction to avoid source-level proof):

- `Bitmap.CompressFormat` (`developer.android.com/reference/android/graphics/Bitmap.CompressFormat`):
  the JPEG entry defines only `quality` (0 = smallest file, 100 = best visual quality). The
  reference explicitly states PNG is lossless so `quality` is ignored, and `WEBP_LOSSLESS` uses
  `quality` as compression effort. **No chroma-subsampling parameter exists.**
- NDK `AndroidBitmap_compress` (`developer.android.com/ndk/reference/group/bitmap`) likewise
  exposes only `quality`.
- libjpeg (the underlying library) supports 4:4:4 / 4:2:2 / 4:2:0 sampling, but the Android
  framework does **not** surface a sampling-factor (`h_samp_factor` / `v_samp_factor`) knob
  through the public Java or NDK API.
- Consequence: JPEG chroma subsampling is entirely the platform encoder's default choice.
  Historically Android's Skia/JPEG path writes 4:2:0; there is **no public way to request
  4:4:4**. The requested "4:4:4 @ 95%" cannot be delivered through `Bitmap.compress`.

This is the "library supports ≠ API controllable" trap: many blog posts confirm libjpeg can
do 4:4:4 and are then misread as "`Bitmap.compress` can set it". A judgement of the capability
boundary rests only on the official API reference parameter list.

## Impact / Conflict

- The shipped JPEG branch is `compress(Bitmap.CompressFormat.JPEG, 95)` — quality 95 as
  requested, but 4:4:4 is **not** guaranteed; the actual sampling is the platform's.
- No conflict with an existing rule. Recorded here so a future reader does not (a) "fix" the
  missing 4:4:4 by inventing an undocumented assumption, or (b) silently add a native
  dependency to force it.
- Honest documentation: the KDoc on `StudioExportFormat` and the commit message state the
  subsampling is the platform's call, not ours. No code pretends to enforce 4:4:4.

## Recommendation

1. Keep the boundary honest: document in code / commit that `compress()` cannot request
   4:4:4; never fake it.
2. If true 4:4:4 JPEG ever becomes a hard requirement, that is an **architecture-level
   decision** — it needs a third-party native encoder (e.g. TurboJPEG / libjpeg-turbo via JNI)
   or a switch to WebP lossless. Both add a native dependency and must get explicit human
   sign-off (a new dependency is a first-party architecture change under the rollback guard).
3. Keep the two export branches data-driven through `StudioExportFormat`
   (`labelRes` / `mimeType` / `extension` / `compressFormat` / `quality`) so the only
   platform-controlled knob (subsampling) has a single, clear owner, and any future API that
   exposes it becomes a one-line change.

## Change History

- 2026-10-06 — Created as a finding from the studio share-menu JPEG work (feature commit
  `e2ba7e5`, follow-up null-safety fix `93a58d2`). Finding: Android `Bitmap.compress` and NDK
  `AndroidBitmap_compress` expose only `quality`; there is no chroma-subsampling control, so
  4:4:4 is the platform's choice, not ours. Decision recorded: fall back to the platform
  default per the user's instruction; do not add a native encoder without explicit sign-off.
  Status set to Implemented (code shipped).
