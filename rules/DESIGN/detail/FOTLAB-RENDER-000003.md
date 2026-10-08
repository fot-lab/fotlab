# Longitudinal CA (LoCA) Fringe Correction (Purple + Green) — Pre-Demosaic, Independent Edge Detection, G-Plane-Only Repair

- ID: FOTLAB-RENDER-000003
- Status: Draft
- Priority: P2
- Created: 2026-10-05
- Owner: —
- Related: FOTLAB-RAWLER-000011 (LCA / `CA_correct_RT` port), RAWTRP-SURVEY-000005 / -000006 (public LoCA / defringe survey), FOTLAB-RENDER-000002 (highlight recovery — distinguishes LoCA from clipped-G highlight fringe)

## Background & Goal

Lateral CA (LCA) — the geometric R/B-vs-G shift — is already corrected pre-demosaic by
`rawtrp_correct::correct_ca_bayer` (the RawTherapee `CA_correct_RT` port, `FOTLAB-RAWLER-000011`).
That stage only rewrites the R/B planes by a radial/measured shift; it does **not** address
**longitudinal (axial) CA**: because R, G and B focus at slightly different distances, at
high-contrast edges a **magenta fringe** (R and B both high relative to G) bleeds into the edge.
This is the classic "purple fringe".

This item adds a second, independent pre-demosaic stage `correct_loca_bayer` that detects that
fringe from the mosaic and neutralises it by **raising the green channel** (the RapidRAW
`recover_clipped_pixel` strategy) rather than desaturating R/B. Goal: remove purple fringe on
strong lenses / high-contrast edges without producing the dull gray "昏暗灰边" that a
desaturate-R/B repair would.

## Requirement

1. **Runs after LCA.** `correct_loca` is invoked immediately after `correct_ca` in the develop
   pipeline, before demosaic (`develop.rs`, between the `ca` and `demosaic` steps). It therefore
   sees the already-laterally-corrected mosaic.
2. **Independent edge detection — always re-run.** The fringe is strictly edge-local. LoCA runs
   its OWN high-contrast edge detection (a local G-gradient magnitude) on every render and does
   **not** reuse the LCA `detect_ca` result. Rationale (explicit user constraint): the user may
   toggle LCA and LoCA independently (LCA off + LoCA on, or vice versa), so LoCA must be fully
   self-contained and correct whether or not LCA ran. Reusing LCA's detection would make LoCA's
   behaviour silently depend on LCA's switch.
3. **Detection + repair — two BOUND criteria+behaviour pairs.** Criteria and
   behaviour are one unit: a pair switch that is off means neither its criteria
   nor its behaviour runs.
   * **Purple pair (去紫边), per G position:**
     1. Estimate R and B from the 4 orthogonal CFA neighbours (the orthogonals
        of a G position are the R/B photosites).
     2. Edge weight = `smoothstep(LOCA_EDGE_LO, LOCA_EDGE_HI, |G gradient|)` over
        the diagonal G neighbours — correction only near high-contrast edges.
     3. Gate: `magenta = min(r,b) − g > 0` AND
        `lum = max(r,g,b) > purple_lum_min (default 0.5)`.
     4. Raise G (RapidRAW `recover_clipped_pixel`, src-tauri/src/raw_processing.rs):
        `target_g = min(r,b)*0.8 + (r+b)*0.5*0.2`; `correction = (target_g − g).max(0)`;
        `dg = correction * smootherstep(purple_lum_min,1.5,lum) * smoothstep(0,0.25,magenta/lum) * edge_weight`,
        plus a residual `raise_g` pass; multiply by user `strength` (0..1). Add
        `dg` to the G plane.
   * **Green pair (去绿边) — the mirror, at the same G positions:** gate
     `excess = g − max(r,b) > 0` (same R/B estimates) AND
     `lum > green_lum_min (default 0.5)`; behaviour: **lower G toward max(r,b)** —
     `target_g = max(r,b)*0.8 + (r+b)*0.5*0.2` (the mirrored RapidRAW hedge;
     sits above min(r,b), so the lower can never overshoot into magenta), same
     blend weights, same residual lower pass. The two gates are mutually
     exclusive, so at most one branch fires per position.
