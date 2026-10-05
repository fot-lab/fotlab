# Highlight recovery (HLRecovery) port feasibility — RawTherapee's five variants in two tiers; the per-pixel tier fits the OKLab camera-space fold

- ID: FOTLAB-RENDER-000002
- Status: Draft
- Priority: P2
- Created: 2026-10-04
- Owner: —
- Related: FOTLAB-RENDER-000001 (OKLab highlight-chroma compression bypass), FOTLAB-RAWLER-000018 (magenta root cause, REVIEW), RAWTRP-SURVEY-000005 (LOCA in the OKLab bypass, STRUCT)

## Background & Goal

Our OKLab highlight-chroma compression (`FOTLAB-RENDER-000001`) desaturates near-clipped highlights in camera space (post-WB, pre-`cam2rgb`, per-pixel round trip). It **hides** the frozen magenta hue of clipped highlights but does **not reconstruct** the true channel value, because once the `cam2rgb` matrix runs on a clipped camera-RGB triple the true value is irreversibly lost (linear mix, non-invertible). RawTherapee avoids the artifact class entirely with its HLRecovery stage.

Goal of this study: determine whether fotlab can add or port RT's HLRecovery, at which pipeline position, and in which computational model. Research only — this item records findings and verdicts, no implementation.

## Research Findings

### RT has five variants in two tiers

| Tier | Method | Call position | Computational model |
| --- | --- | --- | --- |
| Area-based | `HLRecovery_inpaint` ("Color", `hilite_recon.cc:303`) | post-demosaic, **pre-WB**, camera RGB (`rawimagesource.cc:874`) | full-image `boxblur2` + global `chmax`/`clmax` reduction + 3×3 chrominance propagation |
| Area-based | `highlight_recovery_opposed` ("Coloropp", `hilite_recon.cc:1348`) | post-demosaic, **pre-WB** (`rawimagesource.cc:877`) | 3×3 mean → power-curve refavg from the other two channels, mask dilation, global chrominance average over the transition band |
| Per-pixel | `HLRecovery_Luminance` (`rawimagesource.cc:3803`) | **post-WB, pre-`cam2rgb`** (`rawimagesource.cc:992-995`) | opponent `C = √3(r−g)`, `H = 2b−r−g`; ratio = √((Co²+Ho²)/(C²+H²)) between frozen and true triple; rebuild RGB with **true** `L = r+g+b` |
| Per-pixel | `HLRecovery_CIELab` (`rawimagesource.cc:3841`) | post-WB, pre-`cam2rgb` | true `Y` from the over-max triple via `xyz_cam`; frozen `x,y,z` stems; `fx = fy + x − y`, `fz = fy − y + z`; `f2xyz` back; `cam_xyz` back to camera RGB (~25 lines) |
| Per-pixel | `HLRecovery_blend` (`rawimagesource.cc:3673`, dcraw-derived) | post-WB, pre-`cam2rgb` | chroma ratio between frozen and triple in opponent space + fractional blending + final desaturation |

All three per-row variants were **verified purely per-pixel**: the loop body touches only the current pixel; parameters are `maxval` (fixed 65535), `hlmax` (O(1) from sensor levels × WB), and the two camera matrices. No neighbourhood access, no image reduction.

### Shared philosophy of the per-pixel tier

**True luminance from the over-max triple + chromaticity from the frozen triple.** RT defers clipping when HLRecovery is enabled (`rawimagesource.cc:864`: `doClip` requires `!hrp.hrenabled`), so over-white values survive to the recovery stage — the lightness information pushed past white by WB is still present and is used as truth; the frozen `(min(ch, maxval), …)` triple contributes what the display could represent.

### Deferred-clip prerequisite is structurally satisfied in fotlab

- WB multiply (`calibrate.rs:193-195`) pushes `wb > 1` channels over 1.0 — information preserved.
- Fold output is explicitly unclamped (`calibrate.rs:206`); final clamp happens only at `bound`/encode (`calibrate.rs:111-116`).
- The one discipline to keep: **no clamp between the WB multiply and the recovery point** (currently none exists).

### Unit and data mapping (all inputs already exist)

| RawTherapee | fotlab |
| --- | --- |
| `maxval = 65535` (fixed pre-WB white reference) | `1.0` post-WB |
| `hlmax[c] = clmax[c] · rm` (per-channel post-WB clip ceiling) | `wb[c]` (`calibrate.rs:100-106`) |
| `xyz_cam` / `cam_xyz` | `cam2xyz` / `xyz2cam_eff`, **already built inside the fold** (`calibrate.rs:138-158`) |

## Feasibility Verdict

