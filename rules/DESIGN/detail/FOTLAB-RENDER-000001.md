# OKLab highlight-chroma compression — short-circuitable camera-space bypass

- ID: FOTLAB-RENDER-000001
- Status: Draft
- Priority: P1
- Created: 2026-10-04
- Owner: —
- Related: `FOTLAB-RAWLER-000018` (review — sRGB highlight magenta root cause; OKLab compression must decompose the baked matrix), `FOTLAB-PIPELN-000001` (develop stage — camera→working conversion lives in `calibrate`), `FOTLAB-NATIVE-000001` (dnglab/rawler is a fixed, read-only constraint), `RAWTRP-SURVEY-000002` (OKLab surveyed as the target perceptual space)

## Background & Goal

Our colour path bakes the whole camera→working-space conversion into a single 3×3 `cam2rgb` (`app/src/binding/rust/rawler_fotlab/src/calibrate.rs:116-117`):

```
rgb2cam = normalize(xyz2cam · SRGB_TO_XYZ_D65)      // sRGB → camera, D65-anchored
cam2rgb = pseudo_inverse(rgb2cam)                    // camera → working space
```

`xyz2cam` is rawler's camera colour matrix resolved at the target illuminant (`resolve_xyz_to_cam`, `calibrate.rs:212`); `SRGB_TO_XYZ_D65` is sRGB→XYZ(D65). The per-pixel loop (`calibrate.rs:143-151`) applies white balance in camera space and then multiplies by `cam2rgb`. The **only** clip point is `bound::encode_srgb` (per-channel clamp + sRGB gamma, `bound.rs:130`), because the working-space buffer is intentionally kept unclamped (negatives and >1 survive for the ProPhoto editing path).

Root cause (recorded in `FOTLAB-RAWLER-000018`): non-neutral highlights clip *unevenly* across channels — sensor saturation is per-channel on the RAW mosaic, and after WB gain × colour matrix the clipped vector no longer lands on the neutral axis, e.g. a tungsten highlight becomes `[1.00, 0.78, 1.00]` (magenta, G low). Because clipping is per-channel and happens *before* the matrix, the hue error is frozen and cannot be undone downstream. This is **not** a demosaic / double-green artifact (RGGB merges to a single G in `Intermediate::ThreeColor` before this stage).

Goal:

- **G1** — Add a perceptual highlight treatment that reduces the chroma of near-clipped pixels *before* they reach the per-channel sRGB clamp, so the frozen hue is closer to neutral instead of magenta/cyan.
- **G2** — Do it in **OKLab**, the perceptual space recommended by the prior survey (`RAWTRP-SURVEY-000002`), where lightness `L` and chroma `C=√(a²+b²)` separate cleanly and a lightness-driven chroma roll-off is hue-preserving.
- **G3** — Keep the transform a **self-contained camera-space-in / camera-space-out** block so the rest of the pipeline (the existing `cam2rgb` multiply, the working-space choice, the sRGB clamp) is untouched and the feature can be **short-circuited** (skipped) with zero behavioural change when disabled.

## Requirement

### R1 — Insert a bypass block between WB and `cam2rgb`

The block is placed in `calibrate` immediately after the white-balance multiply (`calibrate.rs:144-146`) and before the `cam2rgb` multiply (`calibrate.rs:147-151`). Its contract is `camera-space f32 → camera-space f32` (same 3 channels, same buffer layout). The surrounding pipeline does not change.

**Branch scope (temporary):** the bypass is applied **only to the `SrgbD65` presentation branch**. The `ProPhotoD50` editing branch is out of scope for now and its camera buffer passes straight to `cam2rgb` unchanged (see C2 and Q2).

```
demosaic → Intermediate (camera space, R/G/B per pixel)
   → WB multiply  (wb[0..2])                         [existing, calibrate.rs:144-146]

   → ┌────────── OKLab highlight-compression BYPASS (new, short-circuitable) ──────────┐
     │  camera ─cam2xyz=camera→XYZ(D65)→ XYZ(D65)                                       │
     │        ─XYZ→OKLab─→ chroma/lightness roll-off ─OKLab→XYZ─→ XYZ(D65)              │
     │        ─xyz2cam=XYZ(D65)→camera─→ camera   (runs BEFORE the sRGB/ProPhoto split) │
     └──────────────────────────────────────────────────────────────────────────────┘

   ── SrgbD65 presentation branch ──  → cam2rgb (camera → sRGB D65)     [existing, calibrate.rs:147-151]
   ── ProPhotoD50 editing branch  ──  → cam2rgb (camera → ProPhoto D50)  [existing, calibrate.rs:147-151]
   (both branches consume the *same* post-roll-off camera buffer; the split is after the bypass)
   → RawlerImageDeveloped (working space, UNCLAMPED)
   → bound::encode_srgb (sRGB clamp + gamma — the only clip point)
```

