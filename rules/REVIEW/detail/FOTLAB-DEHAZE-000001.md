# CFA-domain dehaze — complete math of the guided-filter haze field, and RapidRAW cross-reference

- ID: FOTLAB-DEHAZE-000001
- Status: Observation
- Priority: P3
- Created: 2026-10-09
- Owner: —
- Related: FOTLAB-RAWLER-000009 (pre-demosaic dehaze baseline: per-plane scalar haze floor); FOTLAB-RAWLER-000010 (CFA-domain guided-filter feasibility research); FOTLAB-RAWLER-000012 (dehaze pipeline audit)

## Background & Goal

First-party dehaze lives in `app/src/binding/rust/rawler_fotlab/src/dehaze.rs` (orchestration) and `dehaze_guided_filter.rs` (pixel core), operating on the **scaled CFA mosaic in `[0,1]` — pre-demosaic, NOT RGB, NOT Lab**. This document records, end-to-end and with formulas, how that algorithm (a) *identifies* the haze field, (b) *preserves boundaries*, and (c) *produces* the final spatially-varying haze field `h`, using the fast guided filter (He & Sun 2015). It also records how the external RapidRAW dehaze (`external/rapidraw`) differs, because both ultimately apply the Koschmieder atmospheric-scattering inverse but in opposite domains.

This is a fact/algorithm record; no design change is prescribed.

## Finding

### F1. Pipeline shape — three separable stages
`dehaze()` (`dehaze_guided_filter.rs:108`) routes by `ceiling`: `ceiling` set ⇒ **guided (2D) branch**; absent ⇒ **scalar (global-floor) branch**. The guided branch is:
```
estimate (per plane) → merge (one shared field) → apply
```
The CFA is decomposed into `period` colour planes (`CfaPlanes::from_cfa`); a regular plane `p` carries offset `(dr,dc)` and a sub-lattice of size `(gw,gh)≈(W/period, H/period)`.

### F2. The guide image = the plane's own mosaic (self-guided, no transform)
In `estimate_masks` (`dehaze_guided_filter.rs:237`):
```
guide[i,j] = pixels[(dr + period*i)*W + (dc + period*j)]
```
The guide is **the raw mosaic value of that colour plane, decimated by `period`** — no blur, no debayer, no wavelet, no normalization beyond the already `[0,1]` scaled buffer. It is *self-guided*: guide and source come from the same plane. The guide therefore carries the plane's true scene luminance structure (sky, foliage, building edges), which is exactly what makes later edge preservation possible.

### F3. Haze identification — local dark channel via box-min (no wavelet)
`D_p(i,j) = min_{(u,v)∈Ω} I_p(u,v)` over a square window of radius `dark_radius` (default 8 ⇒ 17×17), implemented by a monotonic deque as a separable O(N) box-minimum (`box_min:681`). This is the dark-channel-prior proxy: in haze the airlight `A` raises every mosaic value by `A(1−t)`, lifting the local minimum; in a clear/dark object `D≈0`. Hence **`D` high ⟺ hazy, `D` low ⟺ clean** — `D` is the haze recognizer.

### F4. Guided-filter refinement — the math
Inputs: guide `I_p`, source `D_p`. All means are sliding-window **uniform (box) averages** of radius `guide_radius` (default 8), implemented with an f64 summed-area table (`box_mean:624`), mathematically a convolution with the square kernel
```
K_box(x,y) = 1/W_k  for |x|,|y|≤r and in-bounds, 0 otherwise
```
(W_k = actual in-window sample count; borders use partial windows, so no shift). Compute:
```
μ_I = I ⊛ K_box          μ_D = D ⊛ K_box
μ_II = (I²) ⊛ K_box      μ_ID = (I·D) ⊛ K_box
```
Per-window local-linear coefficients (least squares):
```
a = (μ_ID − μ_I·μ_D) / (μ_II − μ_I² + ε)
b = μ_D − a·μ_I
```
The final refined field averages the overlapping windows:
```
h_p = ā·I_p + b̄,   ā = a ⊛ K_box,  b̄ = b ⊛ K_box
```
(`guided_filter:556`). `h_p` is the plane's **edge-preserving haze field**.

### F5. Local variance — how it is actually computed
`σ_I² = μ_II − μ_I²` (`guided_filter:592`), i.e. the **second moment within the box window**, NOT a Sobel/Laplacian/Gaussian-difference edge kernel and NOT a wavelet. It is obtained by subtracting two box means (the mean of `I²` minus the square of the mean of `I`). The same construction gives covariance `σ_ID = μ_ID − μ_I·μ_D`.

### F6. Boundary preservation — emerges from the local-linear model
There is **no explicit edge detector**. Edge awareness comes from the guide's local variance `σ_I²`:
- **Flat region:** `σ_I² ≈ 0` ⇒ `a ≈ 0`, `b ≈ μ_D` ⇒ `h_p ≈ μ_D` — the window is smoothed (where it should be flat).
- **Edge/texture region:** `σ_I²` large ⇒ `a` significant ⇒ `h_p = ā·I + b̄` **follows the guide**, so real edges in the dark channel are kept sharp and **no halo is introduced**.
The regularization `ε = GUIDE_EPS = 0.01` decides "how flat counts as flat": below it, smooth; above it, let detail through. The box kernel is direction-free; boundaries are recovered structurally, not by a gradient operator.

