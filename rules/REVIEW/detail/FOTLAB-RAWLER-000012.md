# Dehaze pipeline audit — shipped no-op at default, hue-preserving multiplicative mask, and exposure-dependent failure

- ID: FOTLAB-RAWLER-000012
- Status: Observation
- Priority: P1
- Created: 2026-09-26
- Owner: —
- Related: FOTLAB-RAWLER-000009 (pre-demosaic dehaze baseline: per-plane scalar haze floor), FOTLAB-RAWLER-000010 (CFA guided-filter feasibility), FOTLAB-RAWLER-000011 (rawtrp_correct pre-demosaic slot)

## Background & Goal

The dehaze stage (`app/src/binding/rust/rawler_fotlab/src/dehaze.rs` + `dehaze_guided_filter.rs`) was refactored into `estimate → merge → apply` (commit `8e05c61`) and the apply step was corrected from a per-channel absolute offset to a multiplicative gain (commit `4ccdb36`, shipped in RC `v2026.09.25.09.25-rc`). This document audits the resulting behaviour end-to-end:

- Does the shipped configuration actually dehaze?
- What does the haze field `h_p` physically mean, and what bounds it?
- Does the merge preserve 2D awareness, or is it a global / per-pixel RGB average?
- How does the pipeline interact with exposure (dehaze runs before the exposure gain in `develop.rs`)?

Findings are grounded in source (`develop.rs`, `StudioEngine.kt`, `dehaze_guided_filter.rs`) and two faithful pure-Python ports of the pipeline (`log/dehaze_ceiling_sim.py`, `log/dehaze_underexposure_sim.py`).

## Finding

### F1. The shipped dehaze is effectively a no-op at default settings

- `StudioEngine.kt` wires `dehazeCeiling = currentDehazePercentile` at all five call sites (lines 204, 496, 713, 826, 845). The App exposes only `currentDehazePercentile` (default `0.01`) and `currentDehazeStrength`; there is **no separate ceiling/intensity input**.
- `dehaze_guided_filter.rs` computes `cap_tail = ceiling.unwrap_or(DEFAULT_TAIL).clamp(0.0, 1.0)` with `DEFAULT_TAIL = 0.01` (lines 74, 111). So at default, `cap_tail = 0.01`.
- The mask `h` is clamped to `≤ 0.01`, and `apply_mask` does `gain = (1.0 - strength * h).max(0.0)`. At `strength = 1.0` the strongest dimming is 1%.
- Verified by `dehaze_ceiling_sim.py`: with `cap=0.01, strength=1.0`, global `mean|Δ| / mean = 1.00%`; the near/far brightness ratio is unchanged (1.664 → 1.664). The feature is invisible unless the user manually raises the percentile — which also raises the floor quantile, conflating two unrelated knobs (magnitude vs cap).

### F2. The multiplicative mask (commit `4ccdb36`, Implemented) preserves hue

- The old form `cleared = (v - h) / (1 - h)` applied a per-channel absolute offset; an equal absolute offset shifts channels of different magnitude by different *relative* amounts → false colour (documented in `dehaze_guided_filter.rs` module docs, lines 24–36).
- The new form `cleared = v · (1 - strength · h)` applies one shared gain across all planes, so channel ratios (hue) are preserved exactly; dehaze appears as a 2D-aware brightness reduction plus the saturation boost that accompanies it.
- Pinned by `dehaze_preserves_channel_ratios` and `dehaze_moves_bayer_pixels_without_brightening_them`; 22/22 unit tests pass locally, CI green. It is a chroma-preserving *heuristic*, not a strict DCP inversion.

### F3. The haze field `h_p` is bounded by the airlight `A` (< 1), not by 1; with uniform medium it collapses to the haze floor `h0`

- Per plane: `dark = box_min(guide)` (local dark channel) refined by a self-guided filter to `h`. Where haze is uniform, `dark ≈ h0` and the guided filter passes the smooth field through, so `h ≈ h0`. Thick-haze patches raise `h` above `h0`; clear patches lower it (module docs, lines 46–58).
- `cap_tail` is the only hard upper bound. At `cap=0.01` the field is dead; at `cap=1.0` the sim reaches mask `max = 0.667` in dense haze (mean 0.395, min 0.157). So "more haze → `h` closer to `A`" holds, but `A < 1` and the soft cap — not 1 — is the ceiling.

### F4. Blue-sky under-dehaze is a chroma-preserving tradeoff, not a bug

- `merge_masks` averages the four plane grids cell-wise into one shared field (lines 246–297). For a blue-dominant sky, `B` carries most energy while `R`/`G` planes carry low local dark-channel values, so their plane estimates of `h` are small. Averaging is pulled toward those small values, so the shared `h` is lower than the `B` plane alone would dictate → the blue channel is under-dehazed.
- This is the direct price of sharing one gain to keep hue stable (F2). Directionally consistent with the sim: near (object, low-`h` planes dominant) dims only 17.2%, far (sky, high-`h`) dims 61.8% at `cap=1.0`.

### F5. The merge preserves 2D awareness — it is an average across colour planes, not across pixels

