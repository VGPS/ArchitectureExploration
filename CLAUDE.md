# ArchitectureExploration — Claude Code Project Memory

## What This Project Is

A Java 22 / Maven sandbox for prototyping agent-harness architecture: the model-call loop,
tool-use plumbing, and MCP (Model Context Protocol) servers/clients. No web layer, no
persistence, no unrelated product domain — just the mechanics of building agents.

The repo deliberately holds **two parallel tracks** so they can be compared side by side:

| Track | Style | Status |
|---|---|---|
| A — minimal harness | Hand-rolled against the raw Anthropic Java SDK and raw MCP Java SDK, no framework | Built |
| B — hexagonal / Spring AI | Ports-and-adapters domain core, Spring AI for MCP integration, spec-driven tool contracts | Built |

Comparing a from-scratch harness against a framework-assisted one *is* the point of an
"architecture exploration" repo — Track B is not a replacement for Track A, and nothing in
Track A should be migrated into Track B's structure.

---

## Build & Run

```sh
./run.sh                                               # Track A: com.wgblackmon.Main
./run.sh com.wgblackmon.hexagonal.bootstrap.HexagonalMain   # Track B equivalent
./run.sh com.wgblackmon.mcp.McpSmokeTest                # Track A MCP smoke test
./run.sh com.wgblackmon.hexagonal.HexagonalMcpSmokeTest  # Track B MCP smoke test
```

`run.sh` sources `.env` (if present), compiles, builds the classpath, and runs the given
main class via a **direct `java -cp` invocation** — deliberately not `mvn exec:java`.
That goal runs `main()` as a thread inside Maven's own JVM rather than a real process, and
it was observed to hang indefinitely (18 minutes wall-clock, 15 seconds CPU — a genuine
deadlock, not a slow API call) once Track B's MCP-via-Spring-AI code path was involved.
Running the identical class directly via `java -cp` completed correctly in just over a
minute. Direct invocation is also what an IDE's Run/Debug configuration does under the
hood, so it's the version that's actually steppable in a debugger — **don't switch this
back to `exec:java`** without re-testing against Track B specifically.

**`.env`** is git-ignored. It was carried over from the AIHealthcare project and still
contains that project's secrets (AWS, Stripe, Alpaca, DB password, etc.) — none of those
are used here. The only variable this repo's code reads is `ANTHROPIC_API_KEY`. Don't add
new secrets to it for this project; if it needs trimming down, ask first rather than editing
it unprompted, since it's shared with intent from another project.

---

## Track A — Minimal Harness (`com.wgblackmon.harness`, `com.wgblackmon.mcp`)

**Philosophy:** no framework until the hand-rolled loop is clearly insufficient. The goal is
to see the exact mechanics (the model-call loop, tool-result round-trip, MCP handshake) —
a framework like Spring AI would hide exactly that, which is why Track A avoids one.

| File | Role |
|---|---|
| `harness/Tool.java` | The one abstraction: name, description, JSON-schema map, `execute(JsonNode)` |
| `harness/Agent.java` | The whole loop: call model → execute requested tools → append results → repeat until no tool call. Thinking is explicitly disabled (`ThinkingConfigDisabled`) to avoid replaying thinking blocks — intentional simplicity, not an oversight. |
| `mcp/server/FilesystemMcpServer.java` | Read-only MCP server (`list_files`, `read_file`, `grep`) over stdio, confined to a root directory with path-traversal rejection — this is the permission boundary |
| `mcp/client/McpToolClient.java` | Connects to an MCP server as a subprocess, discovers its tools, adapts each into a `Tool` |
| `Main.java` | Composition root — wires the MCP-discovered tools into an `Agent` and asks it something |

Don't add an "agent framework" abstraction here (multiple agent classes, a registry, a
plugin system) until there are genuinely ≥2 different agents that need one. One `Agent`
parameterized by its tool set and system prompt is enough for now.

---

## Track B — Hexagonal + Spring AI (`com.wgblackmon.hexagonal`)

### Package layout

```
com.wgblackmon.hexagonal
  domain/      Tool, ModelClient (ports); Role, ContentPart, Message, ModelRequest,
               ModelResponse (JDK-only value types); Agent (orchestration) — zero
               third-party imports anywhere in this package, verified by grep
  adapter/
    model/     AnthropicModelClient implements ModelClient — raw Anthropic SDK, same
               conversion logic as Track A's harness.Agent, just relocated behind the port
    mcp/       SpringMcpToolSource (connects via the raw MCP SDK's StdioClientTransport —
               identical pattern to Track A's McpToolClient — then wraps the resulting
               McpSyncClient in Spring AI's SyncMcpToolCallbackProvider) and
               SpringMcpToolAdapter (implements Tool, wraps one ToolCallback)
  bootstrap/   HexagonalMain — composition root, connects to Track A's existing
               FilesystemMcpServer (reused, not duplicated)
```

### Where Spring AI is used — and where it isn't

