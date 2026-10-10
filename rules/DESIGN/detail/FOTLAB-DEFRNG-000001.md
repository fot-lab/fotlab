# OKLab Post-Demosaic Defringe — Purple-Quadrant Reconstruction (Oklch C/h as auxiliary)

- ID: FOTLAB-DEFRNG-000001
- Status: Draft
- Priority: P2
- Created: 2026-10-10
- Owner: —
- Related: FOTLAB-RENDER-000001 (OKLab highlight-chroma compression — same `calibrate_oklab` round trip, the module owns the OKLab pipeline); FOTLAB-RENDER-000003 (pre-demosaic ACA / purple-fringe `correct_loca_bayer`); FOTLAB-RENDER-000004 (post-demosaic shearlet unified CA); research basis: mjambon/purple-fringe "Unpurple" (GitHub); Ottosson, *A perceptual color space for image processing* (2020); Lu et al., *DCA-LUT* (arXiv:2511.12066, 2025); RawTherapee/ART Defringing; GIMP PurpleFringe script.

## Background & Goal

The OKLab/Oklch stage already runs **post-demosaic, post-WB, in camera space** (`rawler_fotlab/src/calibrate_oklab.rs`, FOTLAB-RENDER-000001) and currently only does highlight-chroma compression. This item extends that same pass with a **defringe** step.

**Pipeline reality (important for this design):** the working colour space of this pipeline is **OKLab as the main path** — operations are expressed on the `(L, a, b)` axes. The Oklch values **`C` (chroma = √(a²+b²))** and **`h` (hue = atan2(b,a))** are *auxiliary* values **derived from (a,b)**: `C` is used for gating / measuring "how much was removed", and `h` is **rarely used**. Purple-fringe localization is therefore done with the **purple quadrant + a/b slope**, *not* with Oklch hue-angle bands — this also sidesteps angle/radian wrap-around (0°/360°, −90°/+270°).

The defringe itself follows Unpurple's "reconstruct-and-subtract" idea, translated into OKLab (the pipeline's native space):

Rationale, from the research:

