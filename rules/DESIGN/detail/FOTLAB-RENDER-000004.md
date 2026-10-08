# Shearlet-Domain Chromatic Aberration Correction — Unified LCA + ACA, Post-Demosaic

- ID: FOTLAB-RENDER-000004
- Status: Draft
- Priority: P2
- Created: 2026-10-08
- Owner: —
- Related: FOTLAB-RENDER-000003 (pre-demosaic ACA / purple-fringe `correct_loca_bayer`), FOTLAB-RAWLER-000011 (pre-demosaic LCA `CA_correct_RT` port), RAWTRP-SURVEY-000005 / -000006 (public ACA / defringe survey), `log/papers/chromatic_aberration_paper_summary.md` (Li & Jin, ACCV 2020)

## Background & Goal

The two chromatic-aberration (CA) stages we currently ship are both **pre-demosaic CFA-mosaic** passes in `rawtrp_correct`:

- `ca_correction_lca.rs` — `correct_ca_bayer`, the RawTherapee `CA_correct_RT` radial shift (Lateral CA, geometric R/B-vs-G misalignment).
- `ca_correction_aca.rs` — `correct_loca_bayer`, the RapidRAW raise-G purple/green-fringe repair (Axial CA / longitudinal fringe, edge-local only).

Both are deliberately cheap, local, and CFA-native: LCA rewrites only the R/B planes by a radial/measured shift; ACA only raises/lowers the G plane near high-contrast edges. They are **fast but weak** — local-gradient edge matching degrades on complex texture and severe CA, and ACA in particular only attacks the *fringe*, never the *blur* that ACA (axial defocus) actually produces.

This item specifies a **third, heavier CA pass** ported from Li & Jin, *Chromatic Aberration Correction Using Cross-Channel Prior in Shearlet Domain* (ACCV 2020, [`log/papers/chromatic_aberration_paper_summary.md`](../../../log/papers/chromatic_aberration_paper_summary.md)). It corrects **both** axes in one unified pipeline:

