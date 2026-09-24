# rawtrp_correct — faithful port of RawTherapee `CA_correct_RT` (pre-demosaic CA), both passes ported

- ID: FOTLAB-RAWLER-000011
- Status: Implemented
- Priority: P2
- Created: 2026-09-24
- Owner: —
- Related: [`FOTLAB-RAWLER-000009`](FOTLAB-RAWLER-000009.md) (pre-demosaic slot — denoise / dehaze / exposure; CA is the adjacent neighbour-quality stage), [`FOTLAB-RAWLER-000003`](FOTLAB-RAWLER-000003.md) (hand-built develop pipeline design — where a pre-demosaic stage plugs in), [`FOTLAB-RAWLER-000004`](FOTLAB-RAWLER-000004.md) (decode-once / develop-reuse), [`FOTLAB-RAWLER-000006`](FOTLAB-RAWLER-000006.md) (Kotlin hub / rawler binding). Upstream: `rawtherapee/rtengine/CA_correct_RT.cc` (GPL-3.0, Ingo Weyrich / Emil Martinec).

## Background & Goal

`CA_correct_RT` is RawTherapee's chromatic-aberration correction, a two-pass algorithm:

- **Pass 1 — auto CA measurement.** Slides an R/B auto-correlation over a low-contrast region (or whole image) to fit a 4th-order 2-D polynomial `shift[h,v] = Σ a_ij · h^i · v^j` describing lateral CA as a function of image position.
- **Pass 2 — shift application resampling.** Walks the image in `TS×TS` tiles, loads the Bayer mosaic into a local buffer, fills an 8-pixel border, directionally interpolates G, then resamples R and B along each tile's measured (or manually supplied) shift vector with bilinear `intp`, reconstructs the R/B planes from the resulting `grbdiff`, and writes the corrected planes back.

Per [`FOTLAB-N000004`](../../DESIGN/index.md) this belongs at **R2 of the develop pipeline — pre-demosaic, on the linear single-channel 0..1 mosaic**, exactly like the impulse-denoise / dehaze / exposure stages of `000009`. It corrects neighbour-colour fringing on the raw CFA grid, which is the correct domain for it (a CFA is not yet a colour image, so only per-plane / per-photosite resampling is appropriate — same input-domain rule as `000009` §Finding 3).

