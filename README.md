# ArchitectureExploration

A Java 22 / Maven sandbox for prototyping agent-harness architecture: the model-call loop,
tool-use plumbing, MCP (Model Context Protocol) servers/clients, and a prior authorization
demo UI. There's no product domain of its own — just the mechanics of building agents,
compared side by side across two different implementation styles.

## Intended final purpose

The end state this repo is building toward is **Track C**: a clinician-facing prior
authorization assistant that is explainable, actionable, and appeal-ready. Given a patient
and a requested procedure, the agent should pull the patient's real clinical record, search
payer coverage policy for the relevant criteria, and produce a structured determination —
which criteria are met, which are missing, and a draft appeal argument — with full
visibility into which tools it called to get there.

Tracks A and B exist to answer a prior question: *what's the right shape for the agent loop
and its tool plumbing before building a real application on top of it?* Track A is the
hand-rolled baseline; Track B is the hexagonal/Spring-AI alternative. Track C doesn't pick a
winner in the abstract — it's built on Track B's `Agent`, so the comparison's answer, in
practice, is "the hexagonal port-based one is what a real feature got built on."

## How the pieces fit together

```
Track C (web)              builds its Agent on top of →   Track B (hexagonal)
  PriorAuthController             domain.Agent, domain.Tool, domain.ModelClient
  PriorAuthService                 ↓ implemented by adapters
  Get/Search/Fhir tools           AnthropicModelClient, SpringMcpToolAdapter
  result-fragment.html

Track A (minimal harness)   reused as infrastructure, not duplicated:
  FilesystemMcpServer      ←  the MCP server Track B's smoke test connects to over stdio

com.wgblackmon.db           shared, framework-free infrastructure used by both B and C:
  DataSources (HikariCP)   →  Postgres/pgvector connections
  OllamaEmbedder / ingest  →  loads Synthea patient data + CMS/Aetna policy chunks once, offline
```

Concretely, in Track C a request flows: `PriorAuthController` receives the form POST →
`PriorAuthService` builds a fresh `hexagonal.domain.Agent` for that request (it holds
conversation state, so it can't be a shared Spring bean) → the agent calls
`GetPatientRecordTool` (Postgres, via `com.wgblackmon.db.DataSources`) and
`SearchCoveragePolicyTool` (pgvector cosine search over chunks embedded offline by Ollama)
or `FhirPatientTool` (public HAPI FHIR server, for patients without a local DB row) → the
agent's structured text response is parsed into criteria/gaps/appeal sections and rendered
by `result-fragment.html`. Every tool call is wrapped in a `TracingTool` decorator so the UI
can show exactly what the agent did to reach its answer — the "explainable" half of the
goal above.

## Tracks

| Track | Style | Entry point |
|---|---|---|
| A — minimal harness | Hand-rolled against the raw Anthropic Java SDK and raw MCP Java SDK, no framework | `com.wgblackmon.Main` |
| B — hexagonal / Spring AI | Ports-and-adapters domain core, Spring AI for MCP integration, spec-driven tool contracts | `com.wgblackmon.hexagonal.bootstrap.HexagonalMain` |
| C — prior auth UI | Spring Boot + Thymeleaf + Tailwind CSS + HTMX + Alpine.js, illustrating the prior auth agent end-to-end | `com.wgblackmon.web.WebApplication` |

Track A and Track B are not a before/after — they're a deliberate comparison. Nothing from
Track A is meant to be migrated into Track B's structure, and vice versa. Track C is a
concrete application built on top of Track B's `Agent`.

See `CLAUDE.md` for the full architectural rationale (why two tracks, the hexagonal
boundary rule, the Spring AI scope decision, logging conventions) and `prior-auth-plan.md`
for Track C's build log and live-test results.

## Prerequisites

- Java 22, Maven
- A `.env` file in the project root with `ANTHROPIC_API_KEY=...` set (git-ignored)
- For Track C: a running Postgres instance with the `vector` extension enabled, and Ollama
  running locally with the `nomic-embed-text` model pulled (used for policy-chunk embedding)

## Build & run

```sh
./run.sh                                                    # Track A
./run.sh com.wgblackmon.hexagonal.bootstrap.HexagonalMain   # Track B
./run.sh com.wgblackmon.mcp.McpSmokeTest                    # Track A MCP smoke test (no API key needed)
./run.sh com.wgblackmon.hexagonal.HexagonalMcpSmokeTest     # Track B MCP smoke test (no API key needed)
./run.sh com.wgblackmon.web.WebApplication                  # Track C: prior auth UI at http://localhost:8080
```

`run.sh` sources `.env`, compiles, builds the classpath, and runs the given main class via
a direct `java -cp` invocation — deliberately not `mvn exec:java` (see `CLAUDE.md` for why).
Pass `--debug` as the first argument to attach a JDWP debugger on port 5005:

```sh
./run.sh --debug com.wgblackmon.web.WebApplication
```

See `runInstructions.md` for IntelliJ debug-configuration steps and good breakpoint
locations in each track's loop.

## Project layout

```
com.wgblackmon.harness, com.wgblackmon.mcp     Track A
com.wgblackmon.hexagonal                       Track B (domain / adapter / bootstrap)
com.wgblackmon.web                             Track C
com.wgblackmon.db                              Shared DataSource factory (Postgres/pgvector, H2 — no framework)
```

## Status

All three tracks are built; Track C's agent is wired end-to-end and has been live-tested
against real Postgres/pgvector, Ollama embeddings, and the public HAPI FHIR R4 test server.