### R2 — The four transforms inside the bypass

All four are fixed or camera-only matrices plus one perceptual non-linearity:

1. **`cam2xyz = to_xyz · cam2rgb_eff`** — camera → XYZ(D65). `cam2xyz`/`xyz2cam` are anchored on **D65 regardless of the output working space**: `to_xyz` is always `SRGB_TO_XYZ_D65` and `cam2rgb_eff` is the D65-anchored camera→linear RGB 3×3 (i.e. `pinv(normalize(xyz2cam · SRGB_TO_XYZ_D65))`, the same mapping the SrgbD65 presentation path uses). The 4th E column is unused for RGGB. Rebuilding from these *original factors* — **never** inverting the space-dependent `cam2rgb` alone — keeps the round-trip exact and the XYZ mid-point genuinely D65 (see C3). Because the maps are D65-anchored, the ProPhotoD50 branch needs **no** D50↔D65 Bradford bridge (this retracts the previous C2 deferred note).
2. **XYZ(D65) → OKLab** — standard Ottosson transform: `LMS = M1 · XYZ`, `LMS = ∛LMS`, `OKLab = M2 · LMS`, where (from `external/colour/colour/models/oklab.py`, D65-in):

   ```
   M1 (XYZ→LMS)                              M2 (LMS→OKLab)
   0.8189330101  0.3618667424 -0.1288597137   0.2104542553  0.7936177850 -0.0040720468
   0.0329845436  0.9293118715  0.0361456387   1.9779984951 -2.4285922050  0.4505937099
   0.0482003018  0.2643662691  0.6338517070   0.0259040371  0.7827717662 -0.8086757660
   ```
3. **Highlight compression** — a parametric lightness-driven chroma (and optionally lightness) roll-off. As `L → 1` (and `L > 1` for super-whites), scale `C = √(a²+b²)` down toward 0 so the clipped pixel desaturates instead of shifting hue. Exact curve is intentionally left open (see Q1); it must collapse to the identity when the strength parameter is 0 so the short-circuit invariant (R4) holds.
4. **OKLab → XYZ(D65)** — inverse of step 2 (`LMS = M2⁻¹ · OKLab`, `LMS = LMS³`, `XYZ = M1⁻¹ · LMS`), then **`xyz2cam_eff = cam2rgb_eff⁻¹ · to_xyz⁻¹`** (XYZ→camera) back to camera space.

The two OKLab matrices (`M1`, `M2`, their inverses) are **camera-independent and fixed**; only `cam2xyz`/`xyz2cam` depend on the camera. The only non-linearities are `∛` and `x³` (three components each, branchless), so the block is fully per-pixel and rayon-parallel per row, identical in shape to the existing `cam2rgb` loop.

**Forward ↔ backward symmetry (must hold exactly).** The OKLab conversion is an order-symmetric pair; any imbalance breaks the R4 identity invariant:

- Forward (XYZ(D65) → OKLab): `lms = M1 · xyz` → `lms = ∛lms` → `oklab = M2 · lms`.
- Backward (OKLab → XYZ(D65)): `lms = M2⁻¹ · oklab` → `lms = lms³` → `xyz = M1⁻¹ · lms`.

Every forward step is undone by its exact inverse in reverse order — `∛` ↔ `(·)³` (per channel), `M2` ↔ `M2⁻¹`, `M1` ↔ `M1⁻¹`. `M1⁻¹` / `M2⁻¹` are the **exact matrix inverses** of the *same* constants used forward (not transposes, not recomputed approximations). The cube root is a per-channel non-linearity and therefore sits **between** the two linear maps in both directions — it does not commute with `M1`/`M2`, so its position relative to the matrices must not be swapped. This guarantees the round-trip `XYZ → OKLab → XYZ = I` (AC5).

### R3 — Short-circuit control

- A single boolean gate (`DevelopParams::oklab_highlight_compress`, default **true** = on) enables the bypass. The pipeline runs the compression by default; Kotlin passes this field (default `true`), so passing `false` from Kotlin disables it and restores the exact bit-for-bit identity pass-through (R4). The gate is parameterised so it can be turned off without a Rust change.
- The gate is the *only* thing the feature adds to the data path; enabling it never alters buffer shape, working space, or the downstream clamp.

### R4 — Short-circuit invariant (correctness contract)

- With the gate **off**, output == input (pure pass-through).
- With the gate **on** and compression strength **0**, output == input (the block is the identity map, because `xyz2cam · cam2xyz = I` and step 3 is identity). This makes "off" and "on-with-zero-strength" observationally identical — a directly testable property (AC4).

