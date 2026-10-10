# OKLab/OKLCH post-demosaic dehaze — guided-filter haze field inside the OKLab highlight block

- ID: FOTLAB-DEHAZE-000002
- Status: Draft
- Priority: P2
- Created: 2026-10-10
- Owner: —
- Related: `FOTLAB-RENDER-000001` (OKLab highlight-chroma compression — this design extends the *same* OKLab block with a second op), `FOTLAB-DEHAZE-000001` (CFA-domain guided-filter dehaze — the physically-correct pre-demosaic counterpart; this stage is the perceptual post-demosaic sibling), `FOTLAB-RENDER-000003` (axial CA / purple-fringe — why demosaic highlight chromatic aberration must be neutralised before dehaze), `FOTLAB-RAWLER-000018` (root cause of the clipped-highlight false hue the roll-off removes), `FOTLAB-RAWLER-000010` (guided-filter feasibility research, reused primitives), `RAWTRP-SURVEY-000002` (OKLab/Oklch availability — standard Ottosson constants, port not copy)

## Background & Goal

`FOTLAB-RENDER-000001` ships a camera-space `camera → XYZ(D65) → OKLab → highlight-roll-off → XYZ(D65) → camera` bypass that desaturates near-clipped highlights *before* they reach the per-channel sRGB clamp, killing the magenta/cyan false hue frozen by uneven per-channel clipping (`FOTLAB-RAWLER-000018`, `FOTLAB-RENDER-000003`). That block sits **above the cache** and is short-circuitable.

We now extend that block with a second perceptual op: a **dehaze in OKLab/OKLCH**, run *after* the highlight roll-off. The physical basis (atmospheric scattering `I = J·t + A·(1−t)`): as haze thickens (`t→0`) a pixel moves toward the atmospheric light `A` — bright and near-neutral — which in OKLCH reads as **high `L`, low `C`**. Dehaze is therefore the inverse: **lower `L`, raise `C`**, restoring the scene's contrast and saturation without shifting hue `h` (OKLCH keeps `L`/`C`/`h` cleanly separated, so chroma changes are hue-preserving — unlike RGB, where scaling channels rotates hue). This direction is independently validated by published CIELAB/LCh dehazing work (CIELAB Color Channel Transfer, *Image and Vision Computing*, 2026: clear images have lower `L` and higher `C`; the CCT transfer "lowers L, raises C").

Goal:

- **G1** — Add a dehaze op that lives in the *same* OKLab stage as the highlight roll-off, in the pipeline order `camera → OKLab → highlight_rolloff → dehaze_oklab → OKLab → camera`, operating on the post-demosaic camera buffer.
- **G2** — Make the dehaze **region-aware**: distinguish hazy from non-hazy areas with a **guided filter** (reusing the existing `guided_filter`/`box_mean`/`box_min` from `FOTLAB-DEHAZE-000001`), so flat bright wash-out (sky, fog) is dehazed hard while textured/saturated subjects and the clipped-highlight band are left alone.
- **G3** — Keep the dehaze **short-circuitable**: a gating switch makes it a bit-for-bit identity when off, exactly like the roll-off today (`FOTLAB-RENDER-000001` R4).
- **G4** — Run dehaze **after** the highlight roll-off so the demosaic highlight chromatic aberration (false hue) is neutralised first and is *not* amplified by the dehaze's chroma boost.

This stage is a **perceptual colour restoration** (a "look"), distinct from `FOTLAB-DEHAZE-000001`'s physically-correct Koschmieder transmission recovery in the CFA domain. The two are complementary, not redundant: the CFA stage removes uniform black-point haze pre-demosaic; this stage removes the perceived bright-washout post-demosaic. Neither claims the other's physics.

## Requirement

### R1 — Position: dehaze_oklab is a second op inside the existing OKLab block

The OKLab block (`calibrate_oklab.rs`, invoked from `camera_space.rs::to_working_space`) already runs one camera-space-in / camera-space-out round trip per render. This design adds `dehaze_oklab` as a **second sequential op inside that same block**, after the highlight roll-off:

