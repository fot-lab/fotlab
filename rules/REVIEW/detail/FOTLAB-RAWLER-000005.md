# Working space is locked to sRGB and irreversibly gamut-clipped in `calibrate` — switch to a wide-gamut (ProPhoto D50) hub

- ID: FOTLAB-RAWLER-000005
- Status: Proposal
- Priority: P1
- Created: 2026-09-18
- Owner: —
- Related: `rules/REVIEW/detail/FOTLAB-RAWLER-000003.md` (develop contract — the change below amends its `LinearImage` semantics), `rules/REVIEW/detail/DNGLAB-RAWLER-000005.md` (rawler's `Calibrate` is where D65 is set; `SRgb` is only gamma), `rules/REVIEW/detail/DNGLAB-RAWLER-000001.md`, `rules/REVIEW/detail/DNGLAB-RAWLER-000002.md`, `rules/DESIGN/detail/FOTLAB-STUDIO-000001.md`

## Background & Goal

`FOTLAB-RAWLER-000003` deliberately made our develop output **linear** and left the display
transform to Kotlin, so that tone mapping happens in light-linear space. That was the right call,
but it is easy to read "linear" as "wide gamut". They are two independent axes:

- **transfer function** — linear vs gamma-encoded;
- **gamut** — which RGB *primaries* (and white point) the numbers live in.

We fixed the first and left the second at sRGB. This item records that the working space is still
sRGB, that the pipeline **irreversibly discards out-of-sRGB colour before Kotlin ever receives the
pixels**, and — because we already own the calibrate step — the concrete, contained method to move
to a wide-gamut hub (ProPhoto D50), matching what both other engines in `external/` already do.

## Finding

### 1. The gamut is fixed at one line, in our own code

`app/src/binding/rust/rawler_fotlab/src/calibrate.rs:87-88`:

```rust
let rgb2cam = normalize(multiply(&xyz2cam, &SRGB_TO_XYZ_D65));
let cam2rgb = pseudo_inverse(rgb2cam);
```

The camera→XYZ matrix is anchored on `SRGB_TO_XYZ_D65`
(`external/dnglab/rawler/src/imgop/xyz.rs:102`), so `LinearImage` is **linear sRGB with a D65
white point** — sRGB primaries, i.e. the small gamut. This mirrors rawler's own
`ProcessingStep::Calibrate` (`DNGLAB-RAWLER-000005` §Finding 1): D65 is decided at *Calibrate*,
and the later `SRgb` step only applies gamma. We replicated the math with rawler's public
primitives precisely so we could control it — which is what makes the fix in §3 cheap.

### 2. `clip_euclidean_norm_avg` then destroys everything outside the sRGB cube

`calibrate.rs:118` (three-colour) and `calibrate.rs:141` (four-colour) call rawler's
`clip_euclidean_norm_avg` (`external/dnglab/rawler/src/imgop/raw.rs:72-85`):

```rust
let pix = clip_negative(pix);                 // negative components → 0
if max_val > 1.0 {
  let color = pix.map(|p| p / max_val);       // normalise
  let eucl  = /* RMS of the components */;
  color.map(|p| (p + eucl) / 2.0)             // desaturate back inside the cube
} else { pix }
```

This is an **sRGB-hull clamp**: negatives are zeroed and any component above 1.0 is desaturated
back into the `[0,1]` cube. Colours outside the sRGB hull — deep greens and cyans, some reds, and
much of any LED/laser-lit scene — are therefore destroyed **inside Rust, before the pixels cross
the FFI**. The loss is not recoverable later: converting a clipped linear-sRGB buffer to ProPhoto
only re-containers data that is already gone.

### 3. The other engines in `external/` already use a wide hub

| Engine | Working space |
| --- | --- |
| rawalchemy `decodeRaw` | **Linear ProPhoto RGB (D50)** |
| RawTherapee (rtengine) | **ProPhoto (D50) hub** → chosen working space |
| rawler, and therefore RapidRAW and **us** | linear sRGB D65, clipped to the cube |

Notably RapidRAW shares the limitation: `src-tauri/src/raw_processing.rs:174-179` strips
`ProcessingStep::SRgb` on every path but **keeps `Calibrate`**, so its linear intermediate is the
same clipped linear sRGB. "Professional editor" does not exempt it — its own highlight recovery
runs on data that has already been clipped.

Consequence for FotLab: as it stands we cannot offer the editing latitude the product is aiming
for, and the damage is silent (no error, no warning — just colour that is no longer there).

## Impact / Conflict

- **Amends `FOTLAB-RAWLER-000003`.** That item defines `LinearImage` as "linear RGB, no gamma".
  After this change it becomes *linear, wide-gamut* (ProPhoto D50) and may legitimately contain
  negative and >1 components. Both items must be updated together; do not change one alone.
- **Simplifies the rawalchemy bridge.** Joining dnglab output into rawalchemy's Log pipeline
  currently needs an sRGB(D65)→ProPhoto(D50) bridge including a D65→D50 chromatic adaptation.
  Calibrating straight to ProPhoto D50 removes that bridge entirely.
- **Requires client-side tolerance for out-of-range values.** Histograms, masks, slider ranges and
  any preview that assumes `[0,1]` must be revisited; clipping must move to the final
  export/display step only.
- **No upstream change needed** — the fix lives entirely in our own `calibrate.rs`, so this is
  consistent with `REVIEW.md` principle 5 (upstream out of scope).
- **Not a blocker today.** The app works; this is a silent quality/latitude ceiling, hence P1
  rather than P0.

## Improvement method

Because we already own calibrate (we never call rawler's `ProcessingStep::Calibrate`), the change
is contained to `app/src/binding/rust/rawler_fotlab/src/calibrate.rs`. rawler already ships the
needed matrices — `imgop/xyz.rs`:

```
102  SRGB_TO_XYZ_D65        109  XYZ_TO_ADOBERGB_D65
116  XYZ_TO_ADOBERGB_D50    123  XYZ_TO_SRGB_D50
130  XYZ_TO_SRGB_D65        137  XYZ_TO_PROFOTORGB_D50
```

**Step 1 — adapt the camera matrix to D50 instead of D65.**
`calibrate.rs:44-66` currently resolves `color_matrix_find_first([D65, A, B, C, D50, ...])` and
Bradford-adapts anything else to D65. Adapt to `Illuminant::D50` instead; `adapt_bradford` is
already imported (`calibrate.rs:20`).

**Step 2 — anchor on ProPhoto instead of sRGB.**
`XYZ_TO_PROFOTORGB_D50` is XYZ→ProPhoto, so take its inverse to get the direction we need:

```rust
use rawler::imgop::xyz::XYZ_TO_PROFOTORGB_D50;

let prophoto_to_xyz_d50 = pseudo_inverse(XYZ_TO_PROFOTORGB_D50);
let rgb2cam   = normalize(multiply(&xyz2cam_d50, &prophoto_to_xyz_d50));
let cam2rgb   = pseudo_inverse(rgb2cam);
```

`pseudo_inverse` is already imported (`calibrate.rs:18`). Nothing else in the per-pixel loop
changes — the loop at `calibrate.rs:109-119` / `131-143` just applies `cam2rgb`.

**Step 3 — relax the clip.**
`clip_euclidean_norm_avg` is an sRGB-cube clamp and must not be applied in a wide space, where
negatives and >1 are legitimate intermediates (ProPhoto's primaries are imaginary, so even
in-gamut real colours can go slightly negative). Keep only a negative-floor guard if the
downstream pipeline needs it, and move any real gamut mapping to the final export/display
transform.

**Rejected alternative — convert linear sRGB → ProPhoto after the fact.** This does not work: by
the time the buffer exists, step 2's clip has already destroyed the out-of-sRGB information. The
conversion must happen *before* the clamp, which is exactly what changing the anchor matrix does.

**Preconditions / risks.**
- Bit depth: ProPhoto demands high precision or shadows posterise badly. We are `f32` throughout,
  so this is satisfied.
- Any code that assumes `LinearImage` values are within `[0,1]` must be found and fixed first
  (audit histogram, masks, thumbnail/preview paths).
- Verify on a saturated reference frame: a wide-gamut round-trip must preserve deep green/cyan
  where the current build shows it clipped.

## Recommendation

1. Record the decision, then switch the working space to **linear ProPhoto (D50)** following the
   three steps above, updating `FOTLAB-RAWLER-000003` in the same change.
2. Treat the `[0,1]` assumption audit as part of the work, not a follow-up — it is the main source
   of subtle breakage.
3. Keep gamma and gamut mapping out of Rust: Rust emits linear wide-gamut; Kotlin owns display and
   export transforms. This preserves the `FOTLAB-RAWLER-000003` division of responsibility.
4. Add a regression sample with known out-of-sRGB content so the clip cannot silently return.

## Change History

- 2026-09-18 — Review recorded. Established that "linear" and "wide gamut" are independent axes
  and that we only fixed the former: `calibrate.rs:87` anchors the camera matrix on
  `SRGB_TO_XYZ_D65`, and `calibrate.rs:118/141` then call rawler's `clip_euclidean_norm_avg`
  (`imgop/raw.rs:72-85`), which zeroes negatives and desaturates >1 back into the sRGB
  `[0,1]` cube — an irreversible loss occurring before the FFI boundary, so a later conversion
  to a wide space recovers nothing. Compared against the other engines in `external/` (rawalchemy
  → Linear ProPhoto D50, RawTherapee → ProPhoto D50 hub) and noted that RapidRAW shares our
  limitation because it keeps `Calibrate`. Recorded the improvement method: we own calibrate, and
  rawler already ships `XYZ_TO_PROFOTORGB_D50` (`imgop/xyz.rs:137`), so the switch is
  (1) Bradford-adapt the camera matrix to D50, (2) anchor on
  `pseudo_inverse(XYZ_TO_PROFOTORGB_D50)`, (3) relax the sRGB-cube clamp; this also removes the
  sRGB(D65)→ProPhoto(D50) bridge needed for rawalchemy. Flagged the `[0,1]`-assumption audit and
  the `FOTLAB-RAWLER-000003` contract update as part of the same change. Row appended to
  `rules/REVIEW/index.md`.
