# FotLab processing pipeline — loader / develop / process

- ID: FOTLAB-PIPELN-000001
- Status: Draft
- Priority: P1
- Created: 2026-09-18
- Owner: —
- Related: `FOTLAB-FOTRAW-000001` (canonical decoded-but-undeveloped RAW IR — `FotRaw`), `DNGLAB-RAWDEV-000001` (dnglab `rawler::imgop::develop` — the raw develop path), `RAWTRP-PIPELN-000001` (develop stage taxonomy — completeness checklist), `DNGLAB-PIPELN-000001` / `DNGLAB-PIPELN-000002` (dnglab-side pipeline), `FOTLAB-STUDIO-000001` (Studio render contract — develop must show the true developed image), `FOTLAB-NATIVE-000001` (dnglab is a fixed, read-only constraint)

## Background & Goal

FotLab accepts two very different inputs: true RAW files (CR2/CR3/ARW/NEF/DNG/…) and "finished" raster images that already left a camera pipeline (JPG/PNG/HEIF). Today the two are handled by separate, ad-hoc code paths, which means every creative tool has to special-case its input source.

We want one uniform, source-agnostic pipeline. The split is three stages — **loader → develop → process** — connected by exactly two in-memory objects:

- `FotRaw` — the decoded-but-undeveloped RAW intermediate (defined in `FOTLAB-FOTRAW-000001`). It exists **only for true RAW**.
- `FotDev` — the developed, linear-space image that every post-processing operation starts from. It is the single entry point into `process`, regardless of whether the source was RAW or a finished file.

Goal:

- G1 — Define the three-stage pipeline and the two-object contract so that `process` (all creative editing) never branches on input source.
- G2 — Let finished images skip `FotRaw` entirely (they have no undeveloped RAW to represent) and still arrive at the same `FotDev` shape as a RAW would.
- G3 — Keep `loader` and `develop` free of creative edits; tone/colour/sharpen/denoise belong only to `process`.

## Requirement

### R1 — The pipeline is exactly three stages

```
                 ┌─ raw (CR2/CR3/ARW/NEF/DNG/…) ── sniff ──► FotRaw ──┐
input ───────────┤                                                          ├──► develop ──► FotDev ──► process ──► output
                 └─ finished (JPG/PNG/HEIF/…) ─── sniff ──(skip FotRaw)──┘
```

- `loader` produces `FotRaw` (RAW) or, for finished images, produces nothing and hands the decoded source straight to `develop`.
- `develop` always produces `FotDev`.
- `process` consumes `FotDev` only.

### R2 — `loader`: sniff + decode → `FotRaw`

- `loader` first **sniffs** the input to decide the decoder: magic-byte / container / TIFF-`Make` probing, mirroring rawler's `get_decoder` dispatch (`DNGLAB-SURVEY-000002`). The sniff result is the only thing `loader` decides; it performs no pixel interpretation.
- For a **true RAW**, `loader` decodes the file into the canonical `FotRaw` intermediate (`FOTLAB-FOTRAW-000001`): an uncompressed sample buffer (`FotRawData`) plus the three tag namespaces (`TagsIsoDng` / `TagsDngLab` / `TagsFotLab`) carrying all geometry, CFA, black/white level, colour matrices and camera identity.
- For a **finished image** (JPG/PNG/HEIF), `loader` decodes it far enough to identify it and obtain its pixels, then **does not build a `FotRaw`** — it passes the decoded pixels to `develop` directly (see R3a). The FotRaw IR (a RAW-centric, DNG-tagged structure) is meaningless for an already-developed raster, and re-encoding one into it would be wasted work and lost fidelity.
- `loader` applies no black/white normalisation, no white balance, no demosaic, no colour transform, no gamma/tone. Those are `develop`/`process` concerns.

### R3 — `develop`: → `FotDev` (linear space)

`develop` has two entry arms that converge on the same `FotDev` object.

#### R3a — Finished-image fast path (no `FotRaw`)

- JPG/PNG/HEIF are decoded by `loader` and handed to `develop` as decoded pixels.
- `develop` **restores them to linear light**: it undoes the file's tone-response curve / gamma (sRGB EOTF for JPG/PNG, the HEIF/AVIF transfer function for HEIF), expands to a linear floating-point buffer, and places the result in `FotDev`.
- No `FotRaw` is involved. The output of this arm is structurally identical to R3b's output (see R4/AC3).

#### R3b — True-RAW path

- `develop` takes `FotRaw` and runs the develop chain: black/white-level normalisation → white balance → demosaic → camera→working colour conversion, landing the pixels in a **linear** working buffer as `FotDev`.
- The stage ordering follows the develop taxonomy in `RAWTRP-PIPELN-000001` (the completeness checklist) and is realised through dnglab's `rawler::imgop::develop` (`DNGLAB-RAWDEV-000001`), per `FOTLAB-NATIVE-000001` R4 (upstream is a fixed constraint).
- `develop` stops at the linear developed image. It does **not** apply output tone curve / gamma / creative edits — those belong to `process`.

### R4 — `FotDev` is the single, source-agnostic `process` entry

- `FotDev` is the **developed, linear-space** in-memory object. Both R3a and R3b produce the same shape, so `process` cannot tell (and must not care) whether the source was RAW or finished.
- `FotDev` carries at minimum: a linear RGB float buffer, the working colour space identifier, geometry (width/height/orientation), and develop metadata (illuminant / WB that was applied, the working profile used). Its full field contract is the subject of a separate `FOTLAB-FOTDEV` item — this document freezes only the role and the "linear, source-agnostic" invariant.
- `process` begins only after `FotDev` exists. `loader` and `develop` never apply creative edits.