```
demosaic → WB → camera (post-WB, pre-matrix)
   → ┌──────── OKLab STAGE (above cache, short-circuitable) ────────┐
     │  camera ─cam2xyz─→ XYZ(D65) ───→ OKLab                      │
     │      → highlight_rolloff   (existing L-driven C roll-off)  │
     │      → dehaze_oklab         (NEW: L↓, C↑ by guided haze)    │
     │  OKLab ───→ XYZ(D65) ─xyz2cam─→ camera                     │
     └─────────────────────────────────────────────────────────────┘
   → cam2rgb (per output space: sRGB D65 / ProPhoto D50)
   → bound::encode_srgb (the single clip point)
```

The block stays camera-space-in / camera-space-out, runs above the cache, and operates on the cropped camera buffer exactly as today. No change to the cache boundary, the working-space split, or the sRGB clamp.

### R2 — Order: highlight roll-off first, then dehaze

The two ops run in this fixed order: **roll-off, then dehaze**. Rationale (`G4`): dehaze *raises* `C`; a clipped / aberrated highlight carries a false hue (`FOTLAB-RAWLER-000018`, `FOTLAB-RENDER-000003`) that the roll-off deliberately desaturates. If dehaze ran first and boosted that pixel's `C`, the false chroma would be amplified. Running roll-off first neutralises the false hue, so dehaze only restores *legitimate* scene chroma. As a hard back-stop, the dehaze's `C` boost is additionally **suppressed in the roll-off band** (`L ≥ OKLAB_KNEE_START`, see `calibrate_oklab.rs`) so it can never re-saturate a pixel the roll-off already neutralised (constraint C5).

### R3 — Gating: a short-circuitable per-output switch

- The block's master gate `PipelineStages::oklab` is unchanged (runs the whole stage).
- `OklabSwitches` (`camera_space.rs`) gains two per-output sub-switches, mirroring the existing `highlight_compress_*`:
  - `dehaze_oklab_srgb: bool` — dehaze the `SrgbD65` presentation output.
  - `dehaze_oklab_prophoto: bool` — dehaze the `ProPhotoD50` graded output.
- `DevelopParams` (`develop.rs`) gains the same two fields (default **false** = off) plus a strength:
  - `dehaze_oklab_strength: Option<f32>` (clamped `0..1`) — the user amount; `None` or `0` ⇒ identity.
- Kotlin derives the master `oklab` gate and the sub-switches exactly as it does for the roll-off today.

### R4 — Short-circuit invariant (correctness contract, extends RENDER-000001 R4)

With `dehaze_oklab` **off** (sub-switch false for the output space), **or** `dehaze_oklab_strength == 0`, the dehaze op is the identity map in OKLab, so the whole block's output is bit-for-bit identical to the gate-off path. Concretely:

- When both sub-switches (roll-off and dehaze) for the output are off, the block is skipped entirely — identical to today.
- When roll-off is on and dehaze off, output is identical to today's roll-off-only path (no regression, AC2).
- The dehaze per-pixel kernel **early-returns without the OKLab round trip** whenever its gate is off, its strength is 0, **or** the local haze field `h_ok` at that pixel is ≈ 0 — so non-hazy pixels incur no f32 round-trip drift and stay exact. This preserves the exactness the existing roll-off kernel already relies on (early-return for `L ≤ KNEE_START` / neutral pixels).

### R5 — Region-aware haze field via guided filter

The dehaze is driven by a spatially-varying haze field `h_ok ∈ [0,1]` computed **once per render** over the cropped camera buffer (above the cache), reusing the existing `guided_filter` / `box_mean` / `box_min` from `dehaze_guided_filter.rs` (`pub(crate)`, no new box-filter code):

1. **Convert the cropped buffer to OKLab once** (camera → XYZ(D65) → OKLab, using the same `OklabBypassMaps`). This yields per-pixel `L`, `a`, `b`; `C = √(a²+b²)`.
2. **Fog proxy** `m` per pixel — high `L` AND low `C` (the wash-out signature):
   ```
   m_bright = smoothstep(L_lo, L_hi, L)          # bright ⇒ hazy candidate
   m_wash   = 1 - smoothstep(0, C_hi, C)         # desaturated ⇒ hazy candidate
   m = clamp(m_bright * m_wash, 0, 1)
   ```
   Tunable constants `L_lo`, `L_hi`, `C_hi` (defaults `L_lo=0.6, L_hi=0.95, C_hi=0.12`, pending real-image validation — open question Q2). Flat bright wash-out scores high; bright saturated subjects score low.
