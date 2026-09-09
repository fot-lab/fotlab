# Commit Scope — Conversation-Local Commits

- ID: GITHUB-COMMIT-000001
- Status: Approved
- Priority: P3
- Created: 2026-09-09
- Owner: —
- Related: `rules/ACTION.md` (Agent Behaviour Rules → Verification Loop), `.github/workflows/*.yaml`

## Background & Goal

When the user asks to commit, the agent must not blindly stage everything `git status`
shows. The working tree can contain a mix of:

- changes the agent made **during the current conversation** (the fix / task at hand), and
- changes that existed **before** this conversation — pre-existing local edits, pulls
  from upstream, or edits made in other sessions.

Sweeping unrelated changes into a task commit pollutes history and can bundle
unreviewed work. This rule pins the default behaviour to *conversation-local*
commits, and defines the explicit opt-in for taking over out-of-conversation changes.

## Specification

### Default — commit only this conversation's changes

When the user requests a commit (e.g. "commit", "提交", "继续 commit and push"), the agent:

1. Identifies the set of files it **modified or created during the current conversation**.
   The agent tracks these as it works; this set — not `git status` — is the
   authoritative scope.
2. Stages **only** those files: prefer explicit `git add <path> …`. Never a blanket
   `git add -A` / `git add .` unless the entire working tree is exactly the
   conversation scope.
3. Writes a focused commit message describing the conversation's change.
4. Pushes (`git push`) when the user also asked to push, or per the normal
   verification loop in `rules/ACTION.md`.

Out-of-conversation modifications present in the working tree are **left untouched and
unstaged** by default.

### Opt-in — takeover of non-conversation changes

Only when the user **explicitly** asks to take over the out-of-conversation changes
(e.g. "把对话外的内容也接管 / 审查 / 提交、push", "接管其他未提交的改动") does the agent:

1. Review those non-conversation changes — diff them, understand intent, check for
   secrets or breakage.
2. Decide whether to fold them into the same commit or a separate, clearly-scoped
   commit.
3. Stage, commit and push them as requested.

Without that explicit instruction, the agent must **not** assume ownership of, nor
commit, anything outside the current conversation.

### Push & CI

- Push follows the commit per normal flow.
- The user may say "不用等 CI" / "don't wait for CI" to skip the push-and-wait loop —
  in that case push and stop; do **not** run `gh run watch` or otherwise block on CI.

## Change History

- 2026-09-09 — Initial encoded rule: the default commit scope is the current
  conversation only; non-conversation changes are reviewed/committed/pushed only on an
  explicit takeover request.