### F7. Ceiling clamp
`h_p ← clamp(h_p, 0, cap_tail)` with `cap_tail = ceiling` (`estimate_masks:264`). Caps the maximum haze any pixel may claim, preventing over-dehaze (dark values crushed below 0).

### F8. Multi-plane merge + expansion
Per-plane `h_p` (sub-lattice `Grid`, or `Uniform` for irregular CFAs / scalar branch) is resampled onto the common period grid `(⌈W/period⌉, ⌈H/period⌉)` and collapsed (`reduce_cells:314`):
- `Each` — no merge, each plane applies its own field;
- `Blue` — blue plane's field everywhere (most conservative, blue scatters most);
- `Min` (default) — per-cell `min` across planes: a pixel is dehazed only where *every* plane agrees it is hazy;
- `Avg` — per-cell mean (historical shared-field merge).
The reduced cell is then **block-replicated to every photosite in its CFA period**, so all colours inside one period share one `h`. This bounds the colour cast (the additive `-A·strength·h` offset is not ratio-preserving; a neutral block stays neutral, a coloured block casts by a bounded, spatially-coherent amount — an accepted trade, pinned in tests `dehaze_classical_allows_coloured_cast`).

### F9. Apply — classical atmospheric-scattering recovery
`apply_mask:471`:
```
t = max(1 − strength·h, ε)        # effective transmission
cleared = (v − A)/t + A,  cleared ← max(cleared, 0)
```
`strength∈[0,1]` (UI amount; `0` ⇒ identity), `A` = atmospheric light (1.0 at the FFI boundary, parameterised for later per-channel extension). `h` sets local transmission: hazy `h` large ⇒ `t` small ⇒ strong dehaze; clean `h` small ⇒ `t≈1` ⇒ untouched. This is the exact inverse of `I = J(1−h) + A·h` for a spatially-varying `h`.

### F10. RapidRAW cross-reference (external, RGB domain)
RapidRAW's `apply_dehaze` (`external/rapidraw/src-tauri/src/shaders/shader.wgsl:1107`, a WGSL compute fragment on the already-rendered RGB texture) is also a Koschmieder inverse `J = (I − A)/t + A` with fixed `A = (0.95,0.97,1.0)`, but:
- **Domain:** post-demosaic **linear RGB** (`srgb_to_linear` for non-raw; `is_raw` only switches encoding, not CFA). No CFA, no Lab.
- **Haze estimate:** a blurred copy of the image (`structure_blur_texture`) → `min(r,g,b)` as a regional dark value, with a `halo_protection` term built from the sqrt-luma difference between pixel and blurred (`smoothstep`) to avoid halos — i.e. blur-based edge handling rather than a guided filter.
- **Auto dehaze** (`image_processing.rs:3385`) is a heuristic, not physical: if dynamic range `< 120` and mean saturation `< 0.15` it assigns `dehaze = (1 − range/120)·35`.
Contrast: RapidRAW dehazes *rendered* RGB atmospheric haze; fot-lab dehazes *raw black-point* uniform haze in the CFA domain. Both are physical-model dehaze; neither uses a wavelet.

## Impact / Conflict

- **No wavelet anywhere:** both our dehaze and RapidRAW's rely on box filters / integral images / blurred copies; the only "edge" mechanism is the guided-filter local-linear model (ours) or blur + luma-difference (RapidRAW).
- **Domain choice is the key fork:** ours is pre-demosaic CFA (per-plane sub-lattice, own-guide); RapidRAW is post-demosaic RGB. The `FOTLAB-RAWLER-000010` feasibility constraint (mosaic cannot be fed directly; must decompose per colour sub-lattice) is exactly what `estimate_masks` implements via `CfaPlanes`.
- **Accepted colour-cast trade (F8/F9):** because `h` is shared per CFA period and the apply is additive (not multiplicative-gain), a coloured block casts by `A·strength·h`; this is deliberate and pinned by tests, not a bug.

## Recommendation

Omitted. This document is an algorithm fact record only; it prescribes no change. The numbers `GUIDE_RADIUS=8`, `GUIDE_EPS=0.01`, default `percentile=1%`, and default merge mode `Min` are current implementation constants and are recorded here for reference, not as a proposal.

## Change History

- 2026-10-09 — Created as an Observation documenting the full mathematics of the CFA-domain guided-filter dehaze (guide = plane's own mosaic; box-min dark channel; guided-filter variance/covariance local-linear refinement; ceiling clamp; Min/Avg/Blue/Each merge; classical `(v−A)/(1−strength·h)+A` recovery) and contrasting it with external RapidRAW's RGB-domain Koschmieder dehaze.