3. **Refine with the guided filter** (He & Sun 2015): `guide = L` (or camera luminance), `src = m`, radius/eps defaulting to `GUIDE_RADIUS=8` / `GUIDE_EPS=0.01` (reused), then `h_ok = clamp(guided_filter(L, m), 0, h_ceiling)`. Edge-preserving: flat hazy sky gets a high, smooth `h_ok`; textured/saturated regions keep `h_ok ≈ 0`, so dehaze is **local**, not global — exactly the "区分不同区域的雾气" requirement.

The field is computed from the **pre-roll-off** buffer (it detects haze in the scene, independent of the roll-off), then consumed by the per-pixel dehaze op below.

### R6 — Application: lower L, raise C, keep h

Per pixel, after the roll-off op, the dehaze op applies (using the precomputed `h_ok` at that pixel):

```
L' = L - strength * h_ok * L_gain                       # lower L: recover darkness lost to airlight
guard = (L < OKLAB_KNEE_START) ? 1.0 : 0.0              # never re-saturate the rolled-off clipped band
C' = min(C + strength * h_ok * C_gain * guard, C_ceiling)  # raise C: restore saturation (hue-preserving)
h  = atan2(b, a)  (unchanged)
a' = C' * cos(h);  b' = C' * sin(h)                      # rebuild a,b from C',h
```

- `h` is **never touched** — the op is hue-preserving by construction (AC7).
- `L_gain`, `C_gain` are tunable (defaults `1.0`; the classical Koschmieder analogue would set `L'` so the airlight is subtracted, but here it is a perceptual gain, Q3).
- `C_ceiling` bounds gamut excursion: `C'` is clamped to a parameter (default e.g. `0.4`, Q4) rather than projected onto the working-space gamut cusp, to keep the op simple and the buffer unclamped (`FOTLAB-RENDER-000001` C5); a full Oklch→gamut cusp projection is deferred (Q4).
- `guard` enforces C5: the dehaze never raises `C` in the clipped-highlight band the roll-off already neutralised.

### R7 — No new colour math, no new allocation beyond the field

- The OKLab constants and the camera↔XYZ(D65) maps come from `calibrate_oklab.rs` (`RAWTRP-SURVEY-000002`: standard Ottosson, port not copy).
- The guided filter, box mean, and box-min come from `dehaze_guided_filter.rs` (reused, `pub(crate)`).
- The only new buffer is the `h_ok` field (one `f32` per cropped pixel) plus the per-pixel OKLab scratch the block already allocates — no large new allocation (C4).

## Constraints

- **C1** — `external/` is read-only (`rules/DESIGN.md` principle 5). Port the OKLab constants and reuse the guided-filter primitives; do not modify `external/`, and do not vendor-copy `dehaze_guided_filter.rs` bodies — call them.
- **C2** — The block stays camera-space-in / camera-space-out, above the cache, on the cropped buffer. The working-space buffer remains **unclamped** after the block; `bound::encode_srgb` stays the single clip point (`FOTLAB-RENDER-000001` C5).
- **C3** — The short-circuit invariant (R4) holds: off ≡ on-with-zero-strength ≡ identity, bit-for-bit where the gate is off.
- **C4** — No large new allocation; only the `h_ok` field plus existing scratch.
- **C5** — The dehaze must **not** reintroduce the magenta/cyan false hue the roll-off removes. Enforced by (a) running after the roll-off (R2) and (b) the `L < KNEE_START` guard on the `C` boost (R6).
- **C6** — Defaults **off** for both per-output sub-switches (dehaze is visually aggressive; ship conservative, let Kotlin enable). The Rust default is `false`; Kotlin assembles the gate.

## Acceptance Criteria

- **AC1** — With `dehaze_oklab` off for the output and roll-off off, the full image (both working spaces) is byte-for-byte identical to the current output (no block change).
- **AC2** — With `dehaze_oklab` off and roll-off on, output equals today's roll-off-only path exactly (no regression from adding the second op).
- **AC3** — With `dehaze_oklab` on and `strength == 0`, output equals the gate-off output (R4), verified by a unit test on a fixed fixture.
- **AC4** — On a synthetic hazy fixture (a bright low-`C` region + a textured saturated subject + a clipped-highlight band), dehaze (on) measurably **lowers `L` and raises `C`** in the hazy region while leaving the saturated subject's hue and the clipped-highlight band's neutralisation intact (no false-chroma amplification).
- **AC5** — The guided filter produces a spatially-varying `h_ok` that is high in flat bright wash-out and low in textured/saturated regions (region discrimination), pinned by a unit test comparing `h_ok` across the two region classes.
- **AC6** — The dehaze apply step is per-pixel parallel with no cross-pixel state; a `cargo test` exercises the OKLab round-trip and the `h_ok` discrimination.
- **AC7** — Hue `h` is unchanged (within rounding) for every pixel the dehaze touches, measured as `|h_out − h_in|` over the fixture.