### R5 — `process`: post-processing from `FotDev`

- `process` operates on `FotDev` alone: exposure / tone curve, colour / saturation, local contrast, sharpening, denoise, geometry, and finally the **output** tone-response curve / gamma that produces the display or export raster.
- Because `FotDev` is already linear, all `process` math is in linear light; the output TRC is the last step (matching the RawTherapee output-stage pattern in `RAWTRP-PIPELN-000001` §3 Stage L, but kept inside `process`).

## Object contracts

| Object | State | Produced by | Consumed by | Contract |
| --- | --- | --- | --- | --- |
| `FotRaw` | decoded, **undeveloped** RAW | `loader` (RAW only) | `develop` (R3b) | `FOTLAB-FOTRAW-000001` — `FotRawData` (uncompressed buffer, no shape) + `FotRawMeta` (isodng / dnglab / fotlab namespaces) |
| `FotDev` | developed, **linear** | `develop` (both arms) | `process` | `FOTLAB-FOTDEV` (follow-up) — linear RGB float buffer + working space + geometry + develop metadata |

Finished images never materialise a `FotRaw`; they enter `develop` directly (R3a).

## Constraints

- C1 — Finished images (JPG/PNG/HEIF) **never** become `FotRaw`; they take the R3a fast path. `FotRaw` is reserved for true RAW.
- C2 — `develop` output is always **linear** (no gamma/TRC baked in), so `process` is consistent across sources.
- C3 — `process` consumes `FotDev` only; it contains no branch on whether the input was RAW or finished.
- C4 — `loader` and `develop` apply no creative edits (no tone/colour/saturation/sharpen/denoise); those live only in `process`.
- C5 — The RAW develop path reuses dnglab/rawler upstream as a fixed constraint (`FOTLAB-NATIVE-000001` R4); FotLab does not re-implement RAW develop in Kotlin/C++.
- C6 — Working colour space for `FotDev` is fixed once, project-wide (see Q2); RAW and finished arms must agree so AC3 holds.

## Acceptance Criteria

- AC1 — A true RAW file flows `sniff → FotRaw → develop → FotDev → process → output` end to end with no creative step before `develop`.
- AC2 — A finished image (JPG/PNG/HEIF) flows `sniff → (no FotRaw) → develop → FotDev → process → output`; the `FotRaw` object is never constructed.
- AC3 — `FotDev` produced by R3a and by R3b are structurally identical (same buffer type, working space, geometry semantics) so `process` needs no source branch.
- AC4 — `develop` output is verifiable as linear: applying the output TRC to a mid-grey `FotDev` pixel reproduces the expected displayed value, and no gamma is observable inside `process` math.
- AC5 — Grepping `loader`/`develop` shows no tone-curve / saturation / sharpen / denoise call; those appear only in `process`.

## Impacted Modules

- `loader` — format sniffing + RAW decode to `FotRaw`; finished-image decode handed to `develop`.
- `develop` — dnglab `rawler::imgop::develop` for RAW (`DNGLAB-RAWDEV-000001`); a separate linearization path for finished images (R3a).
- `process` — all creative post-processing, `FotDev`-only.
- `FOTLAB-FOTRAW-000001` — the `FotRaw` IR consumed by R3b.
- `FOTLAB-FOTDEV` (follow-up) — the `FotDev` contract produced by R3 and consumed by R5.
- `FOTLAB-NATIVE-000001` — dnglab integration boundary used by `develop`.
- `FOTLAB-STUDIO-000001` — Studio must display the true developed image; this pipeline is what feeds it.

## Open Questions

- Q1 — `FotDev` detailed field contract (buffer layout, working-space identifier type, develop-metadata schema). **TBD** → `FOTLAB-FOTDEV` item.
- Q2 — Which **working colour space** `FotDev` uses (linear ProPhoto D50 vs linear sRGB D65 vs another). Note the upstream mismatch recorded in project memory: rawalchemy `decodeRaw` lands in **linear ProPhoto (D50)**, whereas dnglab/rawler `develop` lands in **sRGB (D65)**; if `FotDev` is built from dnglab output but the project hub is ProPhoto D50, a D65→D50 chromatic-adaptation bridge is required (sRGB(D65)→XYZ→CAT02→ProPhoto(D50)). Decision needed before C6 can be enforced.
- Q3 — For finished images, is the decode+linearize in R3a done by the same dnglab/native layer or by a first-party decoder? **TBD.**
- Q4 — Does `develop` for RAW also emit the DNG tags needed to *re-export* a developed DNG, or is `FotDev` purely raster? If re-export is required, `FotDev` may need to carry `TagsIsoDng` forward from `FotRaw` (R3b only).

## Change History

- 2026-09-18 — Initial draft. Defined the FotLab processing pipeline as three stages — `loader` (sniff + decode → `FotRaw`, RAW only), `develop` (finished-image fast path JPG/PNG/HEIF → linear `FotDev` skipping `FotRaw`; true-RAW path `FotRaw` → linear `FotDev`), and `process` (post-processing from `FotDev`, source-agnostic). Fixed `FotDev` as the developed, linear-space, single entry to `process`, and recorded the two-object contract (`FotRaw` per `FOTLAB-FOTRAW-000001`, `FotDev` per a follow-up `FOTLAB-FOTDEV`). Linked the develop stage taxonomy to `RAWTRP-PIPELN-000001` and the dnglab develop path to `DNGLAB-RAWDEV-000001`; flagged the D65-vs-D50 working-space bridge as Q2.
