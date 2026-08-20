# AI Agent Guidelines

This file serves as the system rules for AI Agents working on this project.

## Git Workflow Policy
1. **Never commit directly to `main`.** `main` is protected and represents the stable release.
2. **Never commit directly to `development`.** `development` is the integration branch.
3. **Always use feature branches.** When assigned a task, checkout a new branch from `development` (e.g., `feat/feature-name` or `fix/bug-name`).
4. **Update Changelog.** Every time you are about to merge a feature branch into `development`, you MUST update `changelog.md` with the changes and a version bump if necessary.
5. **No commits or pushes during work hours.** Do not run `git commit` or `git push` (or any equivalent that mutates git history/remote) Monday–Friday between 9:00 AM and 5:00 PM local time, unless the user explicitly asks for it in that moment. The user is often at their day job during these hours and cannot review/authorize commits on their own repo. Instead, make the code changes as normal (uncommitted working-tree changes are fine) and record a suggested commit message/summary in `git_commits.md` at the repo root for the user to review and commit later themselves, or ask Claude to commit once they're free. This restriction applies to Claude directly and to any work delegated to Antigravity/agy — instruct delegated agents not to commit or push either.

## Knowledge System (OKF)
- The `okf-docs/` folder contains the Open Knowledge Format (OKF) documentation.
- When you need context on architecture, schemas, or API boundaries, ALWAYS read the relevant `.md` files in `okf-docs/`.
- If you introduce a new feature or architectural change, create or update a file in `okf-docs/` following the OKF format (with YAML frontmatter: `title`, `type`, `author`, `tags`).

## Architecture & Code Quality
- Follow Clean Code principles.
- Strictly adhere to MVVM (Model-View-ViewModel) architecture.
- Always write Unit tests (JUnit, MockK) and Integration tests (Espresso) for new features.
- Keep components modular and single-responsibility.

## Delegating to Antigravity (agy-connector)

If you are Claude Code (or another MCP client) reading this, you have access to **Google Antigravity (AGY)** as a background executor/research agent via the `agy-connector` MCP server (`mcp/agy-connector/server.mjs`, wired in `.mcp.json`). It exposes six tools: `antigravity_execute`, `antigravity_research`, `antigravity_result`, `antigravity_wait`, `antigravity_cancel`, and `antigravity_list`.

### Why delegate
Use it to offload heavy work — large refactors, deep codebase research, or repetitive shell/gradle commands — to `agy` and keep your own context small. `agy` runs are agentic and take minutes, so the tools are async: `antigravity_execute`/`antigravity_research` return a `job_id` immediately, and you poll `antigravity_wait(job_id)` (or `antigravity_result(job_id)`) until it's no longer `"running"`.

### When to delegate
- `antigravity_execute` — large or sweeping code changes, multi-file refactors, implementing a feature, or fixing a bug, where you can state a concrete, machine-checkable `success_criteria` (e.g. `"./gradlew testDebugUnitTest passes"` or `"./gradlew assembleDebug succeeds"`).
- `antigravity_research` — deep codebase questions or root-causing across many files, when you only need the conclusion, not the raw file contents.
- Do small, surgical edits yourself — delegation overhead isn't worth it for those.

### How to delegate (async — never block)
1. Call `antigravity_execute` / `antigravity_research` with the absolute `cwd` of this repo. It returns a `job_id` immediately and does NOT block; `agy` runs in the background.
2. Wait for it with `antigravity_wait(job_id, timeout_seconds: 30)` — it blocks up to ~30s and returns as soon as the job finishes. If it comes back `"running"`, call it again. `agy` tasks usually take 1–5 minutes — don't give up early.
3. When `status` is `"done"`/`"error"`, read the result and act on it.
4. Use `antigravity_cancel(job_id)` to stop a job that's gone wrong, and `antigravity_list` to recover a `job_id` or see what's in flight.

### Model selection
Verified via `agy models` on this install (2026-08-20, `agy` 1.1.16):
- `gemini-3.7-flash-high` / `gemini-3.7-flash-medium` / `gemini-3.7-flash-low`
- `gemini-3.6-flash-high` / `gemini-3.6-flash-medium` / `gemini-3.6-flash-low`
- `gemini-3.5-flash-high` / `gemini-3.5-flash-medium` / `gemini-3.5-flash-low`
- `gemini-3.1-pro-high` / `gemini-3.1-pro-low`
- `claude-sonnet-4-6`
- `claude-opus-4-6-thinking`
- `gpt-oss-120b-medium`

Pass the id exactly as printed by `agy models` — it's forwarded verbatim as `agy --model "<id>"`. Use a flash tier for simple edits or fast research; `gemini-3.1-pro-*` or `claude-sonnet-4-6` for moderate reasoning/refactoring; `claude-opus-4-6-thinking` for complex, correctness-critical work (architecture, security, tricky logic); `gpt-oss-120b-medium` as a general-purpose alternative. Re-run `agy models` if this list drifts after an `agy` upgrade.

### Verify — don't trust blindly
- For `execute`, confirm the receipt's `status` is `SUCCESS`, and independently re-run the `success_criteria` yourself (don't just trust the JSON receipt) — `agy` can silently fail (quota errors, empty output) or misreport work it didn't actually do.
- If the receipt is `FAILURE` or `NEEDS_CLARIFICATION`, delegate again with a tighter task description or more `context_files` — don't silently accept it.
- Keep delegated tasks **self-contained**: `agy` starts fresh with no memory of your conversation, so spell out file paths, the goal, and the acceptance check in the `task` itself.

> Full connector docs (security notes, tool schemas, limitations): [mcp/agy-connector/README.md](mcp/agy-connector/README.md)