4. **Passable luminance thresholds.** `purple_lum_min` / `green_lum_min` are
   caller-supplied parameters (explicit user requirement), default **0.5** each
   (enforced as uniffi defaults on `LocaSettings`); when the caller passes a
   value, that value is used (clamped to 0..1 in the kernel). Both live in the
   pre-WB raw-linear domain (see Constraints).
5. **Mapping to the mosaic — G plane only, R/B never touched (explicit user
   constraint, 2026-10-05).** The purple pair raises G; the green pair lowers G;
   both evaluate at G positions. The R/B planes are left byte-identical in every
   case — R/B is not moved as a "clever" alternative repair. The two pairs are
   PEER branches under the master switch (`purple_delta` / `green_delta` are
   siblings sharing one R/B estimate and one edge weight), not nested.
   Documented as an approximation (see Constraints).
6. **Three gate switches.** `LocaSettings` carries (all uniffi-default on):
   `enabled` — the **master switch**: `false` short-circuits the entire LoCA
   stage (no edge detection, no criteria, no repair), regardless of the pair
   switches (`DevelopParams.loca == None` short-circuits identically);
   `purple_enabled` — the 去紫边 pair switch; `green_enabled` — the 去绿边 pair
   switch. `strength` (default 1.0) scales both pairs.
7. **Bayer only.** Non-Bayer / four-colour / odd-width CFAs degrade to the uncorrected mosaic
   (log::warn + pass through), exactly like LCA.

## Constraints

- **Domain is pre-WB.** LoCA runs where LCA runs — after dehaze/exposure, **before** white
  balance (WB is applied later in `calibrate`). The RapidRAW source runs post-demosaic (WB
  applied). Therefore `purple_lum_min` / `green_lum_min` (default 0.5) and the `0.8 / 0.2`
  weights are interpreted in **raw-linear, pre-WB** space: the raw (camera-native) R/B vs G
  balance, not the perceptually white-balanced one. This is the stable choice (thresholds do
  not move with the WB setting), but it is an approximation — see Open Questions.
- **Disadvantage — documented tradeoffs, asymmetric by design (explicit
  requirement).** The purple pair raises the *deficient* G: it keeps R/B intact
  and *brightens* the fringe toward neutral, so it does not create the dull/gray
  "昏暗灰边" that pulling R/B down would; the cost is **false colour** at
  genuinely-magenta content (a real purple flower, garment, or sunset). The
  green pair lowers the *excess* G (user-directed behaviour, 2026-10-05 — R/B
  must not be moved): it keeps R/B intact so no chroma is injected into R or B,
  but lowering G *darkens* the fringe, so the mirror risk is a **darker/dull
  edge** at genuinely-green content — the exact disadvantage the purple raise
  avoids. In both cases the user can disable LoCA entirely (master switch) or
  either pair individually. These tradeoffs are repeated in the module's doc
  comment and in every user-facing description.
- **Memory.** The implementation uses one O(W·H) `f32` signed delta buffer
  (positive = purple raise, negative = green lower; both branches act on the G
  plane). For a 100 MP full-frame mosaic this is ~400 MB. Acceptable on desktop;
  a candidate future optimisation is to process in row stripes. Not a
  correctness issue.
- **Parallelism / safety.** Correction deltas are computed per G position into a
  disjoint delta buffer (rayon over rows), then applied sequentially. Writes are
  disjoint. LoCA runs strictly AFTER LCA in the pipeline (LCA rewrites R/B by
  radial shift; LoCA only ever touches the G plane) — sequential stages, no
  cross-stage coupling.
- **Independent of LCA's detector.** LoCA must not read `detect_ca` output; it recomputes
  everything from the current mosaic.

## Acceptance Criteria

- A synthetic Bayer mosaic with a high-contrast edge carrying a magenta (min(r,b) > g, bright)
  fringe has the fringe neutralised (post-demosaic R/G/B closer to neutral) after
  `correct_loca_bayer`, and is left unchanged when `strength == 0` or `loca == None`.
- The mirror case: a bright two-tone edge carrying a green excess (g > max(r,b), bright) at
  G positions has those G positions lowered toward max(r,b) after `correct_loca_bayer`.
