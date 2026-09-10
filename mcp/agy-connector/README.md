# agy-connector

An [MCP](https://modelcontextprotocol.io) (Model Context Protocol) server that lets any MCP client delegate **coding** and **research** tasks to the [Antigravity](https://antigravity.google) (`agy`) CLI, which runs as a **background autonomous agent**.

It implements an **orchestrator / executor** pattern: your primary agent (the MCP client) stays in control and offloads heavy, long-running work — sweeping refactors, multi-file edits, deep codebase research — to `agy`. Instead of streaming thousands of lines of terminal output back, `agy` returns a compact, structured JSON receipt, which keeps the orchestrator's context small.

## Why a job model?

`agy` runs are agentic and routinely take **minutes**. Running one synchronously inside a single MCP tool call exceeds the client's request timeout and drops the stdio connection (`-32000 Connection closed`).

This server avoids that: `antigravity_execute` and `antigravity_research` **spawn `agy` in the background and return a `job_id` immediately**. The client then polls `antigravity_result(job_id)` until the job is no longer `running`. Every MCP call returns fast, so long delegations never time out.

```
client → antigravity_execute(task, model, success_criteria)  →  { job_id, status: "running" }
client → antigravity_result(job_id)   →  { status: "running", partial_output_tail }   (poll…)
client → antigravity_result(job_id)   →  { status: "done", result: { …JSON receipt… } }
```

## ⚠️ Security

This server runs `agy` with **`--dangerously-skip-permissions`** — i.e. it launches a **fully autonomous coding agent with no per-action approval prompts**. Once connected, your MCP client can make `agy` read, write, and execute on your machine **without confirmation**.

- Only enable this connector in environments you trust, on repositories you're willing to let an autonomous agent modify.
- Treat the connected MCP client as having the same power over your machine that you've granted `agy`.

## Prerequisites

- **Node.js ≥ 18**
- The **Antigravity `agy` CLI**, installed and on your `PATH`, and **authenticated** (run `agy` once interactively to log in). Verify with:
  ```bash
  agy --version
  ```

## Tools

Valid `model` values are the exact ids printed by `agy models` (this repo's install, `agy` 1.1.10):

- `gemini-3.6-flash-high` / `gemini-3.6-flash-medium` / `gemini-3.6-flash-low`
- `gemini-3.5-flash-high` / `gemini-3.5-flash-medium` / `gemini-3.5-flash-low`
- `gemini-3.1-pro-high` / `gemini-3.1-pro-low`
- `claude-sonnet-4-6`
- `claude-opus-4-6-thinking`
- `gpt-oss-120b-medium`

Run `agy models` on your own install to confirm — this list depends on the installed `agy` version and your Antigravity subscription/tier.

### `antigravity_execute`
Delegate a development task (writing code, implementing a feature, fixing a bug).

| Field | Required | Description |
|---|---|---|
| `task` | yes | Detailed description of what to do. |
| `model` | yes | Exact id from `agy models`. A flash tier for quick tasks; `gemini-3.1-pro-*` / `claude-sonnet-4-6` for moderate reasoning; `claude-opus-4-6-thinking` for complex, correctness-critical work. |
| `success_criteria` | yes | Explicit conditions `agy` must verify before finishing, e.g. `"./gradlew testDebugUnitTest passes"`. |
| `context_files` | no | Array of absolute file paths to point `agy` at the relevant code. |
| `cwd` | no | Absolute working directory to run `agy` in (the repo/project to operate on). Defaults to the server's current directory. |

Returns `{ job_id, status: "running" }`. When done, `antigravity_result` / `antigravity_wait` returns a JSON receipt:
`{ status, summary, files_changed, verification_details, failure_reason }`.

### `antigravity_research`
Delegate a codebase research or debugging investigation.

| Field | Required | Description |
|---|---|---|
| `question` | yes | The research question or bug description. |
| `model` | yes | Exact id from `agy models`. A flash tier is usually sufficient; a pro/thinking tier for deep or ambiguous investigations. |
| `cwd` | no | Absolute working directory to run `agy` in. Defaults to the server's current directory. |

Returns `{ job_id, status: "running" }`. When done, returns a concise summary with file paths / line numbers.

### `antigravity_result`
Poll a background job by `job_id` (non-blocking). Returns `{ status: "running" | "done" | "error" | "cancelled", … }`. While `running`, wait a bit and poll again — `agy` tasks take minutes. Output is streamed to `<os tmpdir>/agy-jobs/<job_id>.out`.

### `antigravity_wait`
Bounded long-poll. Blocks until the job finishes **or** `timeout_seconds` elapses (default 25, **capped at 50s** to stay under the MCP request timeout), then returns the same shape as `antigravity_result`. Prefer this over sleep+poll loops; if it returns `running`, call it again.

### `antigravity_cancel`
Terminate a running job by `job_id` (SIGTERM to the `agy` process). Returns the resulting status; no-op if the job already finished.

### `antigravity_list`
List all jobs the server knows about (in-memory; cleared on restart), each with `status`, `kind`, `model`, `cwd`, `exit_code`, and `created`. Useful to recover a `job_id` or see what's in flight.

## Tips for good results

- Give `execute` a **strict, machine-checkable `success_criteria`** (a command that exits 0). The agent is told to verify it before returning.
- Use `gemini-3.1-pro-*` or `claude-sonnet-4-6` for architecture/refactors, a flash tier for small edits and most research, and `claude-opus-4-6-thinking` for complex, correctness-critical work.
- Provide `context_files` so the agent starts in the right place, and `cwd` to point it at the right repo.
- Use `antigravity_wait` instead of your own sleep+poll loop.

## Limitations

- **Jobs are in-memory.** If the server process restarts, known `job_id`s are forgotten (the `.out` files remain on disk). `antigravity_list` only sees jobs from the current process lifetime.
- **Output files are not auto-cleaned.** `<os tmpdir>/agy-jobs/` grows over time; clear it periodically if needed.
- **No *automatic* per-job timeout.** A stuck `agy` run stays `running` until it exits or you `antigravity_cancel` it.
