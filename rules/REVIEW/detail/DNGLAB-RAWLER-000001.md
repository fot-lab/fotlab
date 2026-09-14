# rawler_fotlab PNG preview is an unprocessed full-sensor dump — no black level, white balance, demosaic, colour mapping or gamma

- ID: DNGLAB-RAWLER-000001
- Status: Observation
- Priority: P2
- Created: 2026-09-14
- Owner: —
- Related: `rules/STRUCT/detail/FOTLAB-STUDIO-000001.md` (native media pipeline; develop pipeline deferred), `rules/DESIGN/detail/FOTLAB-NATIVE-000001.md` (first-party binding over read-only upstream rawler), `app/src/rust/binding/dnglab/rawler_fotlab/src/lib.rs`, `app/src/main/kotlin/io/github/fotlab/fotlab/media/FormatSniffer.kt`, `app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioEngine.kt`

## Background & Goal

When `FormatSniffer` classifies input as a RAW rawler supports, the Studio route resolves to `Route.RawToRaster`: call #2 (`RawlerFotlabBridge.decodeRawToPng` -> UniFFI -> `rawler_fotlab::decode_to_png`) decodes the file and hands PNG bytes to Coil for display (`app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioEngine.kt:110-118`).

This review records exactly what image processing that PNG has — and has not — received, so no one mistakes the current preview for a developed photograph. The review is factual (verified against the pinned `external/dnglab` submodule source); no code is changed.

## Finding

### 1. The PNG is full sensor resolution — no crop and no downscale

`encode_png` allocates one RGBA output pixel per decoded sensor pixel using `RawImage.width` / `RawImage.height` and writes a single PNG (`app/src/rust/binding/dnglab/rawler_fotlab/src/lib.rs:69-101`). Nothing reads `RawImage.active_area` or `RawImage.crop_area`, so the optical-black masking border is included. The Kotlin side forwards the complete PNG byte array unchanged (`RawlerFotlabDecoder.kt:16-19`); only Coil's display pass downsamples for the view. The PNG artifact itself is full-size.

### 2. rawler is a decoder, not a developer — processing coefficients are delivered as metadata only

`rawler::decode` reaches the decoder-specific `raw_image()` and returns a `RawImage` whose pixel buffer holds the sensor's raw linear samples, while every developing parameter is carried in separate fields (`external/dnglab/rawler/src/rawimage.rs:202-247`):

- `wb_coeffs: [f32; 4]` — as-shot white balance coefficients (RGBE order);
- `blacklevel` / `whitelevel` — per-channel levels;
- `xyz_to_cam` and `color_matrix` — camera-RGB <-> XYZ matrices;
- `photometric: Cfa(...)` — the Bayer CFA pattern, i.e. the data is mosaiced.

The decoder constructors (`ok_cfa_image*` in `external/dnglab/rawler/src/decoders/mod.rs:461-489`) attach those fields to the struct; they never subtract black, scale by white balance, or demosaic.

### 3. Our binding reads none of those fields — the only per-sample operation is a bit shift

`encode_png` iterates `RawImageData` directly (`app/src/rust/binding/dnglab/rawler_fotlab/src/lib.rs:77-94`):

- 16-bit integer data: each channel is `v >> 8` saturated to 255 (`shrink_u16`);
- float data: `clamp(0,1) * 255` (`shrink_f32`);
- for Bayer data (`cpp == 1`) `r = g = b`, so every sensor site becomes one **grayscale** pixel; `cpp >= 3` emits the first three components as RGB;
- alpha is fixed at 255.

Consequently the PNG contains **no** black-level subtraction, **no** white-balance application, **no** demosaic, **no** camera->XYZ->sRGB colour transform, and **no** gamma / tone curve. Linear sensor data is encoded as 8-bit sRGB-range values. The source comments state this explicitly ("without a demosaic/white-balance/gamma pass … the precise develop pipeline is future work", `lib.rs:63-68`).

### 4. dnglab's develop pipeline is not even in the dependency graph

Our crate path-depends on `external/dnglab/rawler` only (`Cargo.toml:32-38`: `rawler`, `image` with the `png` feature, `thiserror`, `uniffi`). The dnglab workspace members are `bin/dnglab`, `rawler`, `embedftp` (`external/dnglab/Cargo.toml:1-6`); white balance, demosaicing and colour management live in the dnglab application/develop side, which is neither linked nor reimplemented. Upstream stays read-only per `FOTLAB-NATIVE-000001` R4 — the develop work has to be our own first-party code.

## Impact / Conflict

- For the common case (Bayer RAW, `cpp == 1`) the Studio preview is a **full-size, dark, low-contrast grayscale image**, optionally ringed by an uncropped black border — not a colour photograph.
- Unsubtracted black levels plus encoding linear data without gamma compress the visible range into the dark portion of the 8-bit output; the `>> 8` reduction also discards low-order precision from 12/14-bit captures.
- Functional correctness is not at stake: sniff/route/decode still return a renderable image, and failure modes degrade to `Unsupported` as designed. The issue is image fidelity and the risk that the preview is misread as "developed".
- No conflict with existing rules; this is the deferred work `FOTLAB-STUDIO-000001` already labels future work. This item pins the precise current baseline.

## Recommendation

- Treat the current PNG strictly as a raw-sensor preview; do not present it as the developed result in UI copy.
- When the develop pipeline is implemented, its minimum order is: black-level subtraction -> white balance (`wb_coeffs`) -> demosaic (driven by the CFA pattern) -> camera->XYZ->sRGB (`color_matrix`/`xyz_to_cam`) -> gamma/tone mapping -> 8-bit quantisation, with `active_area`/`crop_area` applied before output. All inputs already exist on rawler's `RawImage`.
- If a cheap interim improvement is wanted before the full pipeline, cropping `active_area` and subtracting black levels are the two lowest-cost, highest-visibility steps; demosaic + colour transform remain the substantive work.

## Change History

- 2026-09-14 — Review recorded. Verified that `rawler_fotlab::decode_to_png` emits a full-sensor-resolution PNG built by a raw 16->8 bit shift with identical RGB channels for Bayer data: no black-level subtraction, white balance, demosaic, camera->sRGB colour mapping or gamma is applied, and `active_area`/`crop_area` are ignored; rawler supplies all coefficients as `RawImage` metadata only, and the dnglab develop pipeline is outside our dependency graph. Filed as `DNGLAB-RAWLER-000001`; row appended to `rules/REVIEW/index.md`.
