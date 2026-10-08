# LCA vs. Kang 2010 PDE paper — patch method not adopted; global radial scaling LCA retained

- ID: FOTLAB-RAWLER-000022
- Status: Rejected
- Priority: P3
- Created: 2026-10-08
- Owner: —
- Related: [`FOTLAB-RAWLER-000011`](FOTLAB-RAWLER-000011.md) (pre-demosaic LCA/CA stage — our LCA lives here), [`FOTLAB-RAWLER-000021`](FOTLAB-RAWLER-000021.md) (ca_correction_lca brightness-edge survey), [`FOTLAB-RAWLER-000020`](FOTLAB-RAWLER-000020.md) (LoCA purple/green fringe — our longitudinal-CA coverage), [`FOTLAB-RAWLER-000009`](FOTLAB-RAWLER-000009.md) (pre-demosaic slot convention)

## Background & Goal

Surveyed Kang, H., Lee, S.H., Chang, J., Kang, M.G., "Partial differential equation-based approach for removal of chromatic aberration with local characteristics", *J. Electron. Imaging* 19, 033016 (2010), DOI 10.1117/1.3494278. Goal: determine whether our lateral-CA (LCA) implementation has optimization room by adopting that method. Research only — no code changed.

## Finding

- **Our LCA** (`ca_correction_lca.rs:1`) is a faithful port of RawTherapee's `CA_correct_RT`: pre-demosaic, radially-symmetric correction. Pass 1 fits a single global 2-D polynomial of residual CA (`POLYORD = 4`, `ca_correction_lca.rs:72`; `FitParams` is one 16-coeff polynomial per R/B direction, `ca_correction_lca.rs:82`); pass 2 resamples the **R/B planes only** (`correct_ca_bayer`, `ca_correction_lca.rs:139`), green untouched. This models LCA as a **geometric radial displacement field** — which is the physically correct optical model: lateral CA is wavelength-dependent refraction producing a positional error that scales with distance from the optical center.
- **The paper's method** is a single nonlinear PDE that simultaneously corrects lateral + longitudinal CA by matching R/B edge **gradients** to G, locally, in the **post-demosaic** RGB domain (it needs full per-channel gradients). It is a content-adaptive **patch/repair** technique, not an optical model of the lens. Its central claim is that "global warping under-corrects local CA."
- **Assessment of the gap** (from the survey): the paper is stronger than our LCA on (a) azimuthal/zonal CA residual that a single global polynomial leaves behind, and (b) unifying lateral + longitudinal CA into one gradient-matching step. Our longitudinal CA is already covered by the separate ACA module (`ca_correction_aca.rs:1`, see `FOTLAB-RAWLER-000020`) — the paper's most notable capability (purple fringing) is **not** missing.
- **Decision (user directive)**: the paper's method is a 修补法 (patch method) and is **not adopted**. Our global radial scaling LCA conforms to physical law and is retained.

## Impact / Conflict

- **Decision**: do **not** implement Kang 2010. Retain the global radial scaling LCA.
- **Rationale**:
  1. **Physical correspondence** — radial displacement is the true optical CA model (lateral CA ∝ distance from optical center, set by wavelength-dependent refraction). The PDE gradient-matching is a heuristic patch that does not model the lens and can distort texture where no CA exists.
  2. **Pipeline position** — the PDE needs post-demosaic RGB gradients, which would move CA correction after demosaic, contradicting the pre-demosaic slot convention (`FOTLAB-RAWLER-000009`) and the established industry placement (RT / darktable / Lightroom all pre-demosaic).
  3. **Runtime** — a nonlinear PDE iteration is materially heavier than one radial resample; mobile (`minSdk 26`) per-render cost matters, whereas our two CA modules are already `rayon`-parallel and cheap.
  4. **Coverage** — longitudinal CA (the paper's headline) is already handled by the independent ACA module.
- **Conflict with earlier internal suggestions** — a prior internal analysis proposed local-adaptive LCA / PDE unification (R1–R3). Those are now **not** to be pursued via the paper's method. Any future LCA refinement must stay within the physically-grounded radial model (e.g., per-annulus / local polynomial fit rather than the gradient-patch PDE) and only when a developer explicitly requests it.

## Recommendation

- Keep `ca_correction_lca.rs` global radial scaling as the LCA algorithm.
- Do **not** implement the Kang 2010 PDE / gradient-matching patch method.
- If a future local refinement is desired, it must: (a) remain pre-demosaic, (b) remain a radial / optical model, (c) be explicitly requested by a developer. Reference the paper only as a "local CA residual" caveat note, never as an implementation target.

## Change History

- 2026-10-08 — created (Status: Rejected). Survey of Kang 2010 vs. our LCA. Conclusion: paper's PDE patch-method is **not adopted**; our global radial scaling LCA is physically grounded and retained. Per user directive, the method is not to be adopted unless a developer explicitly specifies. No code changed.
