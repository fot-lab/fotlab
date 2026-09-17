# UniFFI 0.28 Kotlin Naming Conventions — Enum Variants and Record Fields

- ID: FOTLAB-UNIFFI-000001
- Status: Approved
- Priority: P1
- Created: 2026-09-17
- Owner: —
- Related: [FOTLAB-STRUCT-000001](FOTLAB-STRUCT-000001.md), [FOTLAB-STUDIO-000001](FOTLAB-STUDIO-000001.md)

## Background & Goal

The project uses UniFFI 0.28 to generate Kotlin bindings from Rust (`app/src/binding/rust/rawler_fotlab`). A new `DemosaicAlgorithm` enum and `DevelopParams` record were added to the develop pipeline, but the Kotlin code referencing them failed to compile because the generated names did not match the Rust source names. This document records the naming conventions so future binding additions compile on the first try.

## Requirement

### 1. Enum Variants — SCREAMING_SNAKE_CASE

UniFFI 0.28's Kotlin codegen applies `heck::to_shouty_snake_case()` to every Rust enum variant name. The conversion uses heck's word-boundary rules:

| Rust variant | Kotlin generated | Rule |
| --- | --- | --- |
| `Default` | `DEFAULT` | single word → all-caps |
| `Ppg` | `PPG` | single word → all-caps |
| `Bilinear4Channel` | `BILINEAR4_CHANNEL` | word boundary before uppercase `C`; digit `4` stays with preceding word |
| `XTransBilinear` | `X_TRANS_BILINEAR` | consecutive uppercase `XT` split: `T` followed by lowercase starts a new word |

**Rule**: when writing Kotlin that references a UniFFI-generated enum, always use the SCREAMING_SNAKE_CASE form. If unsure of the exact boundary split, trigger a CI build and read the `e: ` error — it will name the actual generated variant.

### 2. Record Fields — camelCase

UniFFI 0.28 converts Rust struct field names from `snake_case` to `camelCase` for Kotlin data classes:

| Rust field | Kotlin parameter |
| --- | --- |
| `demosaic_algorithm` | `demosaicAlgorithm` |
| `exposure_ev` | `exposureEv` |
| `wb` | `wb` (already camelCase) |

**Rule**: when constructing a `uniffi::Record` data class in Kotlin, use camelCase for all multi-word fields.

### 3. Kotlin Compiler Error Prefix

Kotlin compiler errors in the Gradle build log are prefixed `e: ` (e.g. `e: file:///...path.kt:42:7 ...`). Grep `^e: ` on the `build-gradle.log` artifact to extract all compilation errors quickly. This is documented in [`rules/ACTION.md`](../../ACTION.md) Verification Loop step 3.

## Constraints

- These conventions are specific to **UniFFI 0.28**. A future UniFFI upgrade may change the codegen behavior (e.g. preserving original case). Re-verify after any `uniffi` version bump in `Cargo.toml`.
- The generated Kotlin source lives in `app/build/generated/uniffi/main/kotlin/` (not committed). To inspect the actual generated names locally, run `./gradlew generateUniffiBindings` (or equivalent task) and read the output.
- `heck`'s digit handling: digits are treated as lowercase characters and stay with the preceding word. `Bilinear4Channel` → `BILINEAR4_CHANNEL` (not `BILINEAR_4_CHANNEL`).

## Acceptance Criteria

1. All Kotlin code referencing UniFFI-generated enums uses SCREAMING_SNAKE_CASE variant names.
2. All Kotlin code constructing UniFFI-generated records uses camelCase field names.
3. `:app:compileDebugKotlin` passes without `e: ` errors related to naming mismatches.

## Impacted Modules

- `app/src/main/kotlin/io/github/fotlab/fotlab/` — all Kotlin calling UniFFI bindings
- `app/src/androidTest/kotlin/io/github/fotlab/fotlab/smoke/` — instrumented tests referencing bindings
- `app/src/binding/rust/rawler_fotlab/` — Rust source defining the enums and records

## Open Questions

- Should we pin UniFFI to a specific patch version to prevent codegen drift?
- Should we add a CI step that diffs generated bindings against a committed snapshot to catch naming changes early?

## Change History

- 2026-09-17 — Created after CI failures traced to UniFFI 0.28 naming conventions. Root cause: `DemosaicAlgorithm` enum variants and `DevelopParams` record fields were referenced in Kotlin using their Rust names, but UniFFI 0.28 applies `to_shouty_snake_case()` to enum variants and snake-to-camel to record fields. Fixed in commit `237988a`. Also updated `rules/ACTION.md` with the `e: ` prefix convention for Kotlin compiler errors.
