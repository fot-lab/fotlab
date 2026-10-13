# Showcase screenshots from CI smoke tests → GitHub Wiki

- ID: MANUAL-GHWIKI-000001
- Status: Draft
- Priority: P2
- Created: 2026-10-13
- Owner: —
- Related: DOCKER-GHCRIO-000001 (GHCR / docker-android emulator base the smoke tests boot), FOTLAB-UIXDES-000001..000006 (the UI surfaces we want to show)

> **⚠️ Pending human approval.** This document is a *proposal only*. It MUST NOT be implemented until explicitly approved. All code/command snippets below are design-level illustrations, not committed code.

## Background & Goal

We already run instrumented smoke tests on a prebuilt Android emulator inside GitHub Actions (see `DOCKER-GHCRIO-000001` and `emulator_smoke_shard/action.yml`). Those tests exercise the real app — library grid, RAW import, viewer, develop, grade — on a booted device. The rendered screens are exactly the kind of material we want on the project's public **GitHub Wiki** as a live showcase.

Goal: capture a small, curated set of screenshots during the existing CI smoke run and publish them — together with a short markdown page — into this repository's GitHub Wiki, using the workflow's own `GITHUB_TOKEN`. No manual screenshotting, no Docker Hub, no third-party image host.

## Requirement

1. During a successful (or partially successful) smoke run on `main`, capture N representative PNG screens (home/library grid, an opened RAW in the viewer, a developed frame, a graded frame) from the running emulator.
2. Collect those PNGs out of the emulator and into the CI runner as artifacts.
3. From CI, push the PNGs plus a generated showcase `.md` page into this repo's wiki git repo, authenticated with `GITHUB_TOKEN`, **only when something actually changed**.
4. **Separation of concerns (hard rule).** The Kotlin / Android-emulator code is responsible *only* for capturing screenshots and handing them to the runner. It must contain **no** wiki-repo logic — no git operations, no image comparison, no "should I commit" decision. All such logic lives in CI scripts/tools.
5. **No duplicate commits.** For screenshots that are identical (or near-identical within a tolerance) to what is already in the wiki, do **not** re-commit them. A run that produces no visual change must produce **zero** wiki commits.
6. The Wiki updates automatically on each relevant `main` push; it must not require any human step and must not create a CI loop.

## Constraints

- **Same-repo wiki only.** Target is `https://github.com/${{ github.repository }}.wiki.git` (i.e. `ghcr`-style first-party, but for wiki). No cross-repo, no external host.
- **Token scope.** The push must use the auto-generated `GITHUB_TOKEN`, not a stored PAT. This requires `permissions: contents: write` on the publishing job. Verified against `github-wiki-action`'s documented requirement: *"in order to successfully push to the .wiki.git … we need the contents: write permission."*
- **Wiki feature enabled.** `gh api repos/fot-lab/fotlab` returns `has_wiki: true`, so the feature is on. (Caveat below — the *git backend* may still be unprovisioned until the first page exists.)
- **Reuse the existing smoke flow.** Screenshots are a by-product of the existing `connectedDebugAndroidTest` shards; we must not fork a second emulator boot or add measurable runtime to the gating smoke.
- **No CI loop.** Pushing to `*.wiki.git` fires events on the *wiki* repo, not on `fotlab`, so the main repo's `on: push` workflows are NOT retriggered. This is a desired safety property and must be preserved (don't push wiki content into `fotlab` itself).
- **Emulator is API 33** (`docker-android:api-33`); the app compiles against 36 but renders on 33. Screenshots reflect API-33 rendering — acceptable for a showcase, but note it.
- **App code is capture-only.** Everything about the wiki — clone, image comparison, dedup, and the commit/push decision — is owned by CI scripts/tools. The `androidTest` capture code must stay free of any git/registry/wiki awareness (see Requirement 4).

## Approach (proposed)

### 1. Capture inside the smoke tests (app side)
Add a debug/instrumented-only screenshot helper. Recommended: AndroidX UI Automator `UiDevice.getInstance(instrumentation).takeScreenshot(File)` invoked at curated moments inside the existing `androidTest` smoke classes (e.g. after the library grid renders, after a RAW opens in the viewer, after develop/grade is applied). `RawRoutingTest.kt` already imports `Bitmap`/`MediaStore` and navigates these exact screens, so it is the natural home for the capture calls.

Write each PNG to a world-readable location so `adb pull` works without `run-as`, e.g. `/sdcard/Pictures/fotlab-shots/<shard>-<screen>.png`. (Path/permission choice is an open question — see below; a `run-as` pull is the fallback if scoped storage blocks the public path.)