- Reducing chroma (desaturate toward neutral) is the classic, safe defringe action — exactly what `mjambon/purple-fringe` ("desaturate toward gray", with channel-lower-bound safety) and RawTherapee/ART's Lab defringe do. In OKLab this is a **quadrant-clamped** subtraction: pull `(a,b)` back toward the origin but never cross into the green (`a < 0`) or yellow (`b > 0`) half — the result can only become neutral grey, never a wrong hue. This inherently reduces `C`; `C` itself is only an auxiliary read-out.
- Reducing **`L`** is also well-founded: *DCA-LUT* (2025) frames purple fringing as **edge-localized** and explicitly performs a **luminance correction** conditioned on the fringe gradient `|∇|` and edge geometry. A bright fringe is a luminance overshoot at a high-contrast edge, so pulling `L` down is physically appropriate — provided it is edge-gated and gentle. (Here `L` is OKLab `L`; `h` is not involved.)
- **Why the a/b slope, not hue-angle `h`.** Purple is the `a > 0, b < 0` quadrant of OKLab (a = red−green, b = yellow−blue). With the quadrant clamp fixing the signs, the *balance* between red and blue is fully described by the **a/b slope** `s = a/(−b)`, whose two ends (steeper `s > 1` = redder, shallower `s < 1` = bluer) are distinguished by *which axis dominates* — no angle arithmetic needed, so `h` is rarely consulted. (Ottosson 2020's hue-linearity is a bonus of OKLab itself, already our space; it is not the reason we avoid a separate CIELAB stage.)
- **Complementary**, not a replacement, to the pre-demosaic ACA (`correct_loca_bayer`, raise-G, FOTLAB-RENDER-000003): that stage works in the CFA mosaic and only raises/lowers G; a post-demosaic OKLab pass can catch residual fringe the mosaic stage misses, in the pipeline's native space.

## Requirement

1. **Reuse the existing OKLab round trip.** The defringe function lives in `calibrate_oklab.rs` (the module "owns the entire OKLab round trip") and operates on OKLab `(L,a,b)` pixels derived from the camera-space triple via the existing `D65XYZ2OKLab` / `OKLab2D65XYZ` (and the standard Ottosson constants already defined there). `C` and `h` are derived from `(a,b)` as auxiliary values; no second conversion is introduced.
2. **Detection — gates (criterion + behaviour as one unit).** A candidate fringe pixel must satisfy:
   * **Bright edge** — `|∇L| > edge_threshold` (local L gradient magnitude; edge localization, mirroring DCA-LUT's edge-geometry cue and Unpurple's brightness mask).
   * **Purple quadrant + 综合紫度** — `a > 0` and `b < 0` (purple quadrant), with combined purple intensity `P = max(a,0) + max(−b,0)` above a mask threshold. Blur `P` first (radius ≈ 5 px, same tent/box blur as `unpurple.ml`) to mimic short-wavelength (purple/UV) defocus; the blurred `P_blur` is the approximate artificial-fringe mask.
   * **a/b slope band** — residual slope `s = a/(−b)` within `[slope_min, slope_max]` (e.g. ≈ 0.7–1.6 for violet→magenta), replacing any hue-angle band.
   * **`C` as auxiliary gate** — optional `C > chroma_threshold` (chroma scalar from `(a,b)`) to suppress near-neutral pixels. `h` (hue angle) is **not** used for primary detection; it is only an optional, rarely-enabled auxiliary cross-check.
   `fringe_weight` is a smooth combination of the 综合紫度 excess (`smoothstep` over mask threshold) and the edge weight, times a slope-match factor (1 at band centre → 0 at band edge).
3. **Repair — two BOUND operations, both scaled by `strength · fringe_weight`:**
   * **Quadrant-clamped OKLab subtraction (primary, always on when enabled):** subtract `Δa, Δb` from `(a,b)` bounded by the blurred `P` mask and clamped so `a' ≥ 0` and `b' ≤ 0` (stay in the purple quadrant — never greenish, never yellowish). `L` untouched, so brightness is preserved (unlike raw RGB subtraction which also dims the pixel). This desaturates the fringe toward neutral and inherently reduces `C`; it is the OKLab analogue of Unpurple's "desaturate toward gray, never below the G-relative lower bound". An **auxiliary explicit `C` reduction** `C' = C·(1 − strength·fringe_weight)` may be applied on top, but the quadrant/slope clamp is the main control.
   * **Reduce `L` (secondary, edge-gated, optional via `l_reduce`):** `L' = L − l_reduce · strength · fringe_weight · edge_weight`, clamped to `[0,1]`. Pulls down the bright-edge luminance overshoot. Off by default (`l_reduce = 0`); enabled only when the user opts in, because un-gated `L` reduction risks dark notches on legitimately bright content.
4. **Passable parameters (`DefringeOklabSettings` record):** `enabled` (master), `strength` (default 1.0), `edge_threshold`, `mask_threshold` (综合紫度), blur `radius` (default 5), `slope_min`/`slope_max` (default ≈ 0.7 / 1.6), auxiliary `chroma_threshold` (`C` gate, optional), `l_reduce` (default 0.0). The `DevelopParams` field is `Option` (default `None` ⇒ identity), so existing call sites stay compile-green.
5. **Graceful degradation.** `enabled == false` (or settings `None`, or `strength == 0`) ⇒ exact identity. If the OKLab/camera-space path is not in use, the orchestration passes through unchanged.
6. **Independent of the pre-demosaic stages.** The module never reads `correct_ca_bayer` / `correct_loca_bayer` output; it recomputes everything from the demosaiced, white-balanced RGB.

## Primary Algorithm — OKLab Reconstruction (综合紫度 + 象限钳制 + a/b 斜率钳制)

The three required mechanisms (Unpurple translated into the pipeline's native OKLab space):

1. **Fringe mask from 综合紫度 (combined purple intensity).** For each pixel compute
   `P = max(a, 0) + max(−b, 0)`
   — the sum of redness (a above neutral) and blueness (−b below neutral). This is the OKLab-native proxy for "how much purple-prone, defocused light is here", replacing Unpurple's blue-channel intensity. Blur `P` with the same tent/box blur (`tent_blur`, radius ≈ 5 px, two box passes) used by `unpurple.ml` to mimic short-wavelength (purple/UV) defocus; the blurred `P_blur` is the approximate artificial-purple-fringe mask.

2. **Subtract with 象限钳制 (quadrant clamp).** The removable purple is bounded so the result stays inside the purple quadrant:
   * never push `a` below 0 (would turn greenish): `da = max(a, 0)` is the max redness we may remove;
   * never push `b` above 0 (would turn yellowish): `db = max(−b, 0)` is the max blueness we may remove.
   Cap `da, db` by `P_blur`, then subtract `Δa` from `a` and `Δb` from `b` (`L` untouched — brightness preserved). This is the direct OKLab analogue of Unpurple's "blue/red may not drop below green" lower-bound safety.

3. **a/b 斜率钳制 (slope clamp) for hue, not angle.** Unpurple's `min/max_red_to_blue_ratio` constrains how much red vs blue to remove. In OKLab we do **not** use a fixed a\*:b\* or R:B ratio constant (it is invalid through the sRGB→Lab pipeline — nonlinear and luminance-dependent). Instead we constrain the residual's *slope* `s = a/(−b)` to a configurable band `[slope_min, slope_max]`. Because the quadrant clamp already guarantees `a ≥ 0` and `b ≤ 0`, the slope's two ends are told apart by *which axis dominates* (steep `s > 1` = redder, shallow `s < 1` = bluer) — there is no 0°/360° or −90°/+270° wrap-around to convert, so we never need angle/radian hue clamping. If `s` would fall outside the band after subtraction, pull the residual back *along the slope* (reduce the larger component) rather than clamping an angle.

**Mirror green fringe.** The same machinery applies to the green fringe (`a < 0`) on dark edges by mirroring the quadrant: detect `a < 0` (with its own slope sign) and clamp `a' ≤ 0`; this is the green counterpart, kept symmetric with the purple path.

## Oklch Auxiliary — C and h as auxiliary values

Although the module exposes Oklch, **`C` and `h` are auxiliary, not the operating axes**:

- **`C = √(a²+b²)`** is computed from the OKLab `(a,b)` and used only as (a) an optional chroma gate `C > chroma_threshold` to suppress near-neutral pixels, and (b) a read-out of "how much chroma was removed" for diagnostics/acceptance. The *primary* desaturation is the quadrant-clamped `(a,b)` subtraction above, which already reduces `C`.
- **`h = atan2(b,a)`** is **rarely used**. Purple localization is done by the purple quadrant + `a/b` slope; `h` appears only as an optional, rarely-enabled cross-check and is never the primary band selector (this deliberately avoids hue-angle wrap-around handling).

## Constraints

- **Domain is post-demosaic, post-WB, camera space** — identical to `calibrate_oklab.rs`'s contract (camera triple in, camera triple out, before `cam2rgb`). The main path operates on OKLab `(L,a,b)`; `C` and `h` are auxiliary Oklch values derived from `(a,b)`. This is the inverse of the pre-demosaic ACA's pre-WB raw-linear domain (FOTLAB-RENDER-000003) and must be documented as such.
- **Reducing `L` is the dangerous half.** Un-gated `L` reduction darkens legitimately bright, saturated (but correct) content. Mitigations (all required): `L` reduction is edge-gated (`edge_weight`), slope/quadrant-banded, blended by `strength`, and off by default (`l_reduce = 0`). The `(a,b)` subtraction is the safe default; `L` reduction is opt-in.
- **Over-desaturation false colour** on genuine purple/magenta subjects (flowers, sunsets) — same tradeoff class as FOTLAB-RENDER-000003. Mitigations: the **edge gate** ensures a uniform purple patch (no `|∇L|`) is never touched, so only the *edge* fringe is acted on; the quadrant + slope band limits scope; `strength` lets the user dial back. These tradeoffs are repeated in the module doc comment and every user-facing description.
- **OKLab is the main path; `C`/`h` are auxiliary.** We operate on `(a,b)` directly and avoid hue-angle banding (use quadrant + a/b slope). The hue-linearity of OKLab (Ottosson 2020) is a bonus of already being in this space, not a reason to add a separate CIELAB stage.
- **Cost.** Per-pixel OKLab math reuses the existing conversion (no extra round trip); edge detection needs a small L-neighbourhood gradient (e.g. 3×3 Sobel/Roberts) and must be rayon-parallel like the rest of the module. For a 100 MP image this is O(W·H) with a small constant; acceptable on desktop, same stripe consideration as FOTLAB-RENDER-000003 if memory-bound.
- **Interaction / redundancy with other CA passes.** This pass overlaps conceptually with FOTLAB-RENDER-000003 (pre-demosaic ACA) and FOTLAB-RENDER-000004 (shearlet post-demosaic). Default: all three are independent toggles the user controls; we do **not** auto-disable. An Open Question records whether to offer an "auto-exclusive" mode.

## Acceptance Criteria

- A synthetic demosaiced RGB image with a high-contrast edge carrying a magenta fringe (`a > 0, b < 0`, high `C`, bright edge) has that fringe neutralized (the `(a,b)` pair pulled toward neutral while staying in the purple quadrant; `L` pulled at the edge when `l_reduce > 0`), and is left unchanged when `strength == 0`, `enabled == false`, or settings `None`.
- Mirror case: a bright two-tone edge carrying a green fringe (`a < 0`) at a dark edge has its `(a,b)` pulled back within the green quadrant.
- A genuine **uniform** purple patch (high `C`, purple quadrant, but **no** `|∇L|` edge) is NOT repaired (edge gate holds) — real purple subject preserved; only its fringe is, and even there the `(a,b)` subtraction keeps it from going gray.
- Legitimately bright, saturated, non-fringe content (e.g. a smooth blue-sky gradient with low `|∇L|`) is minimally affected (edge + quadrant + slope gates).
- `enabled == false` / `strength == 0` / `None` ⇒ exact identity (output equal to input).
- `L` is unchanged by the primary `(a,b)` subtraction (brightness preserved); only the opt-in `L` reduction touches `L`.
- No `Cargo.lock` and no personal-info strings are added by this change.
- The module does not read or require the pre-demosaic LCA/ACA outputs; with those disabled, `defringe_oklab` still corrects the fringe (proves independence).
- **Regression baseline:** on the `external/purple-fringe` example images, the OKLab path's output matches Unpurple's within tolerance (the 综合紫度 + 象限钳制 + a/b 斜率钳制 logic is the OKLab port of Unpurple).

## Impacted Modules

- `app/src/binding/rust/rawler_fotlab/src/calibrate_oklab.rs` (extend) — add `defringe_oklab` operating on OKLab `(L,a,b)` (reusing `D65XYZ2OKLab` / `OKLab2D65XYZ` and `OKLAB_M1/M2`), exposing `C = √(a²+b²)` and `h = atan2(b,a)` as auxiliary helpers.
- `app/src/binding/rust/rawler_fotlab/src/lib.rs` — `pub use calibrate_oklab::defringe_oklab;` (and settings type).
- `app/src/binding/rust/rawler_fotlab/src/develop.rs` (or the existing OKLab-pass wiring) — call `defringe_oklab` within/immediately after the highlight-compression pass (same camera-space OKLab buffer).
- `app/src/binding/rust/rawler_fotlab/src/defringe.rs` (new) or within `calibrate_oklab.rs` — FFI `DefringeOklabSettings` (uniffi Record) + `DevelopParams.defringe` (`Option`).
- `rules/DESIGN/index.md` — this row; `rules/DESIGN.md` — add `DEFRNG` to the category table.

## Open Questions

- **Default `l_reduce`.** Ship `l_reduce = 0` (C/(a,b)-only, safest) or a small nonzero (also pull bright overshoot)? The (a,b)-only default avoids the dark-notch risk entirely; the `L` pull is the part that needs field testing.
- **a/b slope band `[slope_min, slope_max]`.** Defaults derived from purple samples (target violet–magenta ≈ 0.7–1.6); expose as user-facing alongside the detection, or fix internally?
- **Auxiliary `C` gate / explicit `C` reduction.** Keep `C` purely as a gate + diagnostic, or also apply the explicit `C' = C·(1−…)` on top of the quadrant subtraction? The quadrant subtract already reduces `C`; a second explicit step may be redundant.
- **Interaction mode.** Independent toggles (default) vs. an "auto-exclusive" option that disables the pre-demosaic ACA (FOTLAB-RENDER-000003) when this post-demosaic defringe is on, to avoid double correction.
- **Merge vs. separate function.** Fold `defringe_oklab` into the existing highlight-compression function (single OKLab pass over the buffer) or keep it a distinct function in the same module? Single pass is cheaper; separate is clearer.
- **Kotlin UI toggle.** Wiring a Studio defringe on/off + `DefringeOklabSettings` is a separate UI task (out of scope here), mirroring the `loca` wiring in FOTLAB-RENDER-000003.

## Change History

- 2026-10-10 — Created (Draft). Design for `defringe_oklab`: a post-demosaic OKLab defringe extending `calibrate_oklab.rs` (FOTLAB-RENDER-000001). Detects bright edges (`|∇L|`) carrying high C in purple/green hue bands; reduces C (primary, desaturate-toward-neutral) and optionally L (edge-gated, opt-in). Based on research: Oklch suitability in the blue/purple band (Ottosson 2020), Unpurple's desaturate-toward-gray, and DCA-LUT (2025) luminance correction conditioned on fringe gradient + edge geometry.
- 2026-10-10 — Reoriented to the pipeline reality: **OKLab is the main path** (operations on `(L,a,b)`); **Oklch `C` and `h` are auxiliary** (derived from `(a,b)` — `C` for gating/measurement, `h` rarely used). Primary algorithm is the OKLab port of Unpurple: fringe mask via 综合紫度 `P = max(a,0) + max(−b,0)` (blurred), 象限钳制 (`a' ≥ 0, b' ≤ 0`) as the OKLab analogue of Unpurple's channel-lower-bound safety, and a/b 斜率钳制 `a/(−b) ∈ [slope_min, slope_max]` for hue (no angle/radian wrap-around, since quadrant clamp supplies signs). Recorded that R:B (and a\*:b\*) does NOT map to a fixed ratio (nonlinear + luminance-dependent), so no ratio constant is carried over; added mirror green-fringe handling and a `external/purple-fringe` regression baseline.