## Constraints

- **C1** — `C2`/`C3` (upstream is read-only): The block is first-party Rust in `rawler_fotlab/src`; `external/` OKLab implementations are **reference only** (do not modify their source). Port the constants, do not vendor-copy the bodies verbatim without attribution.
- **C2** — **The bypass middle is XYZ(D65), and BOTH the `SrgbD65` presentation branch and the `ProPhotoD50` editing branch are in scope.** The OKLab block runs *before* the sRGB/ProPhoto split, entirely in camera space: `cam2xyz`/`xyz2cam` are anchored on D65 (camera ↔ XYZ(D65)) using the D65-anchored camera matrix, so the round trip lands in genuine XYZ(D65) for either destination. After the round trip the (rolled-off) camera buffer is mapped to the destination primaries by the existing space-dependent `cam2rgb`, so ProPhotoD50 output is desaturated exactly as sRGB is. **No D50↔D65 Bradford bridge is needed** — the previous deferred note (apply Bradford on entry/exit for the ProPhoto branch) is *retracted*: it only arose if one built the maps from the D50-resolved `xyz2cam`, which we no longer do (see R1, C3, Q2).
- **C3** — The bypass must use `pinv(xyz2cam)` / `xyz2cam` (original factors), **never** decompose `cam2rgb` (the `normalize` diagonal `D⁻¹` is entangled, `matrix.rs:29`).
- **C4** — No new large allocation. Reuse the existing in-place pattern (the `ThreeColor` arm already flattens zero-copy into `RawlerImageDeveloped`). The block operates in place on the camera vector before `cam2rgb`.
- **C5** — The working-space buffer remains **unclamped** after the block; the sRGB clamp at `bound::encode_srgb` stays the single clip point. The block does not clip and does not clamp.
- **C6** — Default state is **on** (default-on rollout): the pipeline runs the compression unless Kotlin later passes `oklab_highlight_compress = false`, which restores the exact bit-for-bit identity pass-through (R4, AC1). The compression curve itself (the `OKLAB_KNEE_*` constants in `calibrate.rs`) is a tunable constant — the exact knee is left open per Q1 and pending real-image validation, but the mechanism, the identity round-trip, and the branch scope are fixed.

## Acceptance Criteria

- **AC1** — A new `DevelopParams` flag toggles the bypass; with it off, the full image (all working spaces) is byte-for-byte identical to the current output (no matrix change, no extra allocation).
- **AC2** — With the relevant branch gate on and a non-zero strength, a synthetic non-neutral near-clipped highlight (e.g. camera RGB that maps to `sRGB ≈ [1.00, 0.78, 1.00]`) renders with measurably **lower chroma / closer-to-neutral hue** after the sRGB clamp than without the block, **on whichever branch is enabled** (default: sRGB on, ProPhoto off). Both branches consume the same post-roll-off camera buffer before their respective `cam2rgb`, so when the ProPhoto gate is on it is desaturated identically.
- **AC3** — A neutral saturated highlight (`camera (k,k,k)`, k≥white level) is preserved as neutral (within rounding) by the block — no introduced hue shift.
- **AC4** — With the flag on and strength 0, output equals the flag-off output (R4 invariant), verified by a unit test comparing both paths on a fixed fixture.
- **AC5** — The block is proven per-pixel parallel (rayon) with no cross-pixel state; a `cargo test` exists exercising `cam2xyz·xyz2cam ≈ I` and the OKLab round-trip `XYZ→OKLab→XYZ ≈ I`.
- **AC6** — The `ProPhotoD50` branch shares the same camera-space bypass as `SrgbD65`; with `oklab_highlight_compress_prophoto` on, that branch gets the same OKLab highlight roll-off (chroma reduced, hue preserved) before its `cam2rgb` maps it to ProPhoto D50. No Bradford bridge is required (C2). **Default off** — the editing branch is the exact identity pass-through unless Kotlin enables it (AC1/AC4).

## Impacted Modules

- `app/src/binding/rust/rawler_fotlab/src/calibrate.rs` — insertion point (after WB, before `cam2rgb`); new `oklab_highlight_compress` function + `cam2xyz = pseudo_inverse(xyz2cam)` precompute.
- `app/src/binding/rust/rawler_fotlab/src/develop.rs` — `DevelopParams` gains the enable/strength field (default off); passed into `calibrate`.
- `app/src/binding/rust/rawler_fotlab/src/bound.rs` — unchanged (still the only clip point); may add a test fixture.
- `external/colour/colour/models/oklab.py` — reference constants (D65-in, do not modify).
- `external/RawTherapee/rtengine/color.cc:1980` (`xyz2oklab`, D50-in) — alternative reference if the D50 branch is taken without a Bradford bridge.
- `rules/REVIEW/detail/FOTLAB-RAWLER-000018.md` — the root-cause review this design resolves.
- `log/highlight_cast_sim.py`, `log/partial_saturation_chain_sim.py`, `log/cam2srgb_attribution_sim.py` — numpy prototyping ground for the compression curve before Rust lands.

