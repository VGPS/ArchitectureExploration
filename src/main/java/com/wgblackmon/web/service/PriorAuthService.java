package com.wgblackmon.web.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.wgblackmon.db.DataSources;
import com.wgblackmon.db.OllamaEmbedder;
import com.wgblackmon.hexagonal.adapter.db.GetPatientRecordTool;
import com.wgblackmon.hexagonal.adapter.db.SearchCoveragePolicyTool;
import com.wgblackmon.hexagonal.adapter.fhir.FhirPatientTool;
import com.wgblackmon.hexagonal.adapter.model.AnthropicModelClient;
import com.wgblackmon.hexagonal.domain.Agent;
import com.wgblackmon.hexagonal.domain.ContentPart;
import com.wgblackmon.hexagonal.domain.Message;
import com.wgblackmon.hexagonal.domain.ModelRequest;
import com.wgblackmon.hexagonal.domain.Role;
import com.wgblackmon.hexagonal.domain.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Spring service that runs the prior authorization agent for one HTTP request.
 *
 * <p>A new {@link Agent} is created per call — {@code Agent} is stateful (holds
 * conversation history). The model client, data source, and embedder are shared
 * and created once at construction time.
 *
 * <p>Clinical notes are pre-processed by a cheap Haiku 4.5 call before the main Sonnet 5
 * agent runs. Haiku extracts structured fields (behavioral sessions, comorbidities, psych
 * eval status, BMI in notes) so Sonnet receives a clean summary alongside the raw notes,
 * reducing context noise and improving criterion matching accuracy.
 *
 * <p>Tool calls made during the agent run are captured by {@link TracingTool} wrappers
 * and returned in {@link PriorAuthResult#toolCalls()} for display in the UI.
 */
@Service
public class PriorAuthService {

    private static final Logger log = LoggerFactory.getLogger(PriorAuthService.class);

    private static final String SYSTEM_PROMPT = """
            You are a prior authorization specialist at a health insurance company evaluating
            requests for bariatric surgery (CPT 43770 / 43644 / 43775).

            PATIENT RECORD LOOKUP — choose the right tool based on the patient ID format:
            - get_patient_record: use for local database patients with IDs like "demo-001",
              "demo-002", or Synthea-generated UUIDs. These records are complete.
            - get_fhir_patient_record: use for FHIR server patients with IDs like "nhanes-120300".
              FHIR records may have real vital signs (BMI, weight) but sparse condition/medication
              data. Supplement with any clinical notes provided in the request.

            When evaluating a request:
            1. Choose the appropriate patient lookup tool based on the patient ID format and call it.
            2. Call search_coverage_policy with a query like "bariatric surgery BMI criteria \
            supervised weight loss psychological evaluation comorbidities" to retrieve applicable
            policy sections. The policy database now contains both CMS LCD L38467 and Aetna CPB 0157.
            3. Evaluate the patient record against every coverage criterion found in the retrieved
            policy sections. If the record has gaps, check whether the clinical notes fill them.

            Respond using EXACTLY this format (do not add extra sections or change section names):

            DETERMINATION: [APPROVED | DENIED | NEEDS_MORE_INFO]

            CRITERIA:
            - [criterion description]: [MET | UNMET | INSUFFICIENT] ([brief evidence from chart])
            - [criterion description]: [MET | UNMET | INSUFFICIENT] ([brief evidence from chart])
            (one line per criterion, cover all major criteria: BMI threshold, comorbidity if needed,\
             behavioral intervention program, psychological/psychosocial evaluation, no contraindications)

            GAPS:
            - [specific missing item and how to remedy it]
            (write "None" if determination is APPROVED with no gaps)

            APPEAL_POINTS:
            - [point supporting an appeal with policy citation]
            (write "N/A" if approved; list actionable points if denied or needs more info)

            RATIONALE:
            [2-4 sentences summarizing the overall determination with reference to specific policy
            criteria and patient evidence. Cite the source document(s) (e.g. CMS LCD L38467,
            Aetna CPB 0157) that apply.]
            """;

    private static final String EXTRACTION_SYSTEM_PROMPT = """
            You are a clinical data extractor. Extract specific factual items from provider
            clinical notes for a bariatric surgery prior authorization review.

            Return ONLY what is explicitly stated — do not infer, interpret, or add context.
            Use "not mentioned" for any field not present in the notes.

            Output exactly these labeled lines:
            Behavioral program: [session count and/or duration and supervisor type, or "not mentioned"]
            Comorbidities stated: [list any named — T2DM, hypertension, sleep apnea, coronary disease, etc.]
            Psychosocial/psych evaluation: [completed/not completed, date if given, evaluator credentials if given]
            BMI or weight in notes: [value if stated, or "not mentioned"]
            Contraindications or red flags: [any explicitly mentioned, or "none mentioned"]
            Other relevant facts: [any other clinical facts pertinent to bariatric surgery authorization]
            """;

    private final AnthropicModelClient modelClient;
    private final AnthropicModelClient haikuClient;
    private final DataSource dataSource;
    private final OllamaEmbedder embedder;

    public PriorAuthService() {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();
        this.modelClient = new AnthropicModelClient(client, "claude-sonnet-5");
        this.haikuClient = new AnthropicModelClient(client, "claude-haiku-4-5-20251001");
        this.dataSource  = DataSources.postgres();
        this.embedder    = new OllamaEmbedder();
        log.debug("<init>() | modelClient (sonnet-5), haikuClient (haiku-4.5), dataSource, embedder initialized");
    }

    public PriorAuthResult analyze(String patientId, String cptCode,
                                   String icdCode, String clinicalNotes) {
        log.debug("analyze() | patientId={}, cptCode={}, icdCode={}", patientId, cptCode, icdCode);

        String extracted = extractClinicalFindings(clinicalNotes);

        List<ToolCallRecord> trace = new ArrayList<>();
        List<Tool> tools = List.of(
                new TracingTool(new GetPatientRecordTool(dataSource), trace),
                new TracingTool(new FhirPatientTool(), trace),
                new TracingTool(new SearchCoveragePolicyTool(dataSource, embedder), trace));

        String extractedSection = extracted.isBlank() ? "" : """

                PRE-EXTRACTED CLINICAL FINDINGS (from provider notes, via Haiku 4.5):
                %s
                """.formatted(extracted);

        String prompt = """
                Evaluate this prior authorization request:

                Patient ID        : %s
                Procedure (CPT)   : %s
                Diagnosis (ICD-10): %s
                %s
                Clinical Notes from Provider:
                %s
                """.formatted(patientId, cptCode, icdCode, extractedSection, clinicalNotes);

        Agent agent = new Agent(modelClient, SYSTEM_PROMPT, tools);
        String response = agent.run(prompt);

        PriorAuthResult result = parseResponse(response, trace);
        log.debug("analyze() | determination={} criteria={} gaps={}",
                result.determination(), result.criteria().size(), result.gaps().size());
        return result;
    }

    // --- Haiku pre-extraction -----------------------------------------------------

    private String extractClinicalFindings(String notes) {
        if (notes == null || notes.isBlank()) {
            log.debug("extractClinicalFindings() | notes blank, skipping Haiku call");
            return "";
        }
        log.debug("extractClinicalFindings() | calling Haiku 4.5 to extract structured fields");
        try {
            ModelRequest request = new ModelRequest(
                    EXTRACTION_SYSTEM_PROMPT,
                    List.of(new Message(Role.USER, List.of(new ContentPart.Text(notes)))),
                    List.of());
            String extracted = haikuClient.send(request).content().stream()
                    .filter(p -> p instanceof ContentPart.Text)
                    .map(p -> ((ContentPart.Text) p).value())
                    .collect(Collectors.joining("\n"))
                    .strip();
            log.debug("extractClinicalFindings() | return (length={})", extracted.length());
            return extracted;
        } catch (Exception e) {
            log.warn("extractClinicalFindings() | Haiku call failed, proceeding without extraction: {}", e.getMessage());
            return "";
        }
    }

    // --- Response parsing ---------------------------------------------------------

    private static PriorAuthResult parseResponse(String text, List<ToolCallRecord> trace) {
        String determination  = parseDetermination(text);
        List<CriterionResult> criteria = parseCriteria(text);
        List<String> gaps         = parseBlock(text, "GAPS");
        List<String> appealPoints = parseBlock(text, "APPEAL_POINTS");
        String rationale          = parseRationale(text);

        return new PriorAuthResult(determination, rationale, criteria, gaps, appealPoints,
                List.copyOf(trace));
    }

    private static String parseDetermination(String text) {
        Pattern p = Pattern.compile("DETERMINATION:\\s*(APPROVED|DENIED|NEEDS_MORE_INFO)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        if (m.find()) return m.group(1).toUpperCase();
        // Fallback keyword scan
        String upper = text.toUpperCase();
        if (upper.contains("APPROVED"))       return "APPROVED";
        if (upper.contains("DENIED"))         return "DENIED";
        return "NEEDS_MORE_INFO";
    }

    private static List<CriterionResult> parseCriteria(String text) {
        List<CriterionResult> results = new ArrayList<>();
        String block = extractBlock(text, "CRITERIA", "GAPS");
        if (block == null) return results;

        // Each criterion line: "- some description: MET (evidence)" or "- desc: UNMET (evidence)"
        Pattern p = Pattern.compile(
                "^\\s*-\\s*(.+?):\\s*(MET|UNMET|INSUFFICIENT)\\s*(?:\\((.*)\\))?\\s*$",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        Matcher m = p.matcher(block);
        while (m.find()) {
            String criterion = m.group(1).strip();
            String status    = m.group(2).toUpperCase();
            String evidence  = m.group(3) != null ? m.group(3).strip() : "";
            results.add(new CriterionResult(criterion, status, evidence));
        }
        return results;
    }

    private static List<String> parseBlock(String text, String sectionName) {
        List<String> items = new ArrayList<>();
        String nextSection = switch (sectionName) {
            case "GAPS"          -> "APPEAL_POINTS";
            case "APPEAL_POINTS" -> "RATIONALE";
            default              -> null;
        };
        String block = extractBlock(text, sectionName, nextSection);
        if (block == null) return items;

        for (String line : block.split("\n")) {
            String trimmed = line.replaceFirst("^\\s*-\\s*", "").strip();
            if (!trimmed.isEmpty() && !trimmed.equalsIgnoreCase("none")
                    && !trimmed.equalsIgnoreCase("n/a")) {
                items.add(trimmed);
            }
        }
        return items;
    }

    private static String parseRationale(String text) {
        int idx = text.toUpperCase().indexOf("RATIONALE:");
        if (idx < 0) return text.strip();
        return text.substring(idx + "RATIONALE:".length()).strip();
    }

    /**
     * Extracts the text between two section headers (case-insensitive).
     * If {@code endHeader} is null, reads to the end of the string.
     */
    private static String extractBlock(String text, String startHeader, String endHeader) {
        String upper = text.toUpperCase();
        int start = upper.indexOf(startHeader + ":");
        if (start < 0) return null;
        start += startHeader.length() + 1;

        int end = text.length();
        if (endHeader != null) {
            int candidate = upper.indexOf(endHeader + ":", start);
            if (candidate > start) end = candidate;
        }
        return text.substring(start, end).strip();
    }
}
