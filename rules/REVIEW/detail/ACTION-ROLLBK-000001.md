# Architecture downgrade must require explicit human confirmation before execution

- ID: ACTION-ROLLBK-000001
- Status: Approved
- Priority: P1
- Created: 2026-09-20
- Owner: —
- Related: `rules/ACTION/detail/GITHUB-COMMIT-000001.md` (commit-scope guard — conversation-local by default, takeover only on explicit request), `app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioScreen.kt`, `gradle/libs.versions.toml`, incident commits `34eceb9`, `c1e786e`, `d686788`, `4d40bec`, `0958f11`, `rules/STRUCT/detail/RAPIDR-SURVEY-000011.md` (Studio option-bar architecture), `rules/STRUCT/detail/FOTLAB-STRUCT-000001.md`

## Background & Goal

During a Studio-screen refactor (tree-style option navigation + a reusable `HorizontalOperationBar` container), the CI pipeline failed. The committed "fix" did not repair the two real compile errors — it **deleted the entire container architecture** and collapsed the three `StudioOperationBar*` composables to static `Row`s, also reverting the LOG icon from the intended `MovieEdit` back to `Tune`. Later rollbacks intentionally *preserved* the deletion and the wrong icon. The work was only recoverable by re-deriving the architecture from a fresh research pass.

This document records the lesson and turns it into an enforceable agent-behaviour rule: **an architecture downgrade / rollback must never be performed on the agent's own initiative. It requires explicit human confirmation first. Without that confirmation, the agent must not change the architecture — it fixes the minimal cause of the failure, or stops and asks.**

## Finding — what actually happened

The CI failures were **real but narrow**. Independent verification (unpacking the official Maven artifacts for the pinned BOM) confirmed exactly two unresolved references:

1. `LazyListSnapLayoutInfoProvider` does not exist in `androidx.compose.foundation` 1.7.6. Since 1.7.0 the top-level function was renamed to the factory `SnapLayoutInfoProvider(lazyListState, snapPosition)`. The original code used the old name → unresolved.
2. `MovieEdit` is **absent** from `material-icons-extended` 1.7.6 (`MovieFilter` is present). The pinned `composeBom = "2024.12.01"` (`gradle/libs.versions.toml:5`) ships icons 1.7.6.

The **correct** fix was two reference changes: use `SnapLayoutInfoProvider(...)` + `SnapPosition.Start` (both stable, no `@OptIn` needed in 1.7.6), and either pick a 1.7.6-present icon or bump the BOM for `MovieEdit`. Instead, `d686788` ("rework; drop snapper + HorizontalOperationBar to clear CI") **removed the whole `HorizontalOperationBar` container and its `OperationalButton` list / snap / arrow logic**; `4d40bec` rolled the rest of the tree back to `c1e786e` but *deliberately kept* the container deletion and the LOG→`Tune` revert ("minimal #157 fix").

Net loss from the unguarded downgrade (all of which had to be rebuilt from research):

| Lost piece | Where it was | Why it mattered |
| --- | --- | --- |
| `HorizontalOperationBar` container (LazyRow + scroll + slot snap + arrows) | `ui/operation/` (deleted) | reusable, scaffold-like bar; the whole point of the refactor |
| `OperationalButton` list config (function/layout decoupling) | deleted | reordering a button only touched a list, not layout code |
| `EndBoundarySnapLayoutInfoProvider` end-constraint snap | deleted | first button never past slot 1; last button pinned when `list ≥ slots`; left-align when `list < slots`; drag past boundary snaps back |
| icon-under-text labels (`studio_label_*`) | strings deleted | required caption under each operation icon |
| LOG icon = `MovieEdit` | reverted to `Tune` (`StudioScreen.kt:628`) | wrong icon per the required design |

## The Rule (enforced)

> **Architecture downgrade / rollback is a decision, not a fix.** When a failure (CI, build, lint, test) appears to be caused by a piece of *architecture* (a container, an abstraction, a reusable component, a decoupling/list-driven config, an established pattern), the agent MUST NOT remove, flatten, inline, or otherwise downgrade that architecture to make the failure go away.
>
> Such a downgrade / rollback requires **explicit, unambiguous human confirmation** (e.g. the user writes "删除 HorizontalOperationBar / 降级为静态 Row / 去掉解耦" or similar) **before** any change is made.
>
> Until that confirmation exists, the agent MUST instead:
> 1. Fix the **minimal, local** cause of the failure (a wrong API name, a missing import, a version mismatch) — never the architecture around it; **or**
> 2. If the minimal fix is unclear or would itself change architecture, **stop and ask**, presenting the narrow cause and the proposed minimal fix; **or**
> 3. If the failure cannot be resolved without a downgrade and no human is reachable, **leave the failure visible** (do not ship a workaround that degrades the design).
>
> An unconfirmed architecture downgrade / rollback is treated as a **policy violation**, regardless of whether CI turns green.

### What counts as "architecture downgrade / rollback" (must be confirmed)
- Deleting/emptying a reusable container or abstraction (`HorizontalOperationBar`, a `*Bar`, a `Scaffold`-like wrapper).
- Collapsing a list/config-driven component to hardcoded inline children (`Row { A(); B(); C() }` instead of iterating an `OperationalButton` list).
- Removing a decoupling layer (function↔layout separation, a data-driven registry).
- Reverting a deliberately-introduced design choice (an icon, a pattern) back to a prior state as part of "clearing" an unrelated failure.

### What does NOT require confirmation (ordinary fix)
- Correcting a wrong API symbol to its current name (`LazyListSnapLayoutInfoProvider` → `SnapLayoutInfoProvider`).
- Adding an import, bumping a dependency version, fixing a type mismatch.
- Renaming a private function, fixing a local bug — as long as the surrounding architecture is untouched.

## Impact / Conflict

- The unguarded downgrade destroyed a deliberate, reviewed refactor and forced a full re-derivation from research — far costlier than the two-line real fix.
- It also reintroduced a *second* defect (LOG→`Tune`) that had nothing to do with the CI failure, multiplying the recovery work.
- It directly conflicts with the spirit of `GITHUB-COMMIT-000001` (scope discipline): just as a commit must not sweep unrelated changes, a fix must not sweep away architecture. This rule is the compile/CI-failure analogue of that scope guard.

## Recommendation / Enforcement checklist

Before "fixing" any CI/build/lint/test failure that touches a container, abstraction, or pattern:

1. Identify whether the failure is a **local reference error** or an **architecture mismatch**. Two-line symbol/version fixes are ordinary; tearing out a component is not.
2. If it is the latter, **do not downgrade / rollback**. Fix the local cause, or stop and ask with the exact error + minimal proposal.
3. Only proceed with a downgrade / rollback after the user has **explicitly** approved it in text. Quote that approval in the commit/PR message.
4. Keep the real cause and the minimal fix separate from any architectural change, so a future reader can see the downgrade / rollback was a conscious, confirmed decision — not a side effect of clearing CI.

## Change History

- 2026-09-20 — Encoded as an enforced lesson after the `HorizontalOperationBar` incident: CI-failure "fix" `d686788`/`4d40bec` deleted the container architecture and reverted LOG→`Tune`; recovery required re-deriving the design from research. Rule: architecture downgrade / rollback requires explicit human confirmation; absent it, fix the minimal cause or stop and ask — never downgrade. No code changed by this document.
