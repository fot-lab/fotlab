# ca_correction_lca luminance-edge judgement — no YUV/LAB conversion; Bayer green channel is the luminance proxy

- ID: FOTLAB-RAWLER-000021
- Status: Observation
- Priority: P3
- Created: 2026-10-08
- Owner: —
- Related: [`FOTLAB-RAWLER-000011`](FOTLAB-RAWLER-000011.md) (rawtrp_correct — faithful port of RawTherapee `CA_correct_RT`, both passes), [`FOTLAB-RAWLER-000020`](FOTLAB-RAWLER-000020.md) (LoCA purple/green fringe — peer CA stage sharing the same CFA-domain contract), [`FOTLAB-RAWLER-000009`](FOTLAB-RAWLER-000009.md) (pre-demosaic slot — the input domain this runs in)

## Background & Goal

User question, raised during a code audit: *how does `ca_correction_lca` judge luminance edges, and does it convert the image into a YUV or LAB colour space?* This is a research note recording the actual implementation in `app/src/binding/rust/rawtrp_correct/src/ca_correction_lca.rs`, specifically the pass-1 auto-fit measurement `detect_ca` (the only place a "luminance edge" is reasoned about; pass-2 `correct_ca_bayer` merely applies the measured shifts). The crate is a faithful Rust port of RawTherapee's `CA_correct_RT.cc` (Martinec / Weyrich radial CA model), see `FOTLAB-RAWLER-000011`.

## Finding

**1. No YUV / LAB conversion — and it could not have one.** The whole `rawtrp_correct` crate operates on the raw Bayer CFA mosaic: a single-channel `0..1` linear buffer plus a `CfaDesc` (R/G/B planes, no colour image yet). `lib.rs:35` states the contract explicitly — *"The port works in the `0..1` linear mosaic domain"*. A grep across the crate for `YUV` / `Lab` / `lab` / colour-space conversion returns nothing. At the pre-demosaic stage there is no full RGB image to convert, so a YUV/LAB luma is not only absent — it is meaningless by design (matching review principle #5's "upstream is read-only" and the CFA-domain rule of `000009` §Finding 3).

**2. "Luminance" is the Bayer green channel, and edges are judged from it.** In a Bayer grid the G plane is the densest and is used as the luminance proxy. Three mechanisms, all in `detect_ca`:

- **Directional weighted G interpolation (an edge-aware luminance estimate), `ca_correction_lca.rs:1070`–`1116`.** At each R/B grid point the four directional G neighbours are averaged with weights `wtu/wtd/wtl/wtr = 1 / (EPS + |ΔG| + |ΔRB|)²`. The weight is the *inverse square of the local G gradient magnitude*: a strong gradient (a luminance edge) drives the weight toward zero, so the estimator suppresses the edge direction. This is the "luminance" the stage reasons about.
- **G−(R/B) colour-difference high/low-pass filters, `ca_correction_lca.rs:1118`–`1183`.** `rbhpfv/rbhpfh` = high-pass of the R/B colour difference (the high-frequency content at luminance/colour edges); `rblpfv/rblpfh` = its low-pass; `grblpfv/grblpfh` = low-pass of the G-plus-R/B sum ≈ the low-frequency **luminance** estimate.
- **Edge-weighted CA fit, `ca_correction_lca.rs:1208`–`1230`.** Each sample's contribution to the quadratic colour-difference fit is weighted by
  `gradwt = rbhpf·(grblpf neighbours) / (EPS + 0.1·grblpf + rblpf)`.
  Bright, high-contrast (luminance-edge) regions get large weight and the CA measurement is trusted; flat / low-contrast regions are suppressed.

A separate, block-level consistency check (`ca_correction_lca.rs:1364`–`1368`) rejects tiles whose measured shift² exceeds `CA_AUTOSTRENGTH · blockvar` — a fit-consistency gate, not a per-pixel luminance-edge test.

## Impact / Conflict

No conflict and no action required. The behaviour is the expected, correct design for a pre-demosaic CFA stage: luma is the green channel, and there is deliberately no YUV/LAB conversion. The only risk is a *reader* misconception — anyone expecting a YUV/LAB luma would mis-model it; this note records the actual (green-channel) definition so downstream tuning of `caAutostrength` / the `gradwt` formula reasons about the right signal. It is consistent with `FOTLAB-RAWLER-000020` (LoCA), which shares the same CFA-domain, green-as-luminance approach.

## Recommendation

No code change. Keep as a reference finding. If future work proposes a different luma definition (e.g. a true YUV/LAB luma), it must first justify operating *after* demosaic — which would move it out of the pre-demosaic `R2` slot this crate owns per `000009` / `000011`. Otherwise the green-channel proxy stands.

## Change History

- 2026-10-08 — Created as `Observation` (P3). Research note answering how `ca_correction_lca::detect_ca` judges luminance edges: no YUV/LAB conversion (pre-demosaic single-channel mosaic, `lib.rs:35`); the Bayer **green channel is the luminance proxy**, and luminance edges are reasoned about via (a) directionally-weighted G interpolation whose weights are the inverse-square of the local G gradient (`ca_correction_lca.rs:1070`–`1116`), (b) G−R/B colour-difference high/low-pass filters (`1118`–`1183`), and (c) a `gradwt` term that weights the CA colour-difference fit by local luminance-edge strength (`1208`–`1230`), plus a block-level `CA_AUTOSTRENGTH` variance gate (`1364`–`1368`). No action required.
