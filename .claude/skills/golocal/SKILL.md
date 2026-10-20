---
name: golocal
description: Kill, review, build, and restart Track C (prior-auth UI) locally
---

# Go Local — Full Local Rebuild and Restart

Kill running app, review code for errors, build, restart Track C locally, and verify.

## Steps — execute in order, stop on any failure

### 1. Kill running Java processes

```bash
taskkill //F //FI "IMAGENAME eq java.exe" 2>/dev/null; echo "done"
```

### 2. Review current code — compile check

Run a compile-only build to surface any errors before committing to a full build:

```bash
mvn compile -q 2>&1
```

If this produces any output, **STOP** and report every compilation error to the user. Do not proceed to the build step. List each error with file path and line number.

### 3. Run the MCP smoke tests

This repo has no JUnit suite — the two tests that exist (`McpSmokeTest`,
`HexagonalMcpSmokeTest`) are plain `main()`-based smoke tests covering Track A and
Track B's MCP plumbing, not the web layer (each file's header says so explicitly —
`mvn test` reports "No tests were executed" against them). Run them only if the changed
files touch `com.wgblackmon.mcp` or `com.wgblackmon.hexagonal`:

```bash
git diff --name-only HEAD -- '*.java'
git ls-files --others --exclude-standard -- '*.java'
```

If any changed/untracked path is under `src/main/java/com/wgblackmon/mcp/` or
`src/main/java/com/wgblackmon/hexagonal/`, run each via `run.sh` (same direct `java -cp`
invocation documented in `CLAUDE.md` — these cannot run via `mvn test`):

```bash
./run.sh com.wgblackmon.mcp.McpSmokeTest 2>&1 | tail -20
./run.sh com.wgblackmon.hexagonal.HexagonalMcpSmokeTest 2>&1 | tail -20
```

Otherwise (changes confined to `com.wgblackmon.web` / templates / static assets), skip
testing — there's nothing to run against it yet.

`McpSmokeTest` succeeds if it prints `list_files` and `grep` output with no stack trace.
`HexagonalMcpSmokeTest` prints `All assertions passed.` on success. If either throws an
exception or exits without that line, **STOP** and report:
- Which smoke test failed and the exception/stack trace
- A brief explanation of what went wrong

Do not proceed to the next step.

### 4. Detect front-end changes

Before starting the app, check if any templates or static assets were modified in this session:

```bash
git diff --name-only HEAD -- '*.html' '*.css' '*.js' 2>/dev/null
git diff --name-only --cached -- '*.html' '*.css' '*.js' 2>/dev/null
git ls-files --others --exclude-standard -- '*.html' '*.css' '*.js' 2>/dev/null
```

Save the list of changed front-end files — you will report them with URLs in step 6.

### 5. Start the app locally

Use `run.sh`, **not** `mvn spring-boot:run` — see `CLAUDE.md`: Track B's MCP-via-Spring-AI
code path was observed to deadlock under `mvn exec:java`, and `run.sh` standardizes on a
direct `java -cp` invocation instead for all main classes in this repo, including Track C.
`run.sh` already sources `.env` itself, so don't source it separately first.

Run in background with `timeout 120000`:

```bash
./run.sh com.wgblackmon.web.WebApplication
```

Then wait for startup:

```bash
sleep 20 && curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/prior-auth
```

Should return `200`. If connection refused, wait 15 more seconds and retry once. If still failing after 35 seconds total, check the background task output for errors and report them.

### 6. Report results

Report a summary to the user:

**Always include:**
- Test status (ran / skipped, and why)
- Build status (SUCCESS)
- App status (running at `http://localhost:8080`)
- Form URL: `http://localhost:8080/prior-auth`

**If front-end files changed (from step 4), also include a "Changed Pages" section listing the affected URLs.** Map template filenames to URLs using this table:

| Template | URL |
|----------|-----|
| `prior-auth/index.html` | `/prior-auth` |
| `prior-auth/result-fragment.html` | `/prior-auth` (HTMX swap fragment — not a standalone route) |
| `tailwind.css` or `styles.css` | *(all pages — styles changed)* |

Prefix each URL with `http://localhost:8080` so the user can click directly.

## Notes

- Track C has no login — the only route is `/prior-auth` (form) and `POST /prior-auth/submit` (HTMX fragment).
- No database is wired into Track C yet; Postgres/H2 are available via `com.wgblackmon.db.DataSources` but unused by the web layer today (see `CLAUDE.md`'s Databases section).
- `PriorAuthController.submit()` currently returns a stub fragment — the hexagonal `Agent` (Track B) isn't wired in yet, so results are canned, not live model/tool output.
- Startup is fast (no LLM-backed warm-up step like AIHealthcare's topic summaries) — the 20s wait in step 5 is for Spring Boot boot time only.