Illustrative (design only):
```kotlin
val dir = File("/sdcard/Pictures/fotlab-shots"); dir.mkdirs()
UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    .takeScreenshot(File(dir, "loader-viewer.png"))
```

### 2. Pull out of the emulator (CI side)
In `emulator_smoke_shard/action.yml`, after `run_smoke_shard.sh` finishes, pull the directory unconditionally (`if: always()`) so we keep partial shots even on a failed shard:
```bash
adb pull /sdcard/Pictures/fotlab-shots ./screenshots-"$SHARD" || true
```
Then upload as a workflow artifact so the publish step can collect across shards:
```yaml
- uses: actions/upload-artifact@v7
  if: always()
  with:
    name: smoke-screenshots-${{ inputs.shard }}
    path: screenshots-*/
    if-no-files-found: ignore
```

### 3. Compare & decide (CI side — the dedup logic)
A standalone CI script/tool (e.g. `.github/scripts/wiki_shots_diff.py`, or a thin shell wrapper around it) owns **all** wiki-repo logic. It is invoked from the publish job and, for the de-duplication step, **clones the existing wiki and compares each freshly captured screenshot against the screenshot already committed in that wiki** — the fixed filenames from Section 5 give a 1:1 mapping, so "dedup" is precisely "new screenshot vs. wiki's existing screenshot, file by file":
1. Clones the wiki repo (`<repo>.wiki.git`) — read-only is enough for the comparison.
1. Clones the wiki repo (`<repo>.wiki.git`) — read-only is enough for the comparison.
2. For each captured PNG, compares it against the file currently in the wiki working tree:
   - **byte-identical** → unchanged;
   - otherwise compute a **perceptual hash / image diff** (e.g. aHash, or Pillow `ImageChops.difference` with a mean-absolute-difference threshold) so tiny non-determinism (status-bar clock, battery %, font hinting) does not count as a change;
   - below the threshold → treat as **same**, skip;
   - above the threshold OR file absent → **changed**.
3. Reconstructs the intended `Showcase.md` and compares its text to the existing one.
4. Decides: if **no** PNG changed AND the page text is unchanged → **exit without committing** (no-op; the wiki stays as-is). Only if something actually changed does the publish step proceed.

Illustrative (design only — tool choice is an open question):
```bash
# compare captured shots against wiki HEAD; exit code / stdout says "changed" or "unchanged"
python3 .github/scripts/wiki_shots_diff.py \
  --wiki wiki/ --incoming screenshots-*/ --threshold 0.02
```

Why a tool and not `git`/`cmp` alone: emulator PNGs are never byte-stable (PNG metadata, compression, clock), so a pure byte compare would re-commit every run. A perceptual hash collapses those trivial differences. The exact threshold and hash algorithm are open questions (see Open Questions).

### 4. Publish to the wiki (CI side — only when changed)
Add a publish job (in `build.yaml`, `needs: [emulator-smoke]`, or a dedicated `wiki-shots.yaml`) that runs **only on `main` push** and only after smoke passes. It:
1. Downloads all `smoke-screenshots-*` artifacts.
2. Runs the Section-3 comparison; **if nothing changed, stop here — no commit, no push** (the wiki stays as-is).
3. Otherwise assembles/updates `Showcase.md` and copies only the changed PNGs into the wiki clone, then commits & pushes:
```bash
git clone "https://x-access-token:${{ secrets.GITHUB_TOKEN }}@github.com/${{ github.repository }}.wiki.git" wiki
python3 .github/scripts/wiki_shots_diff.py --wiki wiki/ --incoming screenshots-*/ --threshold 0.02 --apply
cat > wiki/Showcase.md <<'EOF'
# fotlab on-device showcase
_Generated from CI smoke tests on \`main\`._

![Library](loader-library.png)
![RAW in viewer](loader-viewer.png)
![Developed](develop-frame.png)
EOF
cd wiki && git add -A && git commit -m "showcase: $(date -u +%Y-%m-%dT%H:%M:%SZ)" && git push
```
The job needs:
```yaml
permissions:
  contents: write   # required to push to .wiki.git of the same repo
```