- R/B planes are byte-identical before and after `correct_loca_bayer` in every scenario
  (explicit user constraint — LoCA only ever touches G).
- Pair switches are bound: with `purple_enabled == false` no G position is ever raised; with
  `green_enabled == false` no G position is ever lowered; with both off (or the master
  `enabled == false`, or `loca == None`) the stage is an exact identity.
- With `loca` enabled and LCA disabled, LoCA still detects and repairs the fringe (proves the
  detector is independent of LCA).
- Non-Bayer / odd-width input returns `Error` and the orchestration passes the mosaic through
  unchanged (degrade, not fail).
- No `Cargo.lock` and no personal-info strings are added by this change.
- A genuine uniform-magenta patch (no edge) is NOT repaired (edge gate holds), so legit purple
  content is not silently desaturated — only its *edge* fringe is, and even there the raise-G
  path keeps it from going gray.

## Impacted Modules

- `app/src/binding/rust/rawtrp_correct/src/ca_correction_aca.rs` (new) — `correct_loca_bayer`,
  `LocaParams`, `Error`.
- `app/src/binding/rust/rawtrp_correct/src/lib.rs` — `pub mod ca_correction_aca;`.
- `app/src/binding/rust/rawler_fotlab/src/loca.rs` (new) — FFI orchestration `correct_loca`,
  `LocaSettings` (uniffi Record).
- `app/src/binding/rust/rawler_fotlab/src/lib.rs` — `mod loca;`.
- `app/src/binding/rust/rawler_fotlab/src/develop.rs` — `DevelopParams.loca` field; call
  `correct_loca` after `correct_ca`.
- `rules/DESIGN/index.md` — this row.

## Open Questions

- **WB domain.** Thresholds are pre-WB (raw-linear). A post-WB variant would match RapidRAW
  exactly but requires running LoCA after `calibrate` (post-demosaic), which breaks the
  "pre-demosaic, independent-of-LCA-detection" shape and the CFA-mosaic contract. Keep pre-WB;
  revisit only if field testing shows the pre-WB gate mis-fires on strongly-WB'd sensors.
- **Kotlin UI toggle.** The `loca` field exists and is `Option` (default `None`), so existing
  Kotlin `DevelopParams(...)` call sites stay compile-green without changes. Wiring a Studio
  LoCA on/off control + passing `LocaSettings` is a separate UI task (out of scope here).
- **Thresholds `LOCA_EDGE_LO/HI`.** Chosen in 0..1 gradient units (0.01 / 0.08). Tunable; field
  testing on real purple-fringe samples may widen/narrow the edge band.

## Change History

- 2026-10-05 — Created (Draft). Spec for the standalone LoCA / purple-fringe pre-demosaic stage:
  independent edge detection (never reuses LCA's `detect_ca`), runs after LCA, RapidRAW
  min(r,b)>g + luminance gate with raise-G repair, and the documented false-colour-vs-dark-edge
  tradeoff.
- 2026-10-05 — Revised (Draft). Split the stage into two BOUND criteria+behaviour pairs with
  their own switches: 去紫边 (`min(r,b)>g` + `lum > purple_lum_min` → raise G) and 去绿边
  (`g > max(r,b)` + `lum > green_lum_min` → raise R/B, the mirrored hedge
  `target_c = g*0.8 + max(c,o)*0.2`); both thresholds caller-passable, default 0.5; master
  switch `enabled` short-circuits the whole stage. Tradeoff extended to both pairs; two delta
  buffers (~800 MB @ 100 MP) noted in Memory.
- 2026-10-05 — Revised again (Draft, user-directed). 去绿边 behaviour corrected to **lower G**
  (was raise-R/B): LoCA never touches the R/B planes in any scenario (explicit constraint),
  the two pairs are PEER branches under the master switch (`purple_delta`/`green_delta`
  siblings sharing one estimate + one edge weight, no deep nesting), and both evaluate at G
  positions with a single signed delta buffer (~400 MB @ 100 MP). Green-pair tradeoff
  restated: keeps R/B intact but darkens the fringe (mirror risk of a darker/dull edge).
