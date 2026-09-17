# RawImage already carries the resolved camera calibration (color_matrix / cfa / wb) — the data↔camera match is done inside rawler for every format, including CR2/etc. via the camera DB lookup

- ID: FOTLAB-RAWLER-000002
- Status: Observation
- Priority: P2
- Created: 2026-09-17
- Owner: —
- Related: `rules/REVIEW/detail/FOTLAB-RAWLER-000001.md` (RawImage never crosses the FFI; preview is an unprocessed dump), `rules/REVIEW/detail/DNGLAB-RAWLER-000002.md` (rewrite assessment), `rules/DESIGN/detail/DNGLAB-RAWDEV-000001.md` (develop pipeline), `rules/DESIGN/detail/FOTLAB-NATIVE-000001.md` (R4 — upstream read-only)

## Background & Goal

`FOTLAB-RAWLER-000001` established that the `rawler_fotlab` binding obtains a `rawler::RawImage` (via `rawler::decode`, `app/src/binding/rust/rawler_fotlab/src/lib.rs:94`) but never surfaces it, and that `encode_png` produces an unprocessed, bit-shifted PNG. This item goes one level deeper and answers three linked questions:

1. What does `RawImage` actually hold beyond pixel data?
2. Does the presence of `color_matrix` on a `RawImage` prove that the **data ↔ camera match** has already been resolved?
3. For formats other than DNG (e.g. CR2) the `color_matrix` is **not** embedded in the file — it must be looked up from the camera database. **Did our glue library perform that lookup, or did it stop at "just reading the DNG/RAW"?**

Goal: record on file whether the camera-matching step is already complete inside `RawImage`, so future "true developed image" work does not mistakenly assume it must be re-implemented in first-party code.

## Finding

### 1. `RawImage` is a "pixels + full calibration/metadata" object, not a pixel buffer

The struct (`external/dnglab/rawler/src/rawimage.rs:202-252`) carries a large amount of non-pixel state. Grouped by purpose:

- **Camera identity** — `camera: Camera`, `make`, `model`, `clean_make`, `clean_model` (`rawimage.rs:204-212`).
- **Geometry / crop** — `width`, `height`, `cpp`, `bps`, `active_area`, `crop_area`, `blackareas`, `orientation`, `fuji_rotation_width` (`rawimage.rs:214-251`).
- **Color / calibration** — `wb_coeffs` (`rawimage.rs:222`), `whitelevel` (`rawimage.rs:224`), `blacklevel` (`rawimage.rs:226`), `xyz_to_cam` (deprecated, `rawimage.rs:228`), `photometric: RawPhotometricInterpretation` (holds `cfa` + `colors` via `CFAConfig`, `rawimage.rs:230`), and `color_matrix: HashMap<Illuminant, FlatColorMatrix>` (`rawimage.rs:245`).
- **Raw DNG tags** — `dng_tags: HashMap<u16, Value>` (`rawimage.rs:247`).
- **The only actual pixel field** — `data: RawImageData` (enum `Integer(Vec<u16>)` / `Float(Vec<f32>)`, `rawimage.rs:243`, `256-261`).

So `data` is just one of ~22 fields. Everything downstream of decode (demosaic, calibrate) reads from the calibration/geometry fields, not just `data`.

### 2. `color_matrix` being populated proves the data↔camera match is already resolved

`color_matrix` is copied verbatim from the resolved `Camera` at construction time:

```rust
// rawimage.rs:400 (RawImage::new)    and  rawimage.rs:490 (RawImage::new_with_data)
color_matrix: cam.color_matrix,
```

Therefore: **if a `RawImage` has `color_matrix` set, the `Camera` definition (`cam`) has already been built and paired with `data` into the same object.** The match is complete at the moment the `RawImage` exists. There are two source paths for `cam.color_matrix`, and they differ in whether an external lookup is needed:

- **DNG — self-described, no external lookup.** `make_camera()` builds the `Camera` from tags *inside the DNG file itself*: `get_color_matrix()` reads `ColorMatrix1/2` + `CalibrationIlluminant` (`decoders/dng.rs:263`), `get_cfa()` reads `CFAPattern` (`decoders/dng.rs:349`), assembled into `Camera { .., color_matrix, cfa, .. }` (`decoders/dng.rs:271-288`). The optional catalog enrichment at `decoders/dng.rs:45-57` (`check_supported_with_mode`) copies **only** `clean_make/clean_model/hints/params` — it does **not** touch `color_matrix` (that already came from the file).
- **Other RAW (CR2 / NEF / ARW / RW2 / PEF / ORF / …) — from rawler's bundled camera DB.** Each decoder stores a `camera: Camera` field (e.g. `Cr2Decoder { camera, .. }`, `decoders/cr2.rs:67`). That field is populated at decoder construction by looking the camera up in the database: `rawloader.check_supported_with_mode(...)` → `check_supported_with_everything(make, model, mode)` → `self.cameras.get(&(make, model, mode))` (`decoders/mod.rs:1064-1074`). The returned `Camera` (with `color_matrix` parsed from the `data/cameras/*.toml` DB) is what gets paired into the `RawImage`.