**Scope: MCP integration only, via the plain `spring-ai-mcp` artifact — not
`spring-ai-starter-mcp-client`.** The starter pulls in `spring-boot-starter` and expects
`@SpringBootApplication`; the plain artifact gives `SyncMcpToolCallbackProvider` and
`ToolCallback`, both constructible with `new` from a plain `main()`. Confirmed via
`mvn dependency:tree`: no Spring Boot anywhere in the graph, and `spring-ai-mcp`'s own
`mcp-spring-webflux`/`mcp-spring-webmvc` dependencies are declared `optional` in its POM
so they aren't pulled in either — the actual footprint is lighter than it looks on paper.

The model-call loop stays off Spring AI's `ChatClient` — swapping the loop for `ChatClient`
would just be Track A's loop hidden behind a framework, defeating the reason Track B exists
as a *different* comparison point rather than a rename of Track A. `domain.ModelClient` is
a port `Agent` depends on; `AnthropicModelClient` implements it directly against the
Anthropic SDK so the model-call mechanics stay visible. `domain.Tool` is a port;
`SpringMcpToolAdapter` implements it using Spring AI's MCP support.

**One adapter-contract fix worth knowing about:** `ToolCallback.call(String)` returns
Spring AI's own JSON-serialized form of the MCP result (e.g. `[{"text":"..."}]`), not
plain text. `SpringMcpToolAdapter.execute()` unwraps that so `domain.Tool.execute()`
returns the same plain-text shape regardless of which adapter backs it — this was caught
by `HexagonalMcpSmokeTest`, which is exactly what it's there for.

**One known limitation, not a bug:** `SyncMcpToolCallbackProvider` implements
`ApplicationListener<McpToolsChangedEvent>` for cache invalidation when the server's tool
list changes. There's no Spring context here, so that event never fires — `tools()` is a
one-time snapshot, same characteristic Track A's `listTools()` call already has.

### Hexagonal boundary rule

Nothing under `domain/` may import `com.anthropic.*`, `io.modelcontextprotocol.*`,
`org.springframework.*`, or `org.slf4j.*` — a logging framework is an infrastructure
dependency like any other. `domain.Agent` uses the JDK's `System.Logger` instead; adapters
use real SLF4J. If a domain class needs a third-party type, extract a port instead and put
the import in an `adapter/` implementation.

### Spec-driven tool development

MCP tool schemas are already specs (JSON Schema, enforced by the protocol) — lean on that
rather than adding a separate spec format. For every new `Tool` in Track B:

1. Write the JSON-schema `inputSchema` first.
2. Write a short contract alongside it: purpose, inputs, outputs, error modes (a doc comment
   or a few lines in the PR is enough — no separate spec files unless the tool is complex
   enough to warrant one).
3. Write a test against that schema/contract before or alongside the implementation.

This is intentionally lightweight — no spec-kit tooling, no BDD framework, unless a specific
tool's complexity earns it. `HexagonalMcpSmokeTest` is this slice's actual spec-driven
artifact: the filesystem tools' schemas already exist (Track A), so what it verifies is the
adapter's own contract — "MCP schema → domain `Tool` shape, via Spring AI."

### Per-method logging and class-header convention (Track B only)

Carried over from a real convention in a sibling project's `docs/CONVENTIONS.md` that
otherwise doesn't apply here (see git history for how it surfaced). Applies to every file
under `com.wgblackmon.hexagonal` — **not** retrofitted onto Track A, which stays the
deliberately bare baseline:

- Every class/interface/enum/record gets a Javadoc header: one-line purpose, longer
  explanation if needed, `@author`/`@version`/`@since`/`@updated`.
- Every concrete `public`/`protected`/package-private method logs its arguments on the
  first line and its return value immediately before every `return` (`return=void` for
  void methods). Interfaces, abstract methods, and `private` helpers are exempt.
- In `domain/`: use `System.getLogger(ClassName.class.getName())` + `Level.DEBUG`
  (`{0}`-style placeholders) — SLF4J would violate domain purity.
- In `adapter/`: use real SLF4J `log.debug("method() | arg={}", arg)` (`{}`-style
  placeholders) — adapters may depend on infrastructure.
- `src/main/resources/simplelogger.properties` sets `defaultLogLevel=debug` so these
  traces are visible by default without extra JVM flags. Harmless for Track A, which has
  zero log statements today.

---

## Cross-Cutting Conventions (both tracks)

- **Streams are fine, with one nuance.** Use the Java Streams API wherever it's clearer
  than a loop — already used throughout both tracks. Prefer an explicit `for` loop
  specifically when the loop body needs per-iteration debug logging or breakpoints;
  streams are harder to step through one element at a time.
- **No premature abstraction.** Don't introduce a layer, interface, or config knob for a
  problem that doesn't exist yet in this repo. This applies to both tracks — Track B's
  hexagonal boundaries exist because they solve a real coupling problem (`Agent` ↔ SDK types),
  not because hexagonal architecture is the stated goal in the abstract.
- **No comments restating what the code does.** Only comment a non-obvious *why* (e.g. the
  `ThinkingConfigDisabled` note above). Track B's class-header Javadoc is the one explicit,
  bounded exception — that convention requires a purpose/functionality description; it
  doesn't license prose comments inside method bodies restating each line.
- Package root is `com.wgblackmon` (matches the groupId `wgblackmon.com`, reversed).
