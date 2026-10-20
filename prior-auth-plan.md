# Prior Authorization Agent — Build Plan

**Last updated:** 2026-10-19  
**Goal:** A clinician-facing prior authorization assistant that is explainable, actionable,
and appeal-ready — using all available tools: Postgres, pgvector, MCP, hexagonal agent
harness, Ollama embeddings, and a Thymeleaf/HTMX/Alpine.js web UI.

---

## Current State

| Item | Status |
|---|---|
| Database schema applied (patients, conditions, medications, observations, coverage_policy_chunks) | ✅ Done |
| pgvector extension + HNSW index on embedding column | ✅ Done |
| `GetPatientRecordTool` — stub returning hardcoded bariatric patient | ✅ Stub |
| `SearchCoveragePolicyTool` — stub returning hardcoded LCD L38467 text | ✅ Stub |
| `PriorAuthService` wiring agent + tools into Spring Boot controller | ✅ Stub |
| Track C UI: form + HTMX result fragment | ✅ Stub |
| Ollama installed + nomic-embed-text model | ✅ Done |
| `DataSources` factory (HikariCP, Postgres + H2) | ✅ Done |

---

## Phase 1 — Data

Guiding rule: **data structure first, then data, then process, then view.**  
Schema is already done. This phase fills it with real content.

### 1a. Synthea Patient Data  `[ ]`

**What:** Generate a synthetic patient population with realistic bariatric surgery candidates,
parse the FHIR R4 JSON output, load into `patients` / `patient_conditions` /
`patient_medications` / `patient_observations`.

**Files to create:**
- `com.wgblackmon.db.SyntheaIngestMain` — standalone `main()`, no framework

**Steps:**
1. Download `synthea-with-dependencies.jar` (latest release from GitHub).
   Place in `tools/` directory (git-ignored like `tailwindcss.exe`).
2. Run Synthea targeting obesity/bariatric population:
   ```
   java -jar tools/synthea.jar -p 50 \
     --exporter.fhir.export=true \
     --exporter.baseDirectory=synthea-output \
     -m "obesity" "Massachusetts"
   ```
3. `SyntheaIngestMain` parses `synthea-output/fhir/*.json` (FHIR R4 bundles via Jackson).
   Resources needed:
   - `resourceType: Patient` → `patients` (name, birthDate, gender)
   - `resourceType: Condition` (ICD-10 coded) → `patient_conditions`
   - `resourceType: MedicationRequest` → `patient_medications`
   - `resourceType: Observation` (LOINC coded) → `patient_observations`
     - BMI: LOINC 39156-5
     - Weight: LOINC 29463-7
     - Height: LOINC 8302-2
4. Calculate BMI on ingestion from height/weight observations; store in `patients.bmi`.
5. Run: `./run.sh com.wgblackmon.db.SyntheaIngestMain`

**Design note:** `SyntheaIngestMain` lives in `com.wgblackmon.db` — neutral infrastructure,
not tied to either Track A or B. Uses `DataSources.postgres()` directly.

---

### 1b. CMS LCD Policy Ingestion  `[ ]`

**What:** Load CMS Local Coverage Determination L38467 (Bariatric Surgery) into
`coverage_policy_chunks`, with Ollama-generated embeddings for semantic search.

**Files to create:**
- `com.wgblackmon.db.OllamaEmbedder` — HTTP client for `POST /api/embeddings`
- `com.wgblackmon.db.LcdIngestMain` — standalone `main()`, chunks + embeds + loads

**Steps:**
1. Obtain LCD text. Options (in order of preference):
   - Download from CMS Coverage Database as plain text / PDF text extract.
   - Use the HTML version and strip tags.
   - URL: `https://www.cms.gov/medicare-coverage-database/view/lcd.aspx?lcdid=38467`
   - Save as `data/lcd-L38467.txt` (git-ignored — raw policy text, large).
