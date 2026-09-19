# External module study — where the develop pipeline converts 14/16-bit integer to f32 (rawler `apply_scaling`), and why minus-EV does not lose shadow detail

- ID: RAWTRP-DECODE-000004
- Status: Draft
- Priority: P2
- Created: 2026-09-19
- Owner: —
- Related: `rules/STRUCT/detail/RAWTRP-DECODE-000003.md` (the demosaic kernel I/O contract — this doc explains where the 0..1 f32 `array2D<float>` that 000003's bridge feeds actually comes from: it is produced by `apply_scaling`), `rules/STRUCT/detail/RAWTRP-PIPELN-000001.md` (develop pipeline — `apply_scaling` is rawler's `Rescale` step, the first stage that touches pixel magnitude), `rules/STRUCT/detail/DNGLAB-RAWLER-000005.md` (dual-fork pipeline — the exposure 2 EV lives in our binding's `develop_image`, after scaling). Also context: the exposure-precision question (does the 2-EV multiplier run on u16 or f32?) is answered here — it runs on the f32 produced by the conversion below.

> **Note on naming**: `RAWTRP-` project code (RawTherapee) + six-character `DECODE` category, same as `RAWTRP-DECODE-000001/000002/000003`. Lives under `rules/STRUCT/detail/` because `STRUCT.md` principle 5 treats `external/` modules as fixed constraints to document, not modify. Although this specific finding is about rawler (not RawTherapee), it is the immedate upstream of the rawler→RT data bridge in 000003, so it stays in the same decode/develop thread.

> **Scope**: the integer→float conversion boundary in the develop pipeline, for a 14-bit or 16-bit integer RAW source. Concretely:
> 1. Does the decoder emit f32, or do integer samples survive into the `RawImage`?
> 2. At which single stage does `u16 → f32` happen, and is the cast lossless?
> 3. Is the black-level subtraction also done in f32 (the second place shadow detail could be lost)?
> 4. Where does our binding's exposure 2-EV multiplier sit relative to that conversion — i.e. is minus-EV shadow detail safe?
>
> This is **research only — no code is written or modified.** It closes the loop with the prior exposure-precision finding and with 000003 (the f32 array that the bridge hands to a re-hosted kernel).

All RawTherapee paths are inside the pinned `external/RawTherapee/` submodule; all rawler paths inside `external/dnglab/rawler/`; the binding path is `app/src/binding/rust/rawler_fotlab/`. No upstream source is modified.

## 1. Stage 0 — the decoder emits `Integer(Vec<u16>)`, never f32 (for integer sensors)

- The RAW container (14-bit packed, 16-bit, etc.) is unpacked by rawler's decoders into `RawImageData::Integer(Vec<u16>)`. A 14-bit sensor occupies `0..16383`, a 16-bit sensor `0..65535`; `bps = cam.real_bps`. The value sits in a `u16` slot the whole time.
- `RawImage.data` is set to that integer buffer at construction (`rawler/src/rawimage.rs:470-481`, `data: image`). So the post-decode `RawImage` handed to develop is **still integer**.
- Verified across decoders: `cr2`, `nef`, `raf` (fuji_decompressor produces `Vec<u16>`), `rw2`, `unwrapped`, `iiq` all produce `RawImageData::Integer(...)`. The **only** `RawImageData::Float` construction in `decoders/` is at `decoders/mod.rs:707`, and it is gated on floating-point DNG storage (`f32` samples, i.e. a *float* source) — not on an integer sensor. For a 14/16-bit integer source the data is born and stays `Integer(u16)` until stage 1 below.

## 2. The single conversion point — `RawImage::apply_scaling()`

The **only** integer→float conversion in the entire pipeline is `RawImage::apply_scaling` (`rawler/src/rawimage.rs:519-541`). It is rawler's `ProcessingStep::Rescale` step (`rawler/src/imgop/develop.rs:169-171`); our binding calls it explicitly at `app/src/binding/rust/rawler_fotlab/src/develop.rs:211`.

```rust
// rawimage.rs:519
pub fn apply_scaling(&mut self) -> crate::Result<()> {
  let mut pixels = self.data.as_f32();                 // (a) Integer -> f32 cast
  match &self.photometric {
    RawPhotometricInterpretation::Cfa(_) => {
      correct_blacklevel_cfa(pixels.to_mut(), w, h, &black.as_bayer_array(), &white.as_bayer_array()); // (b) f32 normalize
      self.data = RawImageData::Float(pixels.into_owned());   // (c) data permanently f32
    }
    ...
```

### 2.1 The cast — `as_f32()` → `f32::from(u16)` — is lossless

`as_f32` (`rawimage.rs:264-269`) for the `Integer` variant calls `convert_to_f32_unscaled` (`rawler/src/imgop/mod.rs:278-284`):

```rust
pub fn convert_to_f32_unscaled<T>(pix: &[T]) -> Vec<f32>
where f32: From<T> { pix.iter().copied().map(f32::from).collect() }
```

This is a plain `f32::from(u16)` value-preserving cast. **f32 has a 24-bit mantissa; any u16 (0..65535) is exactly representable**, so the cast introduces **zero** precision loss. A 14-bit value is equally exact. No re-quantisation to integer steps happens here.

### 2.2 The black-level subtraction is also done in f32

`correct_blacklevel_cfa` (`rawler/src/imgop/raw.rs:165-190`) takes `&mut [f32]` and computes, per CFA channel:

```rust
a[0] = clip(a[0] - blacklevel[0]) / max[0];   // max = whitelevel - blacklevel
```

Both operands are `f32`: `a[0]` is the already-cast pixel, `blacklevel`/`whitelevel` are `f32`. So the **black subtraction itself happens in float** — a shadow residual such as `val 130 − black 128 = 2` survives as the small f32 value `2/max`, fully preserved to ~1e-7. This is the *second* place shadow detail could have been lost (the first being the cast); rawler avoids both by staying in f32. (Contrast: an integer pipeline that did `val_u16 - black_u16` then promoted would round the small residual.)

### 2.3 After `apply_scaling`, the data is permanently f32

`self.data = RawImageData::Float(pixels.into_owned())` (`rawimage.rs:531`). From this point on, every develop stage — demosaic, white balance, calibrate, colour mapping, and our binding's exposure — runs on `RawImageData::Float(Vec<f32>)`, normalised to 0..1. `take_scaled_pixels` (`rawler_fotlab/src/develop.rs:259-265`) takes ownership of that `Float` buffer without a copy and **errors if it is still `Integer`**, guarding the upstream contract.

## 3. Where our binding's exposure 2-EV sits (pipeline view)

In `rawler_fotlab/src/develop.rs::develop_image` the order is:

| Step | Line | What | Data type |
| --- | --- | --- | --- |
| `image.apply_scaling()` | `:211` | Integer → f32, black/white normalize | enters f32 here |
| `take_scaled_pixels(&mut image)` | `:218` | take the `Vec<f32>` buffer | f32 |
| `ev_scale = 2f32.powf(ev)` | `:227` | f32 scalar (±2 → 4.0 / 0.25, both exact) | f32 |
| `*p *= ev_scale` | `:229-231` | multiply the mosaic | **f32** |

The exposure multiplier therefore runs **after** the conversion and **on f32**. It is before demosaic/WB/calibrate, but that is irrelevant to precision — those stages are also f32. (rawler itself applies no exposure gain; the only `exposure` hits in `imgop/develop.rs` are EXIF metadata. The 2 EV is our binding's addition.)

## 4. Precision analysis — why minus-EV does not lose shadow detail

- **f32 resolution in 0..1** is ~6e-8 (proportional to magnitude), and a 14-bit sensor's own quantisation step is 1/16383 ≈ 6e-5. After a minus-2-EV (×0.25), a shadow value like `64/16383 ≈ 0.00391` becomes `0.000977`; the f32 spacing there is ~6e-11, i.e. **~1000× finer than the original RAW quantisation**. Shadows are preserved *better* than the sensor delivered them.
- **The failure mode the user worried about** — dark values collapsing to 0/1 under a ×0.25 multiply — only occurs if the multiply is done on `u16` (e.g. `4 → 1`, `1 → 0`). That requires moving exposure *before* `apply_scaling`. The current order (exposure after scaling, on f32) inherently avoids it.
- **Plus-EV** (×4) is a direct f32 upscale, no loss; values >1 are kept unclipped in the ProPhoto editing branch (see `DNGLAB-RAWLER-000005`) and clipped only at sRGB/PNG export — high-light non-protection is intentional, independent of precision.
- `2f32.powf(ev)` for non-integer EV carries ~1 ULP error, negligible.

## 5. Edge case — floating-point DNG

A floating-point DNG is decoded straight to `RawImageData::Float` (`decoders/mod.rs:707`); `as_f32` then just borrows it (`rawimage.rs:267`, `Cow::Borrowed`). There is **no conversion** for those sources — they are f32 from birth. The integer→f32 boundary described in §2 applies only to 14/16-bit integer sensors, which is the case the user asked about.

## Constraints (STRUCT.md principle 5)

`external/RawTherapee` and `external/dnglab/rawler` remain fixed constraints. This document records the conversion boundary and its precision properties as observed in source. It does **not** modify any upstream source and does **not** commit to any code change — it is a research note that (a) answers the exposure-precision question and (b) grounds the f32 array that `RAWTRP-DECODE-000003` hands to a re-hosted kernel.

## Open Questions

- Q1 — For X-Trans, does `correct_blacklevel_cfa` assume a 2×2 bayer tile mask (its `blacklevel[4]` indexing) when the sensor is 6×6? rawler's `apply_scaling` for `Cfa(_)` uses `as_bayer_array()`; confirm the black-level array is correct for X-Trans (the kernel path in 000003's bridge would inherit any error). Needs an X-Trans test image.
- Q2 — `convert_to_f32_unscaled` vs `convert_to_f32_scaled`: rawler's `apply_scaling` deliberately uses the *unscaled* `as_f32` then normalises in `correct_blacklevel_cfa`, rather than the combined `convert_to_f32_scaled` helper (`imgop/mod.rs:287`). Confirm both paths are numerically identical for the Cfa branch (they should be — same f32 subtract/divide order) so a future refactor does not change magnitude.
- Q3 — If exposure is ever moved earlier for performance, it must stay in f32. The guard in `take_scaled_pixels` only catches the *post*-scaling state; a future author could call exposure on `pixels_u16()` (rawimage.rs:500) and silently reintroduce the §4 failure mode. Worth a one-line comment at the exposure site.

## Change History

- 2026-09-19 — RawTherapee/rawler **integer→f32 conversion boundary** study. Confirmed the post-decode `RawImage` is `RawImageData::Integer(Vec<u16>)` for 14/16-bit integer sensors (decoders cr2/nef/raf/rw2/unwrapped/iiq all emit `Integer`; the only `RawImageData::Float` construction is `decoders/mod.rs:707`, gated on floating-point DNG, not integer). The sole `u16→f32` conversion is `RawImage::apply_scaling` (`rawler/src/rawimage.rs:519-541`), rawler's `ProcessingStep::Rescale` (`rawler/src/imgop/develop.rs:169-171`), called explicitly at `rawler_fotlab/src/develop.rs:211`. Inside it: `as_f32` → `convert_to_f32_unscaled` (`rawler/src/imgop/mod.rs:283`, `f32::from(u16)`) is **lossless** (16-bit < 24-bit f32 mantissa); the black-level subtraction + normalize also run in f32 inside `correct_blacklevel_cfa` (`rawler/src/imgop/raw.rs:165-190`), so shadow residuals survive; after `self.data = RawImageData::Float(...)` (`rawimage.rs:531`) all downstream stages are f32. Our binding's exposure 2-EV (`develop.rs:227`, `*p *= ev_scale` on `Vec<f32>` from `take_scaled_pixels` at `:218`) therefore runs on f32, after the conversion — so minus-EV does **not** lose shadow detail (the `u16 × 0.25 → collapse to 0/1` failure mode would only arise if exposure were moved before `apply_scaling`). Floating-point DNG is born f32 (no conversion). Filed as `RAWTRP-DECODE-000004`; row appended to `rules/STRUCT/index.md`.
