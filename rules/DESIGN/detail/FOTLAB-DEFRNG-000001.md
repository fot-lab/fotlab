# Oklch Post-Demosaic Defringe — Bright-Edge Chroma (C) + Luminance (L) Reduction

- ID: FOTLAB-DEFRNG-000001
- Status: Draft
- Priority: P2
- Created: 2026-10-10
- Owner: —
- Related: FOTLAB-RENDER-000001 (OKLab highlight-chroma compression — same `calibrate_oklab` round trip), FOTLAB-RENDER-000003 (pre-demosaic ACA / purple-fringe `correct_loca_bayer`), FOTLAB-RENDER-000004 (post-demosaic shearlet unified CA); research basis: Ottosson, *A perceptual color space for image processing* (2020); mjambon/purple-fringe "Unpurple" (GitHub); Lu et al., *DCA-LUT* (arXiv:2511.12066, 2025); RawTherapee/ART Defringing; GIMP PurpleFringe script.

## Background & Goal

The Oklab/Oklch stage already runs **post-demosaic, post-WB, in camera space** (`rawler_fotlab/src/calibrate_oklab.rs`, FOTLAB-RENDER-000001) and currently only does highlight-chroma compression. This item extends that same pass with a **defringe** step: detect bright edges that carry high chroma (purple/magenta, and the mirror green fringe) in Oklch and neutralize the fringe by reducing **C** (desaturate) and, secondarily, **L** (pull down the luminance overshoot).

Rationale, from the research (see `rules/DESIGN/detail/FOTLAB-RENDER-000004.md` §调研 and the sources above):

- Reducing **C** at a fringe is the classic, safe defringe action (pull the colour toward neutral without introducing a new hue) — exactly what `mjambon/purple-fringe` ("desaturate toward gray", with channel-lower-bound safety) and RawTherapee/ART's Lab defringe do.
- Reducing **L** is also well-founded: *DCA-LUT* (2025) frames purple fringing as **edge-localized** and explicitly performs a **luminance correction** conditioned on the fringe gradient `|∇|` and edge geometry. A bright fringe is a luminance overshoot at a high-contrast edge, so pulling L down is physically appropriate — provided it is edge-gated and gentle.
- **Oklch is a better substrate than CIELAB/LCH here.** Ottosson (2020) shows CIELAB predicts blue hues badly and shifts white→blue blends toward purple; Oklab adds a "blue colours do not fold inwards" constraint. Purple/magenta fringe lives exactly in that blue-violet band, so C/h thresholds and operations are far more stable in Oklch.
- This is **complementary**, not a replacement, to the pre-demosaic ACA (`correct_loca_bayer`, raise-G, FOTLAB-RENDER-000003): that stage works in the CFA mosaic and only raises/lowers G; a post-demosaic Oklch pass can catch residual fringe the mosaic stage misses and operate in the perceptually-uniform space the pipeline already enters.

## Requirement

1. **Reuse the existing OKLab round trip.** The defringe function lives in `calibrate_oklab.rs` (the module "owns the entire OKLab round trip") and operates on OKLab/Oklch pixels derived from the camera-space triple via the existing `D65XYZ2OKLab` / `OKLab2D65XYZ` (and the standard Ottosson constants already defined there). No second Oklch conversion is introduced.
2. **Detection — three BOUND gates (criterion + behaviour as one unit).** A candidate fringe pixel must satisfy all of:
   * **Bright edge** — `|∇L| > edge_threshold` (local L gradient magnitude; edge localization, mirroring DCA-LUT's edge-geometry cue and Unpurple's brightness mask).
   * **High chroma** — `C > chroma_threshold`.
   * **Hue band** — `h` within a configurable **purple/magenta** band *and* a separate **green** band (the mirror fringe on dark edges). Each band has its own lo/hi and an enable switch.
   `fringe_weight` is then a smooth combination of the chroma excess (`smoothstep(chroma_threshold, chroma_threshold+width, C)`) and a hue-match factor (1 at band centre → 0 at band edge), times the edge weight.