### 3. Glue code: `decode_to_png` runs the FULL upstream decode, so it DID perform the camera-DB lookup (CR2 etc.)

`decode_to_png` (`app/src/binding/rust/rawler_fotlab/src/lib.rs:88-103`) calls `rawler::decode(&src, …)` (`lib.rs:94`). That call walks the entire upstream decode path:

```
rawler::decode
  └─ RawLoader::get_decoder            (decoders/mod.rs:909)
       └─ for Canon → Cr2Decoder::new  (decoders/mod.rs:996)
            └─ rawloader.check_supported_with_mode(...)   (decoders/cr2.rs:377)
                 └─ self.cameras.get((make, model, mode))  (decoders/mod.rs:1064-1074)  ← camera-DB lookup
            └─ stores the Camera into self.camera          (decoders/cr2.rs:393)
  └─ decoder.raw_image(...)
       └─ RawImage::new(camera.clone(), image, …)          (decoders/cr2.rs:266)
            └─ color_matrix: cam.color_matrix              (rawimage.rs:400)
```

**Answer to the research question:** for CR2 and all non-DNG formats, the `color_matrix` lookup **is** performed — by rawler, invoked through our glue code. Our glue code does **not** do a *separate* lookup of its own; it reuses `rawler::decode`, which does the lookup internally as part of constructing the decoder. The net result is identical: the `RawImage` produced by `decode_to_png` already contains the correctly-looked-up `color_matrix` for **every** format rawler supports, DNG or not.

The database itself is bundled inside rawler (`data/cameras/*.toml` → `RawLoader.cameras`, loaded at `RawLoader` construction), so it is present wherever rawler is linked — including our shipped binding.

### 4. Where the real gap is: `encode_png` discards the resolved calibration

The `RawImage` handed to `encode_png` is **fully resolved** — `color_matrix`, `cfa`/`colors`, `wb_coeffs`, `blacklevel`, `whitelevel` are all present and correct (looked up for CR2, embedded for DNG). The gap is downstream and purely in `encode_png` (`lib.rs:111-143`): it reads only `img.data` + `img.width/height/cpp` and bit-shifts the linear samples to 8-bit RGBA PNG (no demosaic / white-balance / gamma). It does **not** consume `color_matrix`, `cfa`, or `wb_coeffs` at all.

So: the **data ↔ camera match is done**; it is simply **not consumed**. The missing step is the **develop pass** (`develop_intermediate`, `rawler/src/imgop/develop.rs`), which is what applies `color_matrix` (calibrate) and `cfa`/`colors` (demosaic). Not the lookup.

## Impact / Conflict

- Confirms `FOTLAB-RAWLER-000001`'s "preview-only PNG" conclusion: the cause is `encode_png` dropping the calibration, **not** a missing camera lookup. The match is already complete inside `RawImage`.
- Means the R4 "true developed image" work does **not** need to add a camera-DB lookup in first-party code — rawler already supplies `color_matrix` / `cfa` / `wb_coeffs` inside the `RawImage`. The binding only needs to either (a) call rawler's develop pass on the `RawImage` at `lib.rs:94` before `encode_png`, or (b) expose the `RawImage` (requires a new `#[uniffi::export]` wrapper) so first-party code can develop it.
- No change to `external/` is required; this is consistent with `FOTLAB-NATIVE-000001` R4 (upstream read-only). The finding also strengthens the "deepen, don't rewrite" conclusion in `DNGLAB-RAWLER-000002` — the calibration data the develop pipeline needs is already flowing out of `rawler::decode`.

## Recommendation

1. **Do not re-implement a camera lookup in first-party code.** `RawImage` already carries the resolved `color_matrix`/`cfa`/`wb_coeffs` for all formats; for CR2 etc. rawler's decoder performs the `self.cameras.get(...)` lookup on our behalf.
2. To obtain a developed image, drive rawler's develop pass (`RawDevelop::default().develop_intermediate(&img)` or `rawler::imgop::develop`) on the `RawImage` obtained at `lib.rs:94` before `encode_png` — or add an exported function that surfaces the pixel buffer + dimensions + `cpp` + metadata so the studio side can develop it.
3. If the preview-only path is kept, document explicitly that `color_matrix` (and the rest of the calibration) is **available but intentionally unused** — it is correct, just dropped by `encode_png`.

## Change History

- 2026-09-17 — Created. Recorded that `RawImage` holds far more than pixels (field taxonomy at `rawimage.rs:202-252`); that `color_matrix` is copied from `cam.color_matrix` (`rawimage.rs:400/490`) so its presence proves the data↔camera match is resolved; that DNG is self-described (`decoders/dng.rs:263,271-288`) while CR2/etc. are resolved via the camera DB (`decoders/cr2.rs:377` → `decoders/mod.rs:1064-1074`); and that our glue code's `decode_to_png` (`lib.rs:88-103`) calls `rawler::decode` (`lib.rs:94`) which triggers that lookup internally — so the glue `RawImage` **does** contain the looked-up `color_matrix` for every format. The only gap is `encode_png` discarding the calibration (`lib.rs:111-143`). Row to be appended to `rules/REVIEW/index.md`.