### 5. Gating & idempotency
- Publish only on `push: main` (not PRs) to avoid wiki churn from every PR.
- Overwrite a **fixed** set of image filenames each run so the wiki delta stays small and the page references stay stable (don't append run IDs to filenames).
- Pushing to `.wiki.git` does not trigger `fotlab`'s workflows → no loop (see Constraints).

## Acceptance Criteria

- A `main` push whose smoke run succeeds AND whose screens changed results in updated `Showcase.md` + PNGs in the repository Wiki, visible at `https://github.com/fot-lab/fotlab/wiki/Showcase`.
- **No-op guarantee:** a `main` push whose captured screens are identical (within tolerance) to the wiki's current ones produces **zero** wiki commits — the publish step detects "unchanged" and exits before cloning/pushing.
- **Separation guarantee:** the Kotlin / emulator code contains no wiki-repo logic (no git, no comparison, no commit decision); all of that lives in the CI comparison script.
- The publish step uses only `GITHUB_TOKEN` (no PAT secret referenced).
- The wiki push does not cause a new `fotlab` CI run.
- A failed smoke shard still yields any screenshots it managed to capture (best-effort, non-fatal).

## Impacted Modules

- `app/src/androidTest/.../smoke/*` — new screenshot capture calls (e.g. `RawRoutingTest.kt`).
- `.github/actions/emulator_smoke_shard/action.yml` — `adb pull` + artifact upload.
- `.github/workflows/build.yaml` (or new `.github/workflows/wiki-shots.yaml`) — publish job with `contents: write`.
- `.github/scripts/wiki_shots_diff.py` (new) — CI-side comparison/dedup tool: perceptually diffs captured PNGs against the wiki HEAD and decides whether to commit. This is where **all** wiki-repo logic lives.
- This repository's GitHub Wiki (`fot-lab/fotlab.wiki.git`) — new `Showcase.md` + image assets.

## Open Questions (require approver input)

1. **Wiki backend bootstrap.** A GitHub wiki's `.wiki.git` may return *404 "repository not found"* on clone until the **first page exists**. Need a one-time seed: create `Home.md` via the web UI, or via the REST API (`POST /repos/{owner}/{repo}/wiki/pages` with `GITHUB_TOKEN`), before the CI push will succeed. Decide who seeds it and when.
2. **Which screens / how many.** Confirm the exact curated list (proposed: library grid, RAW opened in viewer, developed frame, graded frame). Keep it small (≤6) for a clean showcase.
3. **Capture mechanism.** `UiDevice.takeScreenshot` (inside the test, reliable timing) vs `adb exec-out screencap -p` from the runner (no app code, but timing is hard). Recommend in-test capture.
4. **Write path / `adb pull` permission.** Public `/sdcard/Pictures/fotlab-shots` vs app-private external dir (needs `run-as` to pull on API 33 scoped storage). Pick the path that `adb pull` can read without root.
5. **Determinism.** Emulator screenshots may vary (animations, fonts, timing). Decide whether to disable animations / wait-for-idle before capture, and whether exact-pixel stability matters (it does not for a showcase, but should be noted).
6. **Reuse vs custom.** Use `Andrew-Chen-Wang/github-wiki-action` (folder-sync model) vs a small custom clone-copy-commit-push step. Recommend custom for control over image naming and the generated page, but the action is a viable shortcut.
7. **Retention / wiki growth.** Overwriting fixed filenames bounds growth; confirm that is acceptable vs pruning old assets.
8. **Comparison tool & threshold.** Which engine does the dedup use — Python Pillow (`ImageChops.difference` / perceptual aHash), ImageMagick `compare` with `-fuzz`, or pure-bash `cmp`? What hash/metric and what threshold counts as "same" vs "changed"? This is the core of the no-duplicate-commit guarantee.
9. **Normalising non-determinism before compare.** Emulator shots drift (status-bar clock, battery %, notification icons, font hinting). Should the comparison crop the status bar / flatten minor regions, or just rely on a tolerant threshold? Affects how often real changes get falsely suppressed.

## Change History

- 2026-10-13 — Created as Draft (pending human approval). Captured investigation facts: `has_wiki: true`; same-repo wiki push requires `permissions: contents: write` on the publishing job; wiki is a separate git repo at `<repo>.wiki.git`; no CI loop because wiki pushes do not retrigger `fotlab` workflows. Identified the missing pieces (in-test capture, `adb pull` + artifact, publish job, wiki bootstrap caveat).
- 2026-10-13 — Revised (still Draft, pending approval). Added PNG **comparison / de-duplication**: identical (within tolerance) screenshots must not be re-committed, and a no-op run must produce zero wiki commits. Hardened the separation-of-concerns rule: Kotlin / the Android emulator only capture screenshots; all wiki-repo logic (clone, perceptual diff, dedup decision, commit/push) moves into a CI-side script/tool (`.github/scripts/wiki_shots_diff.py`). Capture side stays free of any git/registry/wiki awareness.
