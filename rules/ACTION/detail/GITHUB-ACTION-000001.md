# Viewing Remote CI Results via gh CLI

- ID: GITHUB-ACTION-000001
- Status: Approved
- Priority: P3
- Created: 2026-09-09
- Owner: —
- Related: `rules/ACTION.md` (Agent Behaviour Rules → Querying CI Status), `.github/workflows/build.yaml`, `.github/workflows/gradle.yaml`

## Background & Goal

When the user explicitly asks to view remote CI results, the agent should prefer
the `gh` CLI over the raw REST API, because it resolves the repository,
authentication and pagination automatically. This rule records *where* `gh` lives
in an agent environment and *which* commands to use.

## Specification

### When to use

Call the `gh` CLI only when the user explicitly asks to view remote CI results
(runs, logs, conclusions). Routine build/verify work still goes through the
push-and-wait loop in `rules/ACTION.md`.

### Locating `gh` — environment-dependent, never a single hard-coded path

- On Windows the CLI commonly installs to `C:\Program Files\GitHub CLI\gh.exe`.
  If it is missing from `PATH` in the agent's shell, invoke it by that full path.
- Otherwise let the agent search for it: `where gh` (cmd) or `Get-Command gh`
  (PowerShell), or probe common install dirs (`C:\Program Files\GitHub CLI`, the
  WinGet `Packages` tree, scoop shims, `${env:LOCALAPPDATA}`).
- In CI the runner image already ships `gh` on `PATH`, so no lookup is needed.

### Useful commands (run from the repo root so the repo resolves automatically)

- `gh run list --limit 5` — recent runs with status / conclusion.
- `gh run view <run-id> --log` — full log of a run.
- `gh run watch <run-id>` — follow a run until it finishes.
- `gh run list --branch main --status failure` — filter to failures.

### Downloading logs

CI uploads `build-gradle.log` and `build-native.log` as artifacts. Download them
into the gitignored `log/` directory (see `.gitignore`) so they are never committed:

- `gh run download <run-id> -D log` — download all artifacts of a run into `log/`.
- Then read `log/build-gradle.log` / `log/build-native.log` for the compile errors.

### Auth

`gh` uses the GitHub credential already available to the agent/user; never paste
a token into a command. If `gh auth status` reports unauthenticated, tell the user
to run `gh auth login` rather than authenticating on their behalf.

## Change History

- 2026-09-09 — Initial encoded rule. Extracted from `rules/ACTION.md`'s "Viewing Remote CI Results (gh CLI)" section into this detail file as the first ACTION-area item (`GITHUB-ACTION-000001`), per the AGENTS.md three-layer layout; `rules/ACTION/index.md` created as the Layer 2 master table and `rules/ACTION.md` now keeps only a brief reference.
- 2026-09-09 — Added the "Downloading logs" rule: CI logs are downloaded into the gitignored `log/` directory (see `.gitignore`) and never committed.