1. **The pre-WB position (post-demosaic, before WB and `cam2rgb`) is reachable only by the area tier.** It requires a separate buffered stage (full-image buffer + box blur / 3×3 + reduction). Still camera-space in/out and pipeline-transparent, but it breaks the pure per-pixel fold.
2. **The per-pixel tier slots directly into the existing OKLab fold at its current position** (post-WB, pre-`cam2rgb`). The fold already computes true `L` from the over-max triple; the RT methods would replace the heuristic smoothstep chroma scaling with principled frozen-chroma reconstruction. In OKLab coordinates this is natural: keep true `L`, anchor `a,b` to the frozen triple.
3. **Limitation**: the per-pixel tier cannot repair fully blown regions (2-3 physically saturated channels) — only `inpaint` neighbourhood propagation can. A hybrid (per-pixel first, area stage later) mirrors the structure recommended in `RAWTRP-SURVEY-000005`.
4. **License**: `hilite_recon.cc` and `rawimagesource.cc` are GPL-3. Clean-room reimplementation required; the formulas themselves are standard colorimetry (`C = √3(r−g)`, `H = 2b−r−g`, `f2xyz` = CIELab f inverse) and freely implementable — code must not be copied.

## Constraints

- `external/RawTherapee` is a read-only reference (DESIGN.md principle 5); GPL-3 → clean-room reimplementation, no transcribed code.
- The per-pixel arm must stay neighbour-free to preserve the rayon per-pixel design (`calibrate.rs:168-171`).
- No clamp between the WB multiply and the recovery point.
- Matrices must reuse the exact pipeline factors so the untouched-pixel round trip remains the exact identity (design doc C3 of `FOTLAB-RENDER-000001`).
- **A hue-band gate must never be the sole trigger for desaturation/reconstruction in the fold.** A pure `h ∈ [260°,340°]` gate false-positives on legitimate purple objects (real purple flowers, garments, sunsets) because hue only encodes "what colour", not "is this an artifact", and legitimate purple shares the same band as a clipped magenta fringe. Both reference implementations confirm the correct design is clip/edge-gated with hue at most a qualifier: RT triggers on a blur-difference chroma *edge* (`PF_correct_RT.cc:117`) with hue only a strength modulator (`:108`); RapidRAW triggers on `max_c > 0.5` + the magenta signature `min(R,B) − G > 0` (`raw_processing.rs:64,74`), and its `260..340 => purple` tag (`tagging.rs:110`) is content classification, never reaching the recovery path. Our current fold is already safe — its `L`-knee is luminance-gated, not hue-gated. If a hue gate is added, the clip/edge condition stays the **primary** trigger and hue is an `AND` qualifier at most. (See `RAWTRP-SURVEY-000005` Option B caveat.)

## Acceptance Criteria (for any future implementation)

- Untouched pixels (below any intervention threshold) are **bit-identical** to the bypass-off path.
- The camera round trip stays the exact identity for non-highlight pixels.
- A tungsten-like clipped-highlight input (e.g. camera triple ≈ `[1.00, 0.78, 1.00]` class, cf. `FOTLAB-RAWLER-000018`) is driven toward its true hue by reconstruction, not merely desaturated.
- The per-pixel arm performs no neighbour access and no global image reduction (other than O(1) metadata).

## Impacted Modules

- `app/src/binding/rust/rawler_fotlab/src/calibrate.rs` — the fold (insertion point for the per-pixel tier).
- `app/src/binding/rust/rawler_fotlab/src/develop.rs` — parameter surface if a recovery toggle is exposed.
- Possible future separate camera-space stage module if the area tier is pursued.

## Open Questions

- Q1 — Which RT method to port first: the CIELab-equivalent (reconstructed in OKLab coordinates) or the Luminance-equivalent?
- Q2 — Clip detection threshold: any channel > 1.0 post-WB, or a knee that interacts with the existing `OKLAB_KNEE_START = 0.92`?
- Q3 — Per-pixel tier only, or plan the area stage (buffered, pre-WB) from the start?
- Q4 — Verify rawler's `Intermediate` preserves demosaic overshoot above 1.0 (no upstream clamp) across all supported sensors.
- Q5 — Interaction with the `ProPhotoD50` editing branch: recovery in camera space is branch-agnostic (like the fold), but confirm the rawalchemy path wants reconstructed values rather than desaturated ones.

## Change History

- 2026-10-04 — Initial entry. Recorded the two-tier/five-variant map of RT's HLRecovery, the verified per-pixel nature of the Luminance/CIELab/Blend tier, the shared "true luminance + frozen chroma" philosophy, the unit/data mapping to the existing fold, the deferred-clip prerequisite, and the feasibility verdict (per-pixel tier fits the fold; pre-WB position requires an area stage; GPL clean-room required).
- 2026-10-05 — Added a constraint: a hue-band gate (`h ∈ [260°,340°]`) must never be the sole trigger for desaturation/reconstruction in the fold; it false-positives on legitimate purple objects. Verified from both references that the correct design is clip/edge-gated with hue at most a qualifier (RT blur-diff chroma `PF_correct_RT.cc:117` + hue modulator `:108`; RapidRAW `max_c>0.5` + `min(R,B)−G` signature `raw_processing.rs:64,74`; `tagging.rs:110` purple tag is classification-only). The existing `L`-knee is luminance-gated and therefore safe. Cross-linked to `RAWTRP-SURVEY-000005` Option B caveat.
