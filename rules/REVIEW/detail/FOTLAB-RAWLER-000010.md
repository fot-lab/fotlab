# CFA-domain guided filter feasibility for pre-demosaic dehaze — research findings

- ID: FOTLAB-RAWLER-000010
- Status: Observation
- Priority: P3
- Created: 2026-09-24
- Owner: —
- Related: FOTLAB-RAWLER-000009 (pre-demosaic dehaze baseline: per-plane scalar haze floor)

## Background & Goal

The current first-party dehaze stage (`app/src/binding/rust/rawler_fotlab/src/dehaze.rs`) estimates **one scalar haze floor per CFA colour plane** — R, G, B — over the whole active area (a per-plane percentile of a 256-bin, 0..1 histogram), then applies `(v − h)/(1 − h)` uniformly to every photosite of that plane. Because the floor is a global statistic, the stage holds no 2D spatial information and cannot vary dehaze strength by location (e.g. clear regions vs. dense-haze regions share one floor).

Goal of this research: determine, from available in-repo source, whether a **guided filter** can be applied in the **CFA / pre-demosaic domain** (i.e. on the single-channel scaled mosaic, before demosaic) to recover spatial awareness, and what constraints that imposes. This document records only verifiable facts and source references; no design decision or recommendation is made.

## Finding

### F1. Every production guided-filter dehaze in the repo runs post-demosaic, on RGB
- RawTherapee dehaze (`external/RawTherapee/rtengine/ipdehaze.cc`) operates on `Imagefloat` (demosaiced R/G/B). The header comment (lines 21–29) cites He/Sun/Tang DCP and explicitly uses a guided filter "for the soft matting of the transmission map". Dark channel is computed from `R/G/B` arrays (`get_dark_channel`, lines 106–163); ambient light is per-channel `ambient[3]` (`estimate_ambient_light`, lines 196–289).
- The same pattern holds for the other reference implementation (darktable `hazeremoval`, per project memory): DCP + guided filter on post-demosaic RGB.
- Conclusion: **no existing in-repo implementation applies a guided filter to the raw CFA mosaic.** All usages assume a demosaiced RGB buffer.

### F2. The guided filter itself is domain-agnostic and reusable
- Interface (`external/RawTherapee/rtengine/guidedfilter.h`, line 29): `guidedFilter(const array2D<float> &guide, const array2D<float> &src, array2D<float> &dst, int r, float epsilon, bool multithread, int subsampling=0)`. It consumes and produces single-channel `array2D<float>`; it has no knowledge of colour or CFA. The `subsampling` parameter confirms it is the **fast guided filter** (He & Sun 2015).
- RT's own dehaze uses it in two domain-agnostic ways (`ipdehaze.cc`):
  - `extract_channels` (lines 291–304): self-guided smoothing of each of R, G, B independently (`guidedFilter(imgR, imgR, r, …)`).
  - Refinement of the transmission/dark map: `guidedFilter(guideB, dark, dark, radius, epsilon)` (lines 378–379 and 541–542) — Blue channel as guide.
- Conclusion: **the algorithm can be ported (Rust + rayon) and reused; the obstacle is not the filter but what image is fed to it, and in which domain.**

### F3. A raw CFA mosaic cannot be fed to a guided filter directly
- The guided filter's local-linear model `q = a·I + b` requires the **guide image I to be locally smooth** (piecewise linear). On a raw Bayer/X-Trans mosaic, adjacent pixels are different colours and values jump R↔G↔B.
- Feeding the mosaic directly as guide mixes three colours inside every local box window: `var(I)` is inflated by colour jumps, `a = cov(I,p)/(var(I)+ε)` collapses toward 0, and the output degenerates to `mean(src)` — i.e. a blur that also **cross-colours** between R/G/B samples. This is a property of the algorithm, not an implementation bug.
- Conclusion: **naive application on the single-channel mosaic is invalid; the guide must be a locally-smooth image.**

### F4. The CFA-aware pattern: filter each colour plane on its own sub-lattice
- The correct decomposition is to separate the mosaic into **per-colour sub-lattices** (one regular grid per plane), run the guided filter on each sub-lattice with that plane's own values as guide, then scatter the results back to original positions via `CFA::color_at`. This is structurally identical to RT's `extract_channels` (F2), except the inputs are half/quarter-resolution sub-lattices rather than full-resolution RGB channels. Each sub-lattice is the true single-colour signal and is locally smooth, so the local-linear assumption holds.

### F5. Bayer decomposition implies 4 regular grids (R / G1 / G2 / B)
- `external/dnglab/rawler/src/cfa.rs` encodes colours as `CFAColor`: `RED=0, GREEN=1, BLUE=2, …` (lines 18–30). `CFA::color_at` returns this value (lines 167–169). For an RGGB pattern both green positions return `GREEN = 1`.
- The current dehaze merges the two greens into a single G plane (`plane_count = 3`, via `PlaneColor`, lines 333–335; `dehaze.rs` treats both G photosites as plane 1).
- If the two greens are kept merged, the G samples form a **quincunx / two-offset checkerboard**, which is **not a regular rectangular grid**. A box-blur-based guided filter (F2) needs a regular grid for its window statistics, so it **cannot be applied directly to the merged-G sub-image**.
- rawler exposes `CFA::map_colors` (lines 151–163) and 4-colour `PlaneColor`, which can remap RGB → R G1 G2 B. Decomposing Bayer into **4 regular half-resolution grids (R, G1, G2, B)** makes each sub-lattice a valid rectangular array for the guided filter.
- Conclusion (factual): a clean per-sub-lattice guided filter on Bayer is most naturally expressed with a **4-plane (R/G1/G2/B)** decomposition, which diverges from the current dehaze's 3-plane (R/G/B) merge. This is an observed constraint, not a prescribed change.

### F6. X-Trans (6×6) follows the same sub-lattice principle
- X-Trans repeats a 6×6 pattern; each colour occupies a regular set of positions within the tile. Extracting each colour into its own regular sub-grid (by the repeating colour coordinates) yields valid rectangular sub-lattices, analogous to F5. No special-casing beyond the per-CFA colour map is required.

### F7. No in-repo Rust guided filter implementation exists
- A content search of `app/` for `guidedFilter` / `guided_filter` / `GuidedFilter` returns nothing. `external/RapidRAW` references a guided filter in `focus_stacking.rs` / `ai_processing.rs`, but those are upper-layer usages, not a reusable CFA-domain primitive. The authoritative, licence-compatible reference in this repo is the RT C++ (`external/RawTherapee/rtengine/guidedfilter.cc` + `.h`).

## Impact / Conflict

- **Domain gap (F1, F3):** all guided-filter dehaze references are post-demosaic RGB; applying one pre-demosaic requires the per-sub-lattice decomposition of F4–F6, which is not present in `dehaze.rs` today.
- **Plane-count conflict (F5):** the current dehaze deliberately merges the two Bayer greens into one plane (3 planes). A box-blur guided filter needs regular grids, which pushes toward a 4-plane (R/G1/G2/B) decomposition — a divergence from the current merge that must be reconciled if a CFA-domain filter is ever adopted.
- **Reuse is feasible (F2, F7):** the fast guided filter is domain-agnostic and its RT C++ is present in-repo; porting to Rust + rayon is the only implementation prerequisite, independent of the domain decision.

## Recommendation

Omitted per request. This document is a fact record only; design decisions and prescriptions are deferred.

## Change History

- 2026-09-24 — Created as an Observation recording the CFA-domain guided-filter feasibility research (facts F1–F7, domain gap, plane-count conflict, reuse feasibility). No recommendation included.