## Open Questions

- **Q1** — Exact compression curve. Candidate: `C' = C · f(L)` where `f(L)` is a smoothstep rolling from 1 at some `L₀` to 0 at/after `L=1`, optionally with an accompanying `L'` shoulder. Also whether to roll off `L` (tonemap super-whites) or chroma only. Prototype in `log/` and validate against the magenta fixture before fixing.
- **Q2** — **Resolved: scope is BOTH the `SrgbD65` presentation branch and the `ProPhotoD50` editing branch.** The OKLab block runs before the sRGB/ProPhoto split in camera space (R1, C2), so both destinations receive the identical roll-off; preview and edit now agree on highlight behaviour. The earlier concern about rawalchemy expecting an untouched ProPhoto buffer is moot: the roll-off is a *desaturation of near-clipped highlights*, fully within the camera→working mapping and indistinguishable in intent from any other colour-matrix choice — rawalchemy still receives a valid linear ProPhoto-D50 buffer. No D50↔D65 Bradford bridge is needed.
- **Q3** — Strength exposure: per-`DevelopParams` constant vs a user-facing Studio control. Default off regardless; UI surface is a follow-up.
- **Q4** — Performance alternative: when the block is *on*, the camera→XYZ(D65)→camera round-trip plus the later `cam2rgb` (which re-crosses XYZ(D65)) is two redundant fixed 3×3 pairs. A fused `camera→OKLab→…→XYZ(D65)→working` could drop the second pair, but it breaks the clean camera-space IO / short-circuit contract (R1/G3). Keep modular unless profiling shows it matters.

## Change History

- 2026-10-04 — Initial draft. Specified a short-circuitable, camera-space-in/camera-space-out OKLab highlight-chroma compression bypass inserted in `calibrate` between white balance and the baked `cam2rgb` multiply. Fixed the four internal transforms (rebuild `cam2xyz = pinv(xyz2cam)` from the original factors — never decompose `cam2rgb` because of the entangled `normalize` diagonal; standard Ottosson OKLab D65 matrices from `external/colour`; original `xyz2cam` back to camera). Recorded the white-point constraint (bypass middle is D65; ProPhotoD50 branch needs a D50↔D65 Bradford bridge, C2), the short-circuit invariant (off ≡ on-with-zero-strength ≡ identity, R4), and the no-new-allocation / unclamped-buffer constraints. Linked root cause to `FOTLAB-RAWLER-000018` and the OKLab survey to `RAWTRP-SURVEY-000002`; left the compression curve, branch scope, and UI exposure as open questions.
- 2026-10-04 (addendum) — Scope & symmetry clarifications. (1) Bypass enabled **only for the `SrgbD65` presentation branch**; `ProPhotoD50` editing branch temporarily out of scope and passes through unchanged (updated R1 diagram, C2, AC2, AC6, Q2). (2) Made the OKLab forward/backward transform an explicit order-symmetric contract: forward `M1 → ∛ → M2`, backward `M2⁻¹ → (·)³ → M1⁻¹`, with the per-channel cube root sitting between the two linear maps in both directions and `M1⁻¹`/`M2⁻¹` being the exact matrix inverses of the forward constants; guarantees `XYZ → OKLab → XYZ = I`.
- 2026-10-04 (addendum 2) — Default-on decision. The enable gate `DevelopParams::oklab_highlight_compress` defaults to **true** (R3, C6 updated): the pipeline runs the compression unless Kotlin later passes `false`. Kotlin does not pass the field yet, so the Rust default applies. The compression knee constants are a tunable constant pending real-image validation (Q1).
- 2026-10-04 (addendum 3) — Enable OKLab highlight roll-off on the `ProPhotoD50` editing branch too. The branch split is moved *after* the OKLab block in `calibrate`: `bypass_active = oklab_compress` (no longer gated by `space == SrgbD65`), and the `cam2xyz`/`xyz2cam` maps are anchored on D65 (camera ↔ XYZ(D65)) using the D65-anchored camera matrix instead of the space-dependent `cam2rgb`. Because the round trip is camera-space in/out, the ProPhotoD50 output is affected exactly as sRGB — **no D50↔D65 Bradford bridge required** (retracts the C2 deferred note). Short-circuit switch and Kotlin param pass-through preserved; off-knee behaviour is bit-identical to the previous no-OKLab ProPhoto path.
