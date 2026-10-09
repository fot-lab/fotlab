# Shearlet-Domain Chromatic Aberration Correction — Unified LCA + ACA, Post-Demosaic

- ID: FOTLAB-RENDER-000004
- Status: Draft
- Priority: P2
- Created: 2026-10-08
- Owner: —
- Related: FOTLAB-RENDER-000003 (pre-demosaic ACA / purple-fringe `correct_loca_bayer`), FOTLAB-RAWLER-000011 (pre-demosaic LCA `CA_correct_RT` port), RAWTRP-SURVEY-000005 / -000006 (public ACA / defringe survey), `log/papers/chromatic_aberration_paper_summary.md` (Li & Jin, ACCV 2020)

## Background & Goal

The two chromatic-aberration (CA) stages we currently ship are both **pre-demosaic CFA-mosaic** passes in `rawtrp_correct`:

- `ca_correction_tca.rs` — `correct_ca_bayer`, the RawTherapee `CA_correct_RT` radial shift (Lateral CA, geometric R/B-vs-G misalignment).
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
   * **Stage A — LCA (CC-SD prior):** shearlet-transform R, G, B; minimize the cross-channel shearlet-difference prior `Σᵢ ||Tᵢ(x_G) − Tᵢ(x)||²₂` aligning R/B to G; solve with ADMM (auxiliary `fⱼ` for the shearlet-`ℓ₁` term, `u` for the data term). Per the paper the x-subproblem is FFT/IFFT-closed-form, the `fⱼ`-subproblem is 1-D soft-threshold, the `u`-subproblem uses He's deviation-constrained projection. **Per the paper (Eq 2) `x_G` is the green channel _recovered_ by an existing method such as PSA [10] (i.e. deconvolved), not the raw demosaiced G**; the module must recover G first (internal single-channel shearlet deconvolution, reusing the Stage-A ADMM core) before computing `Tᵢ(x_G)` — see Parameter Provenance.
   * **Stage B — ACA PSF estimation:** from edge patches compute the gradient ratio `R₀ = ∇y(0,0) / ∇y₁(0,0)`; look up the Seidel parameters `(W_d, W₀₄₀)` in the pre-built LUT; synthesize the wave-propagation PSF `h_WP` via the pupil/FFT relation.
   * **Stage C — ACA deconvolution:** deconvolve the LCA-corrected image with the estimated PSF (the same ADMM/regularized-deconvolution core can reuse Stage A's machinery).
3. **Self-contained ordering.** Internally the module sequences LCA (Stage A) → ACA (Stages B–C), matching the paper's Figure 1. It must **not** read or depend on the output of `correct_ca_bayer` / `correct_loca_bayer`, and its correctness must not depend on whether those pre-demosaic stages are enabled.
4. **Passable parameters.** Expose at least:
   - shearlet levels (paper default **3**);
   - ADMM penalties `α, β₁, β₂` (paper: `α = 10×β₁`, `β₁ = β₂ = 1` for simulation; `α = 0.1×β₁` for real-captured);
   - **`f_number`** — optical-system F-number, **required for the ACA branch** (it parameterizes the synthesized PSF `h_WP`, Eq 10). Source: EXIF `FNumber` / `ApertureValue`, or manual user override. If unavailable, skip the ACA stages (see Constraints).
   - **`sigma_reblur` (`σ₀`)** — std of the re-blur Gaussian used to compute the gradient ratio `R₀` (Eq 8) and to build the LUT (Eq 11). Fixed at **`σ₀ = 1.0`** per Zhuo & Sim 2011 [24] (the R₀ framework Li & Jin inherit: *"we set the re-blurring s₀ = 1", isotropic 2D Gaussian, Canny edges, linear camera response assumed*). Not user-tuned.
   - **`sigma_noise` (`σ²`)** — white-noise variance of the image, drives Morozov's discrepancy bound `c = τn²σ²` (Eq 6). Estimate from the raw sensor noise model (ISO/gain → per-channel σ²) when available, else MAD on high-frequency/shearlet coefficients (`σ ≈ median(|coef|)/0.6745`), else variance of a smooth region; allow manual override.
   - the ACA `strength`/enabled switch, and a master `enabled` that short-circuits the whole pass (returns the input unchanged).
   The `(W_d, W₀₄₀)` LUT build range (`W_d: −3..3`, `W₀₄₀: 0.1..2`, step 0.05) and `σ₀ = 1` are build-time/const parameters.
5. **Graceful degradation.** Non-RGB / wrong-channel-count input returns `Error` and the orchestration passes the image through unchanged (degrade, not fail) — consistent with the existing CA stages.

## Constraints

- **Domain is post-demosaic.** Unlike `correct_ca_bayer` / `correct_loca_bayer` (which run pre-WB on the raw-linear mosaic), this pass runs **after** `develop`'s demosaic and (normally) after `calibrate`/WB. Its thresholds and weights therefore live in **display/post-WB RGB** space, not raw-linear pre-WB space. This is the inverse of the ACA purple-fringe tradeoff (FOTLAB-RENDER-000003) and must be documented as such.
- **Crate-contract deviation (rawtrp_correct).** The `rawtrp_correct` crate doc currently states a pre-demosaic CFA-mosaic contract (single-channel array + `CfaDesc`). A post-demosaic 3-channel-RGB module breaks that contract. Options (Open Question): (a) relax the crate doc and host the module here anyway (the user's file name `ca_correction_shearlet.rs` implies this), or (b) move it to a post-demosaic pipeline crate. The design below assumes (a) for now, with the doc updated to say "pre-demosaic CFA passes *plus* an optional post-demosaic RGB pass".
- **Shearlet transform must be self-built (verified: no vendored Rust library).** Checked 2026-10-09: there is **no Rust shearlet crate** — lib.rs returns *"Nothing found"* for `shearlet`, and the canonical **ShearLab** ships only as MATLAB / Python (`pyshearlab`) / Julia (`ShearLab.jl`). The crates.io `shear` crate (v0.1.0) is unrelated (a text-trimming library). The closest Rust relative is `nauticuvs` (v0.1.0, a pure-Rust **curvelet**/FDCT transform, recently published, single-author, SAR-specific) — a *sibling* parabolic-scaling directional transform, but **not** a shearlet and not a drop-in substitute. Consequently the FFT-based shearlet (`SHⱼ(x) = F⁻¹(H̃ⱼ·X)`, FDST = pseudo-polar isometric FFT + band-limited/windowed-Fourier filter bank + frequency-domain shearing & scale decomposition) must be **implemented from scratch** on existing primitives (`rustfft` SIMD FFT + `ndarray`/`ndrustfft`/`realfft`), or substituted by curvelet. This is the single largest implementation unknown; the strategy choice is in Open Questions.
- **Computational cost.** The paper reports ~860 s per 720×720 image in MATLAB on an i7-9700 / 8 GB. A faithful ADMM port will be far heavier than the O(W·H) local passes. Acceptable only as an opt-in, possibly preview-only, path; must not gate the default develop pipeline. Parallelism (rayon over rows/tiles) and FFT planning are mandatory design points, not optimizations.
- **Memory.** Stage C deconvolution allocates FFT buffers at full resolution; for a 100 MP image this is multiple × W·H×C `f32` buffers. Row/ tile striping is a required design consideration, mirroring the ACA delta-buffer note in FOTLAB-RENDER-000003.
- **Independent of the pre-demosaic stages.** The module never reads `detect_ca` / `fit_ca_bayer` / `correct_loca_bayer` output; it recomputes everything from the demosaiced RGB.
- **ACA branch requires a known F-number.** Without `f_number` (no EXIF aperture and no manual override) the wave-propagation PSF **cannot be parameterized** (Eq 10 depends on `f#`). In that case the ACA stages (B–C) must be disabled and the pass reduced to LCA-only (Stage A); do **not** attempt PSF estimation without `f#`. This is a hard guard, not a tuning choice.
- **Optics/domain caveats (carried from the source paper).** The wave-propagation ACA model uses only **two Seidel terms** (defocus `W_d` + spherical `W₀₄₀`) and assumes a **spatially invariant** PSF (paper §3.2). Real lenses show field-dependent CA/defocus, so a single global PSF/LUT lookup cannot correct off-axis variation. The method is validated on *severe* synthetic + single-ball-lens CA, not the mild CA typical of good photographic lenses — keep it opt-in / research-track.

## Parameter Provenance & Estimation

The paper leaves several estimation inputs implicit; this section pins them down so the module is implementable. **No experimental PSF measurement is required** — the PSF is blind-estimated from image edges (§3): edge patches → gradient ratio `R₀` → analytic LUT → synthesized PSF. The only external inputs are `f#`, `σ₀`, `σ²`.

- **F-number (`f#`)** — *why*: Eq (10) synthesizes the PSF from the pupil function and is parameterized by `f#`; the abstract states it is required and the experiments hard-code `f# = 1.2` (their single-ball-lens rig). *source*: EXIF `FNumber` (or `ApertureValue` → `2^(ApertureValue/2)`); fallback to manual user input. *if absent*: disable ACA stages B–C, run LCA only.
- **Re-blur Gaussian `σ₀`** — *why*: `R₀ = ∇y(0,0) / ∇y₁(0,0)` where `y₁ = y * g(σ₀)` (Eq 8); the same `σ₀` enters the LUT model `h(..., σ₀, ...)` (Eq 11). *value*: **`σ₀ = 1.0`** (Zhuo & Sim 2011 [24], §3: *"we set the re-blurring s₀ = 1"*, isotropic 2D Gaussian, Canny edges, linear camera response assumed). Treat as a fixed constant. **Do not confuse with the `σ₀ = (4, 2, 7)` in Fig 2(d)** — that is a *comparison* Gaussian PSF drawn only to visualize kernel shapes, unrelated to the re-blur.
- **Noise variance `σ²`** — *why*: Morozov discrepancy `δ_Ω(u)` bounds the data-fidelity residual by `c = τn²σ²`, `τ = −0.006·BSNR + 1.09`, `BSNR = log₁₀(‖y−mean(y)‖² / (n²σ²))` (Eq 6). *estimation*: prefer the raw sensor noise model (ISO + analog/digital gain → per-channel σ²) since noise originates at the sensor; else median-absolute-deviation on high-frequency/shearlet coefficients (`σ ≈ median(|coef|)/0.6745`); else variance of a smooth/flat region. Allow manual override.
- **Recovered green `x_G`** — *why*: the CC-SD prior (Eq 2) aligns R/B texture to `x_G`, defined in the paper as the green channel *recovered by an existing method such as PSA [10]* (i.e. deconvolved), **not** the raw demosaiced G. *implication*: Stage A must first run a single-channel shearlet deconvolution on G (reuse the Stage-A ADMM core with the shearlet-`ℓ₁` prior only) before computing `Tᵢ(x_G)`. Add an internal `recover_green` step in `ca_correction_shearlet.rs`.
- **Seidel LUT (`W_d, W₀₄₀`)** — pre-built **analytically** from the wave model (not measured); range `W_d: −3..3`, `W₀₄₀: 0.1..2`, step 0.05 (~4.7k entries, each an FFT). The LUT is `f#`- and `σ₀`-dependent; rebuild when `f#` changes (build-at-first-use keyed on `f#`).

### Caveats carried from the source paper
- **Spatially invariant PSF** (paper §3.2): one global PSF/LUT per frame; cannot correct field-dependent CA/defocus.
- **Two-term aberration model**: only defocus `W_d` + spherical `W₀₄₀`; no astigmatism/coma — model mismatch on complex real lenses.
- **Domain**: validated on severe synthetic + single-ball-lens CA; benefit on the mild CA of good photographic lenses is unproven and must stay opt-in.
- **Ringing**: the paper acknowledges deconvolution can add *"ring effect and distinct noise"* (p.114); add a no-new-artifact guard (see Acceptance Criteria).

## Acceptance Criteria

- A demosaiced RGB image synthesized with (a) a known R/B-vs-G geometric shift (LCA) and (b) a known channel-dependent defocus blur (ACA) has **both** artifacts reduced after `correct_ca_shearlet`, measured by PSNR/SSIM against the ground-truth sharp image (the paper's simulation protocol: ≥2 dB PSNR gain over the uncorrected / CC-prior baselines).
- With `enabled == false` (or the FFI `ShearletCaSettings == None`) the pass is an exact identity (output byte-equal to input).
- Stage A alone (ACA disabled) reduces the LCA shift; Stage C alone (LCA disabled) reduces the ACA blur — i.e. the two axes are independently switchable per the paper's two contributions.
- A wrong-channel-count / non-RGB input returns `Error` and the orchestration passes through unchanged (degrade, not fail).
- No `Cargo.lock` or personal-info strings are added by this change; the new FFT dependency is declared in the crate `Cargo.toml` (not committed as a lockfile churn beyond the crate).
- The module does not read or require the pre-demosaic LCA/ACA outputs; with those stages disabled, `correct_ca_shearlet` still corrects both axes (proves independence).
- With `f_number` absent, the ACA stages (B–C) are skipped and only LCA (Stage A) runs — the pass still corrects LCA and returns without error (degrade, not fail), proving the ACA branch is cleanly separable from the LCA branch.
- On a CA-free control image, the pass introduces **no new ringing or color fringe** (per the paper's own ringing caveat, p.114) — verified by a no-new-artifact check (e.g. residual high-frequency energy / chroma not exceeding the input's).

## Impacted Modules

- `app/src/binding/rust/rawtrp_correct/src/ca_correction_shearlet.rs` (new) — `correct_ca_shearlet`, `ShearletCaParams`, `Error`; internal shearlet transform, ADMM solver, Seidel-LUT PSF estimator, deconvolution.
- `app/src/binding/rust/rawtrp_correct/src/lib.rs` — `pub mod ca_correction_shearlet;` + `pub use`; **and** update the crate-level `//!` doc to acknowledge the post-demosaic RGB pass alongside the pre-demosaic CFA passes.
- `app/src/binding/rust/rawtrp_correct/Cargo.toml` — FFT dependency (`rustfft` or equivalent).
- `app/src/binding/rust/rawler_fotlab/src/shearlet.rs` (new) or extension of the existing CA FFI — `ShearletCaSettings` (uniffi Record) + `correct_ca_shearlet` orchestration.
- `app/src/binding/rust/rawler_fotlab/src/develop.rs` — call the shearlet pass **after** demosaic/WB (post-demosaic path), independently of the existing pre-demosaic `correct_ca` / `correct_loca` calls.
- `rules/DESIGN/index.md` — this row.

## Open Questions

> **Resolved by research (2026-10-08):** the four implicit estimation inputs are now pinned — `f_number` (EXIF `FNumber`/`ApertureValue` or manual override, required for ACA), re-blur `σ₀ = 1.0` (Zhuo & Sim 2011 [24]), noise `σ²` (raw noise profile / MAD / smooth-region), and the `x_G`-must-be-recovered prerequisite (PSA-style single-channel deconvolution). All documented in **Parameter Provenance & Estimation** above. Remaining open questions:

- **Hosting / crate contract.** Keep `ca_correction_shearlet.rs` inside `rawtrp_correct` (relaxing its CFA contract) or move it to a post-demosaic pipeline crate? The user's file name implies the former; the crate's `//!` doc must be reconciled either way.
- **Shearlet implementation strategy (resolved 2026-10-09: no vendored option).** Verified there is **no Rust shearlet library** to vendor (lib.rs returns *"Nothing found"* for `shearlet`; canonical ShearLab is MATLAB / Python `pyshearlab` / Julia `ShearLab.jl` only; the crates.io `shear` crate is an unrelated text-trimming lib). Three routes: (a) **self-build the FDST** on `rustfft`+`ndarray` (full fidelity to the paper, most effort — pseudo-polar isometric FFT, band-limited/windowed-Fourier filter bank, frequency-domain shearing & scale decomposition); (b) **substitute a curvelet** (`nauticuvs` v0.1.0, immature) — a sibling directional prior that reproduces the cross-channel directional-alignment idea for LCA, but changes the transform basis so results are **not directly comparable** to the paper's numbers; or (c) **re-evaluate necessity** — the paper's SOTA gains are on *severe* synthetic CA, while fotlab targets *mild–moderate* CA already partly covered by the lightweight pre-demosaic stages, so the shearlet prior may be optional rather than required. Recommend (a) only if the research-track gain proves worth the implementation cost; otherwise default to (c) or (b).
- **Double correction.** When the shearlet (unified) path is enabled, should the pre-demosaic `correct_ca_bayer` (LCA) and `correct_loca_bayer` (ACA) stages be auto-disabled to avoid applying CA correction twice? Default behaviour (both on vs. shearlet-only) is unset.
- **Domain precision.** Post-demosaic usually means post-WB RGB; the paper's priors are defined on the (possibly still-linear) RGB. Confirm whether the pass should run on linear-RGB pre-tonemap or on the final display RGB, and whether the `α/β` defaults need re-tuning off the paper's MATLAB values.
- **Performance gate.** Given ~860 s/image in MATLAB, define the acceptable Rust runtime budget and whether the pass is preview-only / off by default. Tile striping vs. full-frame FFT is a design fork.
- **LUT build cost.** The `(W_d, W₀₄₀)` LUT is built once (range −3..3 / 0.1..2, step 0.05 → ~120×38 entries, each an FFT). Decide build-at-startup vs. build-on-first-use vs. precomputed constant table.

## Change History

- 2026-10-08 — Created (Draft). Spec for a post-demosaic, RGB-domain, unified LCA+ACA CA correction ported from Li & Jin (ACCV 2020): shearlet cross-channel prior for LCA + wave-propagation Seidel-LUT PSF estimation + deconvolution for ACA. Records the user constraints that the pass may run after demosaic and that its ordering relative to the pre-demosaic LCA/ACA stages need not be considered.
- 2026-10-08 — Added **Parameter Provenance & Estimation** (and extended Req 4, Constraints, Acceptance, Open Questions) after verifying the source PDF `log/papers/119-134.pdf` (Li & Jin, ACCV 2020, LNCS 12623 pp.102–117) and its references. Pinned: `f_number` (EXIF/manual, **required** for ACA — Eq 10 is `f#`-parameterized; absent ⇒ LCA-only), re-blur `σ₀ = 1.0` (Zhuo & Sim 2011 [24], the R₀ framework Li & Jin inherit), noise `σ²` estimation for Morozov's discrepancy (Eq 6, raw noise profile / MAD / smooth-region), and the `x_G`-recovery prerequisite (PSA-style single-channel deconvolution, Eq 2). Flagged the Fig 2(d) `σ₀=(4,2,7)` as a *comparison* PSF (not the re-blur σ₀), and carried forward the spatial-invariance + 2-term Seidel + ringing caveats.
- 2026-10-09 — **Resolved the "Shearlet library" open question (no vendored option).** Verified there is no Rust shearlet crate: lib.rs returns *"Nothing found"* for `shearlet`; canonical ShearLab is MATLAB / Python `pyshearlab` / Julia `ShearLab.jl` only; the crates.io `shear` crate (v0.1.0) is an unrelated text-trimming library; the closest Rust relative `nauticuvs` (v0.1.0) is an immature pure-Rust **curvelet**/FDCT transform (sibling directional prior, not a drop-in substitute). Updated Constraints (shearlet must be self-built on `rustfft`+`ndarray` primitives, or substituted by curvelet) and Open Questions (three routes: self-build FDST / curvelet substitute / re-evaluate necessity), and recorded that building-block FFT primitives (`rustfft`, `ndrustfft`, `realfft`) are available.