1. **LCA via the CC-SD prior** — a shearlet-domain (multi-scale, multi-directional) cross-channel prior that aligns the R and B textures to the sharpest G channel, instead of relying on horizontal/vertical gradients alone (the limitation of Heide et al.'s original cross-channel prior).
2. **ACA via wave-propagation PSF estimation + deconvolution** — a Seidel-polynomial PSF model (defocus `W_d` + spherical `W₀₄₀`) built into a `(W_d, W₀₄₀) → R₀` LUT, used to estimate the blur kernel from the image and then deconvolve.

**Key scope decisions from the user (2026-10-08):**

- The shearlet pass is **allowed to run after demosaic**, on the **full 3-channel RGB** image — it does *not* inherit the CFA-mosaic contract of `correct_ca_bayer` / `correct_loca_bayer`.
- Because the shearlet pipeline is **unified** (it corrects LCA and ACA together, internally sequencing LCA → ACA per the paper), we **do not need to worry about ordering it relative to** the existing pre-demosaic `correct_ca_bayer` (LCA) and `correct_loca_bayer` (ACA) stages. It is an independent, self-contained post-demosaic path.

Goal: a research-track, higher-quality CA correction that beats the local-gradient methods on severe/complex CA, offered as a *candidate* unified path alongside (not necessarily replacing) the lightweight pre-demosaic stages.

## Requirement

1. **Post-demosaic, RGB-domain contract.** The kernel takes a demosaiced, **3-channel `f32` RGB** image (full resolution, already demosaiced) and returns a corrected 3-channel RGB image. It does **not** take a single-channel CFA mosaic and does **not** require a `CfaDesc`. This is a deliberate deviation from the `rawtrp_correct` crate's current pre-demosaic CFA contract (see Constraints / Open Questions).
2. **Unified LCA + ACA pipeline.** A single entry point (e.g. `correct_ca_shearlet`) runs the full paper flow:
   * **Stage A — LCA (CC-SD prior):** shearlet-transform R, G, B; minimize the cross-channel shearlet-difference prior `Σᵢ ||Tᵢ(x_G) − Tᵢ(x)||²₂` aligning R/B to G; solve with ADMM (auxiliary `fⱼ` for the shearlet-`ℓ₁` term, `u` for the data term). Per the paper the x-subproblem is FFT/IFFT-closed-form, the `fⱼ`-subproblem is 1-D soft-threshold, the `u`-subproblem uses He's deviation-constrained projection.
   * **Stage B — ACA PSF estimation:** from edge patches compute the gradient ratio `R₀ = ∇y(0,0) / ∇y₁(0,0)`; look up the Seidel parameters `(W_d, W₀₄₀)` in the pre-built LUT; synthesize the wave-propagation PSF `h_WP` via the pupil/FFT relation.
   * **Stage C — ACA deconvolution:** deconvolve the LCA-corrected image with the estimated PSF (the same ADMM/regularized-deconvolution core can reuse Stage A's machinery).
3. **Self-contained ordering.** Internally the module sequences LCA (Stage A) → ACA (Stages B–C), matching the paper's Figure 1. It must **not** read or depend on the output of `correct_ca_bayer` / `correct_loca_bayer`, and its correctness must not depend on whether those pre-demosaic stages are enabled.
4. **Passable parameters.** Expose at least: shearlet levels (paper default 3), ADMM penalties `α, β₁, β₂` (paper: `α = 10×β₁`, `β₁ = β₂ = 1` for sim; `α = 0.1×β₁` for real), the ACA `strength`/enabled switch, and a master `enabled` that short-circuits the whole pass (returns the input unchanged). The `(W_d, W₀₄₀)` LUT build range (`W_d: −3..3`, `W₀₄₀: 0.1..2`, step 0.05) is a build-time/const parameter.
5. **Graceful degradation.** Non-RGB / wrong-channel-count input returns `Error` and the orchestration passes the image through unchanged (degrade, not fail) — consistent with the existing CA stages.

## Constraints

- **Domain is post-demosaic.** Unlike `correct_ca_bayer` / `correct_loca_bayer` (which run pre-WB on the raw-linear mosaic), this pass runs **after** `develop`'s demosaic and (normally) after `calibrate`/WB. Its thresholds and weights therefore live in **display/post-WB RGB** space, not raw-linear pre-WB space. This is the inverse of the ACA purple-fringe tradeoff (FOTLAB-RENDER-000003) and must be documented as such.
- **Crate-contract deviation (rawtrp_correct).** The `rawtrp_correct` crate doc currently states a pre-demosaic CFA-mosaic contract (single-channel array + `CfaDesc`). A post-demosaic 3-channel-RGB module breaks that contract. Options (Open Question): (a) relax the crate doc and host the module here anyway (the user's file name `ca_correction_shearlet.rs` implies this), or (b) move it to a post-demosaic pipeline crate. The design below assumes (a) for now, with the doc updated to say "pre-demosaic CFA passes *plus* an optional post-demosaic RGB pass".
- **Shearlet transform is new infrastructure.** No ShearLab/3D equivalent exists in Rust here. The FFT-based shearlet (`SHⱼ(x) = F⁻¹(H̃ⱼ·X)`) must be implemented or vendored; this pulls in an FFT dependency (e.g. `rustfft`) and a directional multi-scale filter-bank construction. This is the single largest implementation unknown.
- **Computational cost.** The paper reports ~860 s per 720×720 image in MATLAB on an i7-9700 / 8 GB. A faithful ADMM port will be far heavier than the O(W·H) local passes. Acceptable only as an opt-in, possibly preview-only, path; must not gate the default develop pipeline. Parallelism (rayon over rows/tiles) and FFT planning are mandatory design points, not optimizations.
- **Memory.** Stage C deconvolution allocates FFT buffers at full resolution; for a 100 MP image this is multiple × W·H×C `f32` buffers. Row/ tile striping is a required design consideration, mirroring the ACA delta-buffer note in FOTLAB-RENDER-000003.
- **Independent of the pre-demosaic stages.** The module never reads `detect_ca` / `fit_ca_bayer` / `correct_loca_bayer` output; it recomputes everything from the demosaiced RGB.

## Acceptance Criteria

- A demosaiced RGB image synthesized with (a) a known R/B-vs-G geometric shift (LCA) and (b) a known channel-dependent defocus blur (ACA) has **both** artifacts reduced after `correct_ca_shearlet`, measured by PSNR/SSIM against the ground-truth sharp image (the paper's simulation protocol: ≥2 dB PSNR gain over the uncorrected / CC-prior baselines).
- With `enabled == false` (or the FFI `ShearletCaSettings == None`) the pass is an exact identity (output byte-equal to input).
- Stage A alone (ACA disabled) reduces the LCA shift; Stage C alone (LCA disabled) reduces the ACA blur — i.e. the two axes are independently switchable per the paper's two contributions.
- A wrong-channel-count / non-RGB input returns `Error` and the orchestration passes through unchanged (degrade, not fail).
- No `Cargo.lock` or personal-info strings are added by this change; the new FFT dependency is declared in the crate `Cargo.toml` (not committed as a lockfile churn beyond the crate).
- The module does not read or require the pre-demosaic LCA/ACA outputs; with those stages disabled, `correct_ca_shearlet` still corrects both axes (proves independence).

## Impacted Modules

- `app/src/binding/rust/rawtrp_correct/src/ca_correction_shearlet.rs` (new) — `correct_ca_shearlet`, `ShearletCaParams`, `Error`; internal shearlet transform, ADMM solver, Seidel-LUT PSF estimator, deconvolution.
- `app/src/binding/rust/rawtrp_correct/src/lib.rs` — `pub mod ca_correction_shearlet;` + `pub use`; **and** update the crate-level `//!` doc to acknowledge the post-demosaic RGB pass alongside the pre-demosaic CFA passes.
- `app/src/binding/rust/rawtrp_correct/Cargo.toml` — FFT dependency (`rustfft` or equivalent).
- `app/src/binding/rust/rawler_fotlab/src/shearlet.rs` (new) or extension of the existing CA FFI — `ShearletCaSettings` (uniffi Record) + `correct_ca_shearlet` orchestration.
- `app/src/binding/rust/rawler_fotlab/src/develop.rs` — call the shearlet pass **after** demosaic/WB (post-demosaic path), independently of the existing pre-demosaic `correct_ca` / `correct_loca` calls.
- `rules/DESIGN/index.md` — this row.

## Open Questions

- **Hosting / crate contract.** Keep `ca_correction_shearlet.rs` inside `rawtrp_correct` (relaxing its CFA contract) or move it to a post-demosaic pipeline crate? The user's file name implies the former; the crate's `//!` doc must be reconciled either way.
- **Shearlet library.** Implement the FFT-based shearlet filter bank from scratch, or vendor/adapt an existing Rust crate? Affects the LUT/ADMM fidelity vs. effort.
- **Double correction.** When the shearlet (unified) path is enabled, should the pre-demosaic `correct_ca_bayer` (LCA) and `correct_loca_bayer` (ACA) stages be auto-disabled to avoid applying CA correction twice? Default behaviour (both on vs. shearlet-only) is unset.
- **Domain precision.** Post-demosaic usually means post-WB RGB; the paper's priors are defined on the (possibly still-linear) RGB. Confirm whether the pass should run on linear-RGB pre-tonemap or on the final display RGB, and whether the `α/β` defaults need re-tuning off the paper's MATLAB values.
- **Performance gate.** Given ~860 s/image in MATLAB, define the acceptable Rust runtime budget and whether the pass is preview-only / off by default. Tile striping vs. full-frame FFT is a design fork.
- **LUT build cost.** The `(W_d, W₀₄₀)` LUT is built once (range −3..3 / 0.1..2, step 0.05 → ~120×38 entries, each an FFT). Decide build-at-startup vs. build-on-first-use vs. precomputed constant table.

## Change History

- 2026-10-08 — Created (Draft). Spec for a post-demosaic, RGB-domain, unified LCA+ACA CA correction ported from Li & Jin (ACCV 2020): shearlet cross-channel prior for LCA + wave-propagation Seidel-LUT PSF estimation + deconvolution for ACA. Records the user constraints that the pass may run after demosaic and that its ordering relative to the pre-demosaic LCA/ACA stages need not be considered.