3. **Repair — two BOUND operations, both scaled by `strength · fringe_weight`:**
   * **Reduce C (primary, always on when enabled):** `C' = C · (1 − strength · fringe_weight)`, clamped `≥ 0`; **hue h unchanged** — desaturate toward neutral, never introducing a new hue (the Oklch analogue of Unpurple's "desaturate toward gray, never below the G-relative lower bound").
   * **Reduce L (secondary, edge-gated, optional via `l_reduce`):** `L' = L − l_reduce · strength · fringe_weight · edge_weight`, clamped to `[0,1]`. Pulls down the bright-edge luminance overshoot. Off by default (`l_reduce = 0`); enabled only when the user opts in, because un-gated L reduction risks dark notches on legitimately bright content.
4. **Passable parameters (uniffi defaults on a `DefringeOklabSettings` record):** `enabled` (master), `strength` (default 1.0), `chroma_threshold`, `edge_threshold`, purple band `purple_hue_lo/hi` + `purple_enabled`, green band `green_hue_lo/hi` + `green_enabled`, `l_reduce` (default 0.0). The `DevelopParams` field is `Option` (default `None` ⇒ identity), so existing call sites stay compile-green.
5. **Graceful degradation.** `enabled == false` (or settings `None`, or `strength == 0`) ⇒ exact identity. If the Oklch/camera-space path is not in use, the orchestration passes through unchanged.
6. **Independent of the pre-demosaic stages.** The module never reads `correct_ca_bayer` / `correct_loca_bayer` output; it recomputes everything from the demosaiced, white-balanced RGB.

## Constraints

- **Domain is post-demosaic, post-WB, camera space** — identical to `calibrate_oklab.rs`'s contract (camera triple in, camera triple out, before `cam2rgb`). All thresholds (`C`, `L`, hue in degrees) are Oklch units. This is the inverse of the pre-demosaic ACA's pre-WB raw-linear domain (FOTLAB-RENDER-000003) and must be documented as such.
- **Reducing L is the dangerous half.** Un-gated L reduction darkens legitimately bright, saturated (but correct) content. Mitigations (all required): L reduction is edge-gated (`edge_weight`), hue-banded, blended by `strength`, and off by default (`l_reduce = 0`). The C reduction is the safe default; L reduction is opt-in.
- **Over-desaturation false colour** on genuine purple/magenta subjects (flowers, sunsets) — same tradeoff class as FOTLAB-RENDER-000003. Mitigations: the **edge gate** ensures a uniform purple patch (no `|∇L|`) is never touched, so only the *edge* fringe is acted on; the hue band limits scope; `strength` lets the user dial back. These tradeoffs are repeated in the module doc comment and every user-facing description.
- **Oklch advantage is the reason we do this here, not in RGB/Lab** — cite Ottosson (2020): hue linearity and the "blue does not fold inwards" constraint keep C/h behaviour stable precisely in the purple band where CIELAB is worst.
- **Cost.** Per-pixel Oklch math reuses the existing conversion (no extra round trip); edge detection needs a small L-neighbourhood gradient (e.g. 3×3 Sobel/Roberts) and must be rayon-parallel like the rest of the module. For a 100 MP image this is O(W·H) with a small constant; acceptable on desktop, same stripe consideration as FOTLAB-RENDER-000003 if memory-bound.
- **Interaction / redundancy with other CA passes.** This pass overlaps conceptually with FOTLAB-RENDER-000003 (pre-demosaic ACA) and FOTLAB-RENDER-000004 (shearlet post-demosaic). Default: all three are independent toggles the user controls; we do **not** auto-disable. An Open Question records whether to offer an "auto-exclusive" mode.

## Acceptance Criteria

- A synthetic demosaiced RGB image with a high-contrast edge carrying a magenta fringe (high C, hue in the purple band, bright edge) has that fringe neutralized (C reduced; L pulled at the edge when `l_reduce > 0`) after `defringe_oklab`, and is left unchanged when `strength == 0`, `enabled == false`, or settings `None`.
- Mirror case: a bright two-tone edge carrying a green fringe (h in the green band) at a dark edge has C reduced there.
- A genuine **uniform** purple patch (high C, purple hue, but **no** `|∇L|` edge) is NOT repaired (edge gate holds) — real purple subject preserved; only its fringe is, and even there the C reduction keeps it from going gray.
- Legitimately bright, saturated, non-fringe content (e.g. a smooth blue-sky gradient with low `|∇L|`) is minimally affected (edge + hue gates).
- `enabled == false` / `strength == 0` / `None` ⇒ exact identity (output equal to input).
- No `Cargo.lock` and no personal-info strings are added by this change.
- The module does not read or require the pre-demosaic LCA/ACA outputs; with those disabled, `defringe_oklab` still corrects the fringe (proves independence).

## Impacted Modules

- `app/src/binding/rust/rawler_fotlab/src/calibrate_oklab.rs` (extend) — add `defringe_oklab` (and an Oklch helper exposing `C = √(a²+b²)`, `h = atan2(b,a)`), reusing `D65XYZ2OKLab` / `OKLab2D65XYZ` and the `OKLAB_M1/M2` constants.
- `app/src/binding/rust/rawler_fotlab/src/lib.rs` — `pub use calibrate_oklab::defringe_oklab;` (and settings type).
- `app/src/binding/rust/rawler_fotlab/src/develop.rs` (or the existing OKLab-pass wiring) — call `defringe_oklab` within/immediately after the highlight-compression pass (same camera-space Oklch buffer).
- `app/src/binding/rust/rawler_fotlab/src/defringe.rs` (new) or within `calibrate_oklab.rs` — FFI `DefringeOklabSettings` (uniffi Record) + `DevelopParams.defringe` (`Option`).
- `rules/DESIGN/index.md` — this row; `rules/DESIGN.md` — add `DEFRNG` to the category table.

## Open Questions

- **Default `l_reduce`.** Ship `l_reduce = 0` (C-only, safest) or a small nonzero (also pull bright overshoot)? The C-only default avoids the dark-notch risk entirely; the L pull is the part that needs field testing.
- **Hue bands.** Fixed Oklch-degree ranges (e.g. purple ≈ 280°–340°, green ≈ 100°–160°) or per-image adaptive? Width/tolerance tunable from real fringe samples.
- **Interaction mode.** Independent toggles (default) vs. an "auto-exclusive" option that disables the pre-demosaic ACA (FOTLAB-RENDER-000003) when this post-demosaic defringe is on, to avoid double correction.
- **Merge vs. separate function.** Fold `defringe_oklab` into the existing highlight-compression function (single Oklab pass over the buffer) or keep it a distinct function in the same module? Single pass is cheaper; separate is clearer.
- **Kotlin UI toggle.** Wiring a Studio defringe on/off + `DefringeOklabSettings` is a separate UI task (out of scope here), mirroring the `loca` wiring in FOTLAB-RENDER-000003.

## Change History

- 2026-10-10 — Created (Draft). Design for `defringe_oklab`: a post-demosaic Oklch defringe extending `calibrate_oklab.rs` (FOTLAB-RENDER-000001). Detects bright edges (`|∇L|`) carrying high C in purple/green hue bands; reduces C (primary, desaturate-toward-neutral) and optionally L (edge-gated, opt-in). Based on research: Oklch suitability in the blue/purple band (Ottosson 2020), Unpurple's desaturate-toward-gray, and DCA-LUT (2025) luminance correction conditioned on fringe gradient + edge geometry.