2. `LcdIngestMain` chunks the text:
   - Split on section headings and double-newlines.
   - Target ~400-600 words per chunk (fits comfortably in nomic-embed-text's 8192-token window).
   - Record `section_title`, `chunk_index`, `source_document = "CMS LCD L38467"`.
3. For each chunk, call `OllamaEmbedder.embed(text)`:
   ```
   POST http://localhost:11434/api/embeddings
   {"model": "nomic-embed-text", "prompt": "<chunk text>"}
   → {"embedding": [float, ...]}   // 768 dimensions
   ```
4. Insert each chunk + embedding into `coverage_policy_chunks`.
5. Run: `./run.sh com.wgblackmon.db.LcdIngestMain`

**Design note:** `OllamaEmbedder` is a plain HTTP client (JDK `HttpClient`, no SDK) —
Ollama has a simple REST API. Keep it in `com.wgblackmon.db`.

---

## Phase 2 — Process

Replace both stubs with real database queries. Enhance the agent's output format to
include criteria status, gaps, and appeal points.

### 2a. Real `GetPatientRecordTool`  `[ ]`

Replace the hardcoded stub in `com.wgblackmon.hexagonal.adapter.db.GetPatientRecordTool`
with a real SQL query against the ingested Synthea data:

```sql
SELECT p.*,
       array_agg(DISTINCT pc.icd10_code || ': ' || pc.description) AS conditions,
       array_agg(DISTINCT pm.medication_name)                       AS medications,
       array_agg(DISTINCT po.loinc_code || ' ' || po.description
                 || '=' || po.value || ' ' || po.unit)              AS observations
FROM patients p
LEFT JOIN patient_conditions  pc ON pc.patient_id = p.patient_id AND pc.status = 'active'
LEFT JOIN patient_medications pm ON pm.patient_id = p.patient_id AND pm.status = 'active'
LEFT JOIN patient_observations po ON po.patient_id = p.patient_id
WHERE p.patient_id = ?
GROUP BY p.patient_id
```

Returns a structured text summary the agent can reason over.  
Needs a `DataSource` injected — `DataSources.postgres()` called once in constructor.

---

### 2b. Real `SearchCoveragePolicyTool`  `[ ]`

Replace the stub in `com.wgblackmon.hexagonal.adapter.db.SearchCoveragePolicyTool`
with:
1. Embed the incoming query string via `OllamaEmbedder`.
2. Run pgvector cosine similarity search:
   ```sql
   SELECT chunk_text, source_document, section_title,
          1 - (embedding <=> ?::vector) AS similarity
   FROM coverage_policy_chunks
   ORDER BY embedding <=> ?::vector
   LIMIT 5
   ```
3. Return the top-5 chunks concatenated, with section titles as headers.

This is the key differentiator from a chatbot: the agent retrieves **actual payer policy
text**, not a hallucination about what policies say.

---

### 2c. Enhanced Agent Output Format  `[ ]`

Update `PriorAuthService.systemPrompt()` to request structured output with four sections:

```
DETERMINATION: APPROVED | DENIED | NEEDS_MORE_INFO

CRITERIA:
- BMI ≥ 35 with comorbidity: MET (BMI 42.1, T2DM documented)
- 6-month supervised weight loss: MET (chart documents 7 months, Dr. Smith)
- Psychological evaluation: MET (psych clearance 2024-08-15)
- No active substance use disorder: MET (no relevant conditions)

GAPS:
- None

APPEAL_POINTS:
- N/A (approved)

RATIONALE:
<narrative paragraph>
```

Update `PriorAuthResult` record and `PriorAuthService.parseDetermination()` to extract
all four sections.

New/modified files:
- `PriorAuthResult.java` — add `List<CriterionResult> criteria`, `List<String> gaps`, `List<String> appealPoints`
- `CriterionResult.java` — new record: `(String criterion, String status, String evidence)`
- `PriorAuthService.java` — updated system prompt + richer response parsing

---

## Phase 3 — View

Enrich the result fragment to display criteria, gaps, and appeal letter.

### 3a. Criteria Checklist  `[ ]`

In `result-fragment.html`, render `result.criteria` as a visual checklist:
- ✅ green badge for MET
- ❌ red badge for UNMET
- ⚠️ amber badge for INSUFFICIENT

Use Alpine.js for the expand/collapse of evidence text per criterion.

### 3b. Gap Analysis Panel  `[ ]`

If `result.gaps` is non-empty, show a distinct amber panel:
> "**3 items need attention before approval:**  
> • Supervised weight loss: only 4 months documented, 6 required — obtain visit notes for months 5-6 or attending attestation letter  
> • ..."

This is the highest-value screen for a clinical coordinator.

### 3c. Appeal Letter Section  `[ ]`

If `determination == DENIED`, show a collapsible "Draft Appeal Letter" panel.
Contents from `result.appealPoints` + rationale, formatted as a letter.
Alpine.js "Copy to clipboard" button on the panel.

### 3d. Policy Citation Sidebar  `[ ]`

Display the tool call trace in a collapsible "What the agent consulted" section:
- Which policy chunks were retrieved (from `SearchCoveragePolicyTool` output)
- The patient record summary (from `GetPatientRecordTool` output)

Builds clinician trust — they can see the agent didn't hallucinate policy language.

---

## Architecture Map

```
HTTP Request (patientId, cptCode, icdCode, clinicalNotes)
    │
    ▼
PriorAuthController (Track C, Spring Boot)
    │
    ▼
PriorAuthService
    ├── creates TracingTool wrappers
    ├── creates hexagonal.domain.Agent (fresh per request — Agent is stateful)
    └── calls agent.run(systemPrompt, userMessage)
              │
              ├── AnthropicModelClient → Claude API (claude-sonnet-5)
              │
              ├── GetPatientRecordTool → Postgres (patients + conditions + medications)
              │
              └── SearchCoveragePolicyTool
                      ├── OllamaEmbedder → Ollama (nomic-embed-text, 768d)
                      └── pgvector cosine search → coverage_policy_chunks
    │
    ▼
PriorAuthResult (determination, criteria[], gaps[], appealPoints[], toolCalls[])
    │
    ▼
result-fragment.html (Thymeleaf + HTMX + Alpine.js)
    ├── Determination badge
    ├── Criteria checklist
    ├── Gap analysis panel (amber, if gaps > 0)
    ├── Appeal letter (collapsible, if denied)
    └── Policy citations (collapsible — "what the agent consulted")
```

---

## Phase 4 — FHIR + Commercial Policy (✅ Done 2026-10-19)

### 4a. Aetna CPB 0157 Ingestion
`data/aetna-cpb-0157.txt` — fetched from Aetna's public website (HTML → plain text).
`com.wgblackmon.db.AetnaIngestMain` — same chunking/embedding pattern as `LcdIngestMain`.
Result: 12 chunks in `coverage_policy_chunks` with `source_document = "Aetna CPB 0157"`.
The `SearchCoveragePolicyTool` automatically returns Aetna chunks alongside CMS chunks —
pgvector cosine search doesn't care which source the chunk came from.

Key differences from CMS LCD L38467:
- **12 sessions** on separate dates (CMS: 6 months physician-supervised)
- Psychosocial assessment required for **all members** (CMS: only for psychiatric history)
- Supervision flexibility: dietitian/coach OK (CMS: physician oversight implied)
- Asian ancestry BMI thresholds (37.5 and 32.5)

### 4b. HAPI FHIR Connector
`com.wgblackmon.hexagonal.adapter.fhir.FhirPatientTool` — queries HAPI FHIR public test
server (`https://hapi.fhir.org/baseR4`) without authentication.

Architecture:
- 5 HTTP calls per patient: Patient, Condition, MedicationRequest, dedicated vitals query
  (LOINC 39156-5/29463-7/8302-2/8480-6/8462-4), all observations
- Dedicated vitals query prevents BMI from being lost if it falls outside the top-N in
  the general observation set (NHANES patients have 30 observations, BMI was ~position 25)
- Returns same structured text format as `GetPatientRecordTool`

Demo patient: `nhanes-120300`
- Real NHANES BMI: 38.39 kg/m² (measured 2026-01-15)
- Real HbA1c: 7.0% (confirms T2DM context when provided in notes)
- No conditions/medications in FHIR record — realistic: supplement with clinical notes

**Live-tested result (2026-10-19):**
Patient nhanes-120300 with T2DM + 13 behavioral sessions → NEEDS_MORE_INFO
Rationale cited both CMS LCD L38467 AND Aetna CPB 0157 correctly.
BMI (38.39) and T2DM MET; gap was psychosocial assessment documentation completeness.

---

## Data Sources

| Data | Format | Source | Storage |
|---|---|---|---|
| Patient records (local) | FHIR R4 JSON | Synthea synthetic generator | Postgres: patients, conditions, medications, observations |
| Patient records (live) | FHIR R4 JSON | HAPI FHIR public server (NHANES data) | Live API call — no local storage |
| Bariatric surgery policy (Medicare) | Plain text | CMS LCD L38467 | Postgres: coverage_policy_chunks + vector(768) embeddings |
| Bariatric surgery policy (commercial) | Plain text | Aetna CPB 0157 | Postgres: coverage_policy_chunks + vector(768) embeddings |
| Embeddings | float[] 768d | Ollama nomic-embed-text | Stored in coverage_policy_chunks.embedding |

---

## Files To Create (Checklist)

### Phase 1 — Data Ingestion
- `[x]` `tools/synthea-with-dependencies.jar` (downloaded, git-ignored)
- `[x]` `data/lcd-L38467.txt` (representative text, git-ignored)
- `[x]` `data/demo-patients.sql` (3 demo scenarios: approved / needs-more-info / denied)
- `[x]` `.gitignore` entries for `tools/synthea*.jar` and `data/`
- `[x]` `com.wgblackmon.db.OllamaEmbedder`
- `[x]` `com.wgblackmon.db.SyntheaIngestMain`
- `[x]` `com.wgblackmon.db.LcdIngestMain`

**Phase 1 result:** 130 patients (127 Synthea + 3 demo), 9750 conditions, 11175 medications,
130365 observations, 14 policy chunks with pgvector embeddings. All live in Postgres.

### Phase 2 — Process
- `[x]` `com.wgblackmon.hexagonal.adapter.db.GetPatientRecordTool` (real SQL, 4-table query)
- `[x]` `com.wgblackmon.hexagonal.adapter.db.SearchCoveragePolicyTool` (pgvector cosine search via Ollama)
- `[x]` `com.wgblackmon.web.service.CriterionResult` (new record: criterion/status/evidence)
- `[x]` `com.wgblackmon.web.service.PriorAuthResult` (criteria/gaps/appealPoints added)
- `[x]` `com.wgblackmon.web.service.PriorAuthService` (enhanced system prompt + structured parser)
- `[x]` `pom.xml` — maven-compiler-plugin with -parameters flag
- `[x]` `PriorAuthController` — explicit @RequestParam name attributes

### Phase 3 — View
- `[x]` `result-fragment.html` (criteria checklist, gap panel, appeal letter, citations)

**Phase 2 result (live-tested 2026-10-19):** demo-001 (Margaret Thornton, BMI 46.3) returns
APPROVED with 7-criterion checklist (4 MET, 3 INSUFFICIENT for secondary docs), 5 specific
gap items, real LCD L38467 policy chunks retrieved via pgvector cosine similarity (top score
0.81), and collapsible "What the agent consulted" trace showing both tool calls.

### Phase 4 — FHIR + Commercial Policy
- `[x]` `data/aetna-cpb-0157.txt` (fetched from Aetna website, 12 sections)
- `[x]` `com.wgblackmon.db.AetnaIngestMain` (12 chunks embedded + stored in Postgres)
- `[x]` `com.wgblackmon.hexagonal.adapter.fhir.FhirPatientTool` (HAPI FHIR R4 client)
- `[x]` `PriorAuthService` — added FhirPatientTool to agent tools list + updated system prompt
- `[x]` `index.html` — Patient ID field shows both formats

**Phase 4 result (live-tested 2026-10-19):** FHIR patient nhanes-120300 (BMI 38.39 kg/m²
from NHANES) returns NEEDS_MORE_INFO with dual-policy analysis citing both CMS LCD L38467
and Aetna CPB 0157. Dedicated vitals LOINC query ensures BMI is surfaced even when buried
in pagination. Agent correctly distinguishes FHIR vs local DB patients from ID format.

---

## Key Decisions Log

| Decision | Rationale |
|---|---|
| Synthea for patient data | Realistic FHIR-format data, free, local, no PHI risk |
| CMS LCD L38467 for policy | Real payer policy, publicly available, specific enough to demonstrate criteria matching |
| Ollama nomic-embed-text | Local/private, no API key, 768d fits pgvector well, appropriate for healthcare data |
| pgvector cosine search over BM25 | Semantic matching finds relevant policy text even when wording differs from the clinical query |
| One CPT code (43770 bariatric) | One procedure done well is more convincing than five done shallowly |
| Standalone ingestion scripts | No versioning risk, stable, simple to re-run; not MCP tools or agent-callable operations |
| Agent-per-request (not Spring bean) | hexagonal.domain.Agent holds List<Message> history as instance state |
| TracingTool wrapper | Captures tool calls for UI without touching domain Agent |
| DB_PASSWORD matches AIHealthcare .env | Avoids duplicate entries in the shared .env file |