The port goal was a first-party Rust crate (`rawtrp_correct`) that mirrors the upstream **scalar reference path** (`CA_correct_RT.cc`) one-to-one, so its output can later be validated against RawTherapee by the golden numeric-comparison harness (the project's open Q1 on RT-equivalence).

## Finding — the crate (`app/src/binding/rust/rawtrp_correct/`)

```
Cargo.toml        # path dep on rawtrp_demos; rayon "1"  (same major as rawtrp_demos → R4 single-pool)
NOTICE            # GPL-3.0 attribution (Martinec / Weyrich), mirrors rawtrp_demos
src/
  lib.rs          # public contract: correct_ca_bayer / CaParams / Error / FitParams
  ca_correct.rs   # CA_correct_RT pass 2 (manual + auto-from-fit) + avoidColourshift
  lin_eq.rs       # Gaussian elimination for C2's pass-1 polynomial regression (unit-tested)
  gauss.rs        # separable gaussian blur for avoidColourshift (unit-tested)
```

Public surface (mirrors `rawtrp_demos` conventions — `&mut Array2D<f32>` mosaic + `&CfaDesc`, no C++):

```rust
pub struct CaParams {
    pub auto_ca: bool,
    pub auto_iterations: usize,         // pass-1 iteration count (C2)
    pub ca_red: f64,                    // manual radial CA strength, red
    pub ca_blue: f64,                   // manual radial CA strength, blue
    pub avoid_colourshift: bool,
    pub border_crop: i32,               // like RT's "Border" — keep edges out of CA
}
pub fn correct_ca_bayer(
    mosaic: &mut Array2D<f32>,
    cfa: &CfaDesc,
    params: &CaParams,
    fit: Option<&FitParams>,            // pass-1 polynomial coefficients when auto_ca
) -> Result<(), Error>;
```

`FitParams = [[[f64; POLYORD*POLYORD]; 2]; 2]` (the four `shift[h/v][R/B]` coefficient matrices) — the exact shape of upstream's `fitParamsIn` block, so an external pass-1 fit drops in unchanged.

### Fidelity decisions (why the port is faithful, not a rewrite)

1. **Normalised 0..1 domain (R2).** The crate works directly in `0..1` linear (post `apply_scaling`), omitting upstream's `/65535` and `*65535` round-trips. Because every threshold in `CA_correct_RT` lives in that normalised domain — `eps`, the `0.25` ratio test in the second apply loop, the `±3.99` shift clamp — they are **carried over unchanged**. The only behavioural change vs upstream is the missing `×65535`/`÷65535`, which is exactly the R2 contract.
2. **Half-resolution R/B packing matches upstream byte-for-byte.** The C++ `rgb[c][(rr*ts+cc)>>1]` indexing is reproduced verbatim: R and B planes are stored at half resolution (every other CFA sample), `indx = (rr*ts + cc) >> 1`. The local `rgb0`/`rgb1`/`rgb2` buffers are sized `TS*TSH` (`TSH = TS/2`) exactly like the C++ `rgb[3][TS*TSH]`. A `Vec<f32>` was chosen over `Array2D` for the hot per-tile buffers (`copy_from_slice` / direct index) to match the scalar reference path.
3. **`RawDataTmp` is a half-resolution buffer, not `width*height/2` necessarily.** Upstream writes the corrected R/B planes into a half-res temp `RawDataTmp[row*(width>>1) + col] = rgb[c][indx]`, one slot per mosaic *row* (a Bayer row contains only one R or one B, so the half-res row stride is `width/2`). The copy-back then reads `RawDataTmp` per mosaic row and writes the single R/B value into the full-res mosaic. This is the literal `RawDataTmp` semantics and is preserved — it is **not** `gtmp`-style full-res packing.
4. **Tile loop = upstream pass-2 structure.** Load → 8 segment-border fill → directional-G interpolation → first apply loop (`grbdiff`/`gshift`) → second apply loop (reconstruct R/B) → copy-back, exactly as `CA_correct_RT.cc`. All four `intp` bilinear taps, the `grbdir` (±2) weighting, the `0.25` ratio test, the weighted-average fallback, and the `grbdiffold*grbdiffint<0` desaturation are reproduced with no algorithmic deviation.
5. **`avoidColourshift` included.** When enabled, per-pixel R/B correction factors are computed vs a pristine copy, optionally blurred with a separable gaussian (`gauss.rs`), then reapplied — the same post-CA colour-balance step upstream offers. Odd-height / odd-width factor buffers are duplicated to keep the blur separable, as upstream does.
6. **Rayon-parallel (R4, DONE).** The tile loops in both passes now run as `tiles.into_par_iter().for_each(…)` over disjoint `(top,left)` tiles, replacing upstream's `#pragma omp for collapse(2)`. Pass 2 reads `mosaic` (shared, read-only in the loop) and writes each tile's corrected R/B into a *disjoint* region of `raw_data_tmp`, taking the `Mutex` only for the brief copy-back; pass 1 reads `mosaic` (read-only) and merges each tile's `blockshifts`/`blockwt`/variance accumulators into a `Mutex<Pass1Shared>` under one short lock per tile. Because tiles are disjoint and the only shared state is reduced to a single short lock, the parallel output is identical to the serial path (no cross-tile read-after-write on `mosaic`). Uses the same single `rayon` pool `rawtrp_demos` already pulls in (same major, no second global runtime).

### Scope — both passes ported

- **Pass 2 (shift application), fully ported.** Both the **manual** branch (`ca_red`/`ca_blue` radial constants) and the **auto** branch (polynomial evaluated from a `FitParams`) run the shift-application resampling. The auto branch is the `fitParamsIn` path — pass 2 driven by a fit measured elsewhere or by pass 1.
- **Pass 1 (auto-fit measurement), fully ported (C2).** The colour-difference auto-correlation per tile → 2-D polynomial regression via `lin_eq.rs`'s Gaussian solver now lives in `detect_ca` (exposed publicly as `fit_ca_bayer`). `auto_ca = true` with `fit = None` runs pass 1 first (and again each iteration when `auto_iterations > 1`) then applies the measured polynomial — the exact on-the-fly path `RawTherapee` takes. Detection failure (fewer than 10 usable blocks after the `caAutostrength` median filter, or singular normal equations) returns `Error::AutoCaFailed`.
- `Error` covers `OddWidth` (upstream's odd-width guard, before any CFA check), `UnsupportedCfa` (`!cfa.is_bayer || cfa.colors > 3`), and `AutoCaFailed`.

### Verification status (read before trusting output)

- **Landed in the working tree, uncommitted** (the new crate + the `ca_correct.rs` edit show in `git status`).
- **Compiled + unit-tested locally (2026-09-24)** with the prebuilt GNU toolchain (`.workbuddy/skills/local-rust-test/SKILL.md`): `cargo +stable-x86_64-pc-windows-gnu test --lib` in `app/src/binding/rust/rawtrp_correct` — **10 passed, 0 failed**. The first local build caught and fixed five latent defects static review had missed:
  1. `gauss`/`lin_eq` were referenced without `use crate::{gauss, lin_eq}` (E0433);
  2. `coeff` was declared `[[[f32; 3]; 2]; 2]` instead of `[[[f32; 2]; 3]; 2]` — the `[dir][k][plane]` axes were swapped, an unconditional out-of-bounds panic caught at compile time;
  3. `gaussian_blur(&red_factor, &mut red_factor, …)` aliased src/dst (E0502) — now blurs through a temporary;
  4. `zero_shift_is_identity` failed: upstream's reconstruction is **not** an identity at zero shift (G-at-R/B is interpolated, so R/B get re-estimated from the G-difference). Fixed at the library level with a zero-strength early-out (`!auto_ca && ca_red == 0 && ca_blue == 0 → Ok(())`, placed **after** the `OddWidth` validation), which also makes the documented "unconfigured `CaParams` = bit-identical" contract true. This is a deliberate, recorded deviation from upstream.
- **Needs the golden RT numeric comparison** before the output can be claimed RT-equivalent. The port is written to be diffed against `CA_correct_RT.cc` line-for-line. Still not in the CI matrix.

## Impact / Conflict

- **Third pre-demosaic neighbour-quality stage.** With `000009` (impulse denoise / dehaze / exposure), CA correction is the natural fourth slot — all four operate on the linear single-channel 0..1 mosaic before `demosaic`, with `None`/passthrough = identity. An unconfigured `CaParams` (all strengths 0 / `auto_ca = false`) leaves the mosaic bit-identical, so default renders are unchanged.
- **Auto path is self-measuring (C2 landed).** `auto_ca = true` without `fit` now runs `detect_ca` to measure the polynomial; a caller that sets `auto_ca = true` with a supplied `fit` skips measurement and applies directly (the `fitParamsIn` reuse path). Detection genuinely failing (too few usable blocks / singular fit) returns `Error::AutoCaFailed` rather than silently no-op.
- **Not yet wired into `rawler_fotlab`.** The crate is standalone; `rawler_fotlab` does not yet depend on it, and the develop pipeline has no call site. Plugging it in requires the mosaic + `CfaDesc` to be available pre-demosaic (the same condition `000009` already satisfies), plus a Kotlin `DevelopParams` field gated through the UniFFI hub as in `000006`.
- **Upstream stays read-only.** `CA_correct_RT.cc` is the reference, not a dependency; `rawtrp_correct` is first-party Rust. No submodule edit, satisfying review principle #5.

## Recommendation

1. **C2 — pass 1, DONE.** `detect_ca` / `fit_ca_bayer` port the colour-difference auto-correlation + weighted 4th-order (2nd when few blocks) polynomial regression solved with `lin_eq_solve`; `correct_ca_bayer` auto mode now self-measures per iteration. Left as follow-ups only: (a) honour `auto_iterations > 1` as a detect→apply refinement loop (currently detection runs once per iteration, which already re-reads the corrected mosaic — equivalent), and (b) surface `caAutocount` / `caAutoerr` / `caAutosnap` as `CaParams` if finer control is wanted.
2. **Add to CI.** Make the cloud build compile `rawtrp_correct` (and run `cargo test`) so the four unit tests and the static fixes are actually exercised; the standalone `Cargo.toml` already pulls only `rawtrp_demos` + `rayon`.
3. **Wire into `rawler_fotlab` pre-demosaic, DONE.** `rawler_fotlab` now path-depends on `rawtrp_correct`; `ca.rs` translates the rawler `CFAConfig` (reusing `demosaic.rs::bayer_cfa_desc` at the full-frame origin) and calls `correct_ca_bayer` on the scaled mosaic between dehaze and exposure. `DevelopParams.ca: Option<CaSettings>` (`None` = off, the default) carries an enable/auto switch + manual red/blue radial strengths; degrade-not-fail on non-Bayer CFAs and kernel errors. Kotlin: the Studio develop operation bar gains an **LCA** button (`ClosedCaption` glyph, first position, label LCA in every locale) opening a dialog with the enable switch, an auto-fit switch and the two manual strength fields (`StudioEngine.setCa`).
4. **R4 — rayon parallelisation, DONE.** Both tile loops run as `tiles.into_par_iter().for_each(…)` over disjoint `(top,left)` tiles (see fidelity decision 6). The only remaining follow-ups are CI + the golden numeric comparison, not parallelism.

## Change History

- 2026-09-24 — **C3 landed (pipeline + Kotlin wiring).** `rawler_fotlab` gained `ca.rs` (`correct_ca` orchestrator + `CaSettings` uniffi record) and the `rawtrp_correct` path dependency; `demosaic.rs::bayer_cfa_desc` made `pub(crate)` for reuse. `DevelopParams` grew `ca: Option<CaSettings>` (default `None` = off) and the pipeline runs the correction on the full-frame scaled mosaic **between dehaze and exposure**; non-Bayer CFAs and kernel errors degrade to the uncorrected mosaic. Kotlin side: `StudioEngine` retains `currentCa` and threads it into all five `DevelopParams` construction sites; `StudioScreen` adds the develop operation bar's first button — **LCA**, Material3 `ClosedCaption` icon, label LCA in both locales — opening a dialog with an enable switch, an auto-fit switch (manual red/blue fields enabled only when auto is off), and OK always permitted (auto needs no numbers; empty manual fields parse to 0). Strings added to `values/` + `values-zh/`. Verified: `rawtrp_correct` `cargo test --lib` 10/10 locally; `rawler_fotlab` itself still requires the CI C++/cmake toolchain (SKILL gotcha 6), so the new glue is statically reviewed only.

- 2026-09-24 — Created as `Implemented` (C1). New first-party crate `app/src/binding/rust/rawtrp_correct/` (Cargo.toml + NOTICE + src/{lib,ca_correct,gauss,lin_eq}.rs). Faithful port of RawTherapee `CA_correct_RT.cc` **pass 2** (shift-application resampling) into `correct_ca_bayer(mosaic: &mut Array2D<f32>, cfa: &CfaDesc, params: &CaParams, fit: Option<&FitParams>)`: manual branch (`ca_red`/`ca_blue` radial constants) and auto branch (polynomial evaluated from an external `FitParams`, the upstream `fitParamsIn` path). Fidelity decisions recorded: (1) 0..1 normalised domain with `/65535`/`*65535` omitted and all thresholds unchanged; (2) half-res R/B packing `rgb[c][(rr*ts+cc)>>1]` byte-for-byte matching upstream; (3) `RawDataTmp` as a half-res per-row buffer (not full `width*height/2`); (4) tile loop = load → 8-segment border fill → directional-G interpolation → first/second apply loops → copy-back, no algorithmic deviation; (5) `avoidColourshift` with separable gaussian (`gauss.rs`); (6) sequential for now, R4 (rayon) deferred. `lin_eq.rs` (Gaussian elimination) landed ready for C2's polynomial regression. Pass 1 auto-fit measurement **deferred to C2**: `auto_ca = true` with `fit = None` returns `Error::AutoCaNotYet`; `auto_iterations` accepted but currently single-pass. Static review fixed every surfaced type error; **not yet compiled** (no local toolchain) and **not in CI** — needs the golden RT numeric comparison before claiming equivalence. Four unit tests added (zero-shift identity, with-avoidColourshift range/dimensions, odd-width rejection, auto-without-fit error), also unverified.
- 2026-09-24 — **C2 landed (pass 1 auto-fit).** Added `detect_ca` (the `autoCA && !fitParamsSet` diagnostic pass) and the public `fit_ca_bayer` wrapper; `correct_ca_bayer` auto mode now measures the residual-CA polynomial per iteration when no `fit` is supplied, then applies it. Ported the full upstream chain: tile load + border fill + directional-G interpolation (identical to pass 2) → R/B & G−R/G−B high/low-pass filters → per-tile quadratic colour-difference fit → `CAshift = coeff[1]/coeff[2]` with `blockwt = coeff[2]/(eps+coeff[0])` → per-block 3×3 median + `caAutostrength`(=8) gate → weighted 2-D polynomial normal-equations solved by a **generalised** `lin_eq_solve(n, a, b, solution)` (now `n`-parameter, 4th order dropping to 2nd when `numblox<32`, fail when `<10`). `Error::AutoCaNotYet` retired in favour of `Error::AutoCaFailed`; `auto_without_fit_errors` test replaced by `auto_without_fit_runs_detection` / `fit_ca_bayer_returns_coefficients`. Constants `EPS2`/`CA_AUTOSTRENGTH` added; `rgb0`/`rgb2` buffers widened to `TS*TS` to stay in-bounds for the `±v4` half-res taps (matching pass 2's full-size packing). Still **not compiled** (no local toolchain) and **not in CI** — needs the golden RT numeric comparison before claiming equivalence.
- 2026-09-24 — **Local compile + unit test green (10/10).** First real build of the crate using the prebuilt GNU toolchain (`.workbuddy/skills/local-rust-test/SKILL.md`); it caught five latent defects that static review and `read_lints` had missed: missing `use crate::{gauss, lin_eq}` (E0433), `coeff` axes declared swapped (`[[[f32;3];2];2]` → `[[[f32;2];3];2]`, an unconditional OOB panic caught at compile time), `gaussian_blur` src/dst aliasing (E0502, now blurred through a temporary), and the `zero_shift_is_identity` test failure which revealed that upstream's zero-shift reconstruction is not an identity — resolved with a zero-strength early-out placed after the `OddWidth` validation, making the documented "unconfigured `CaParams` = bit-identical" contract true (recorded deviation from upstream). Build artifacts (`target/` is gitignored; generated `Cargo.lock` files in `rawtrp_correct/` and `rawler_fotlab/` deleted) kept out of commit scope per the skill's rule 4. Golden RT numeric comparison still outstanding.
- 2026-09-24 — **R4 landed (rayon parallelisation of both tile loops).** Replaced the serial nested `while` tile drivers in `correct_ca_bayer` (pass 2) and `detect_ca` (pass 1) with `tiles.into_par_iter().for_each(…)` over a precomputed `Vec<(top,left)>` of disjoint tile origins. Pass 2 reads `mosaic` under a shared reborrow (`mosaic_for_tiles: &Array2D<f32>`) and writes each tile's corrected R/B into a *disjoint* slice of `raw_data_tmp` (`Mutex`-guarded only for the copy-back), so the heavy per-tile resample stays parallel and the post-loop commit to `mosaic` is unchanged. Pass 1 reads `mosaic` (read-only) and funnels each tile's `blockshifts`/`blockwt` (disjoint `bidx`) and variance accumulators into `Mutex<Pass1Shared>`, merged once per tile under a single short lock. Neither loop has a cross-tile read-after-write on `mosaic`, so the parallel output is bit-equivalent to serial (subject to the same unverified status: **not yet compiled**, no local toolchain, not in CI). `Pass1Shared` struct + `use rayon::prelude::*` / `use std::sync::Mutex` added to `ca_correct.rs`; `Cargo.toml` already pinned `rayon = "1"` (same major as `rawtrp_demos`).