## Impacted Modules

- `app/src/binding/rust/rawler_fotlab/src/calibrate_oklab.rs` — add `dehaze_oklab` op + the guided-filter haze-field pre-pass; combine with the existing roll-off into the block's per-pixel kernel (roll-off then dehaze). Reuse `OklabBypassMaps`.
- `app/src/binding/rust/rawler_fotlab/src/camera_space.rs` — `OklabSwitches` gains `dehaze_oklab_srgb` / `dehaze_oklab_prophoto`; `to_working_space` passes them + `strength` to the block.
- `app/src/binding/rust/rawler_fotlab/src/develop.rs` — `DevelopParams` gains `dehaze_oklab_srgb` (default `false`), `dehaze_oklab_prophoto` (default `false`), `dehaze_oklab_strength: Option<f32>`; forwarded into `OklabSwitches`.
- `app/src/binding/rust/rawler_fotlab/src/dehaze_guided_filter.rs` — reused (`guided_filter` / `box_mean` / `box_min`); no change unless a visibility tweak is needed.
- `app/src/main/kotlin/.../StudioOpBars.kt`, `StudioEngine.kt` — new gating UI (follow-up; out of scope of this doc, noted).
- `rules/DESIGN/detail/FOTLAB-RENDER-000001.md` — this design extends its block; link back from there.

## Open Questions

- **Q1** — Should `h_ok` be computed from the **pre-roll-off** buffer (chosen in R5) or the post-roll-off buffer? Pre-roll-off keeps haze detection independent of the highlight treatment; confirm on real images.
- **Q2** — Fog-proxy thresholds `L_lo/L_hi/C_hi` and the coherence term (optionally `1 − smoothstep(var_lo, var_hi, local_var_L)` so flat bright regions out-score bright textured ones). Defaults are placeholders; validate against hazy fixtures.
- **Q3** — `L_gain` / `C_gain` shape: a flat perceptual gain (default `1.0`) vs a Koschmieder-style `L' = L − strength·h_ok` airlight subtraction. The latter is more physical but in a perceptual space is only a look; pick after visual review.
- **Q4** — Chroma ceiling: clamp `C'` to `C_ceiling` (chosen, simple) vs a full Oklch→working-gamut cusp projection (preserves hue on clamp, but needs the gamut-cusp solve `RAWTRP-SURVEY-000002 §9 Q5` flagged as sRGB-vs-ProPhoto). Start with the ceiling; promote if clamp-induced hue shift appears.
- **Q5** — Guide choice for the guided filter: `L` (chosen) vs camera/scene luminance vs a combined `L·(1−C)` proxy. Confirm which gives the cleanest region edges.
- **Q6** — Strength exposure: a single global `strength` (chosen) vs per-output independent amounts. Kotlin surface is a follow-up.

## Change History

- 2026-10-10 — Initial draft. Specified `dehaze_oklab` as a second op inside the existing OKLab highlight block, ordered `camera → OKLab → highlight_rolloff → dehaze_oklab → OKLab → camera` (R1/R2). Defined a short-circuitable per-output gating switch extending `FOTLAB-RENDER-000001` R4 (R3/R4), a guided-filter haze field `h_ok` reusing `dehaze_guided_filter.rs` primitives for region discrimination (R5), and a hue-preserving lower-L / raise-C application with a clipped-highlight guard (R6/C5). Marked it the perceptual post-demosaic sibling of `FOTLAB-DEHAZE-000001` (physically-correct CFA dehaze) and linked the highlight-false-hue root cause (`FOTLAB-RAWLER-000018`, `FOTLAB-RENDER-000003`). Defaults off (C6); opened Q1–Q6 on thresholds, gains, chroma ceiling, guide, and UI exposure.