- `merge_masks` resamples each plane's sub-lattice grid onto the common period grid `ceil(W/period) × ceil(H/period)`, averages cell-wise (divisor = number of planes that reached the cell), then expands each cell to its full `period × period` block (lines 221–297).
- Within a CFA period the four photosites receive the *same* `h`, but the field varies spatially (near vs far get different `h`). So "average" here means averaging the colour-plane estimates, not a global or per-pixel RGB average. Pinned by `merged_mask_is_one_value_per_cfa_cell`. This is exactly the property the `estimate → merge → apply` split was built for.

### F6. Releasing the cap gives strong, visible dehaze (verified)

- `dehaze_ceiling_sim.py`, `cap=1.0, strength=1.0`: global `mean|Δ| / mean = 45.06%`; near dims 17.2%, far dims 61.8%; near/far ratio 1.664 → 0.768 (toward 1.0 = dehazed). At `strength=0.5` the effect halves (22.53% `mean|Δ|`). So the pipeline is functional and strong once the cap is released; the only reason it looks inert in the App is F1.

### F7. Dehaze-before-exposure makes dehaze exposure-dependent → fails on underexposed RAWs

- Pipeline order in `develop.rs`: Denoise → Dehaze → CA → Exposure (dehaze runs on the normalised 0..1 mosaic, before the `2^exposure_ev` gain; field docs lines 18–31, 38).
- The mosaic is normalised by white level, so an underexposed capture occupies only the low sub-range of [0,1]. The local dark channel `h = box_min(...)` therefore scales ~with the capture exposure `g`.
- `dehaze_underexposure_sim.py` (cap relaxed to 1.0 to isolate the effect) on the same scene: `mean(1 - gain) = 0.6660` at `g=1.0` (normal), `0.1665` at `g=0.25` (−2 EV, 0.25×), `0.0400` at `g=0.06` (−4 EV, 0.06×). At −4 EV dehaze is only ~6% as strong as normal → effectively disabled.
- Root cause: haze is estimated from the *recorded* (exposure-scaled) signal, not from scene radiance. The multiplicative gain inherits this scaling.

### F8. Strict DCP with per-channel airlight `A_c` would remove the exposure dependence and over-dark failure (proposal, not yet implemented)

- Standard DCP inversion: `t = 1 − ω·J_dark / A_c`, `J = (I − A_c) / t + A_c`, with `A_c` per channel and scene-radiance-normalised. Working in scene-linear radiance (before the exposure gain, or normalised by `2^exposure_ev`) and keying off `A_c` rather than a shared cap makes dehaze (a) exposure-independent and (b) not under-dehaze foggy/overcast regions.
- Prior session's oracle study (`log/airlight_probe_oracle_t.py`): in uniform haze `A_c` is shared across the frame; global DCP / brightest-pixel overestimates `A_c` in foggy/overcast scenes (a bright object pollutes the estimate) and needs a haze-volume gate; per-patch recovery mitigates. How `A_c` is supplied is the open decision.
- This can coexist with the current multiplicative mask: use `A_c`-derived magnitude for the haze estimate, keep the shared per-plane multiplicative gain for chroma stability.

## Impact / Conflict

- **Functional (F1, F6):** at shipped defaults the Dehaze control does almost nothing. Users must discover they must raise the percentile, which conflates magnitude and cap.
- **Correctness (F7):** underexposed RAWs are not dehazed — a real-world failure mode (night / high-speed / high-ISO haze shots).
- **Chroma vs recovery tradeoff (F4):** blue-sky under-dehaze is accepted behaviour of the hue-preserving design; stronger blue recovery would require breaking the shared gain (conflicts with F2).
- No conflict with other review items beyond the related baseline / feasibility docs (`000009`, `000010`, `000011`).

## Recommendation

1. **Decouple `dehazeCeiling` from `currentDehazePercentile` in `StudioEngine.kt`** (5 sites): the ceiling is the guided soft-mask cap and should default to `1.0` for the guided branch; magnitude stays with `strength`. Either add a dedicated ceiling/intensity control or hardcode `cap=1.0` for the guided branch. This alone turns the shipped Dehaze feature on (F1 → F6).
2. **Add a regression test** pinning "default percentile must NOT clamp the guided mask to ~0" (ceiling default = 1.0), so the no-op regression cannot return.
3. **Decide and implement `A_c` estimation** for an exposure-independent DCP inversion (F8); decide whether dehaze runs before or after exposure (or normalise the estimate by `2^exposure_ev`). Keep the multiplicative per-plane gain for chroma stability.
4. **Document the blue-sky under-dehaze tradeoff (F4)** in the feature copy; if stronger blue recovery is desired, evaluate a small `A_c`-aware per-channel scaling instead of a single shared gain.

## Change History

- 2026-09-26 — Created as an Observation from the dehaze pipeline investigation (sessions 2026-09-25 → 26). Records F1 (shipped no-op at default), F2 (hue-preserving multiplicative mask, implemented `4ccdb36` / RC `v2026.09.25.09.25-rc`), F3 (`h_p` bounded by `A < 1`), F4 (blue-sky under-dehaze tradeoff), F5 (merge keeps 2D awareness — cross-plane, not cross-pixel, average), F6 (cap=1.0 verified strong dehaze, 45% `mean|Δ|`), F7 (dehaze-before-exposure underexposure failure, −4 EV → 6%), F8 (`A_c` strict-DCP proposal). Recommends decoupling ceiling from percentile and adding `A_c`.
