package com.wgblackmon.hexagonal.adapter.fhir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgblackmon.hexagonal.domain.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link Tool} adapter that retrieves a patient record from a FHIR R4 server.
 *
 * <p>Defaults to the HAPI FHIR public test server ({@code https://hapi.fhir.org/baseR4}),
 * which requires no authentication. NHANES patient IDs (e.g. {@code nhanes-120300}) have
 * real vital sign measurements but often sparse condition and medication records — the agent
 * should use the clinical notes field to supplement incomplete FHIR records.
 *
 * <p>Queries four FHIR R4 resource types per patient: Patient (demographics), Condition,
 * MedicationRequest, and Observation. Returns structured plain text in the same format
 * as {@link com.wgblackmon.hexagonal.adapter.db.GetPatientRecordTool}.
 *
 * @author  VGPS
 * @version 1.0
 * @since   2026-10-19
 * @updated 2026-10-19
 */
public class FhirPatientTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FhirPatientTool.class);
    private static final String DEFAULT_BASE_URL = "https://hapi.fhir.org/baseR4";

    private static final Map<String, Object> INPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "fhirPatientId", Map.of(
                            "type", "string",
                            "description", "FHIR patient resource ID (e.g. 'nhanes-120300'). "
                                    + "Do not include the server base URL — just the resource ID.")),
            "required", List.of("fhirPatientId"));

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper json;

    public FhirPatientTool() {
        this(DEFAULT_BASE_URL);
    }

    public FhirPatientTool(String baseUrl) {
        log.debug("<init>() | baseUrl={}", baseUrl);
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.json = new ObjectMapper();
    }

    @Override
    public String name() {
        log.debug("name() | (no args)");
        String result = "get_fhir_patient_record";
        log.debug("name() | return={}", result);
        return result;
    }

    @Override
    public String description() {
        log.debug("description() | (no args)");
        String result = "Retrieve a patient's clinical record from a FHIR R4 server (HAPI FHIR public "
                + "test server). Returns demographics, conditions, active medications, and vital sign "
                + "observations including BMI. Use this tool when the patient is identified by a FHIR "
                + "resource ID such as 'nhanes-120300'. FHIR records may be incomplete — supplement "
                + "with clinical notes provided in the request.";
        log.debug("description() | return={}", result);
        return result;
    }

    @Override
    public Map<String, Object> inputSchema() {
        log.debug("inputSchema() | (no args)");
        log.debug("inputSchema() | return={}", INPUT_SCHEMA);
        return INPUT_SCHEMA;
    }

    @Override
    public String execute(Map<String, Object> input) throws Exception {
        log.debug("execute() | input={}", input);
        String patientId = String.valueOf(input.getOrDefault("fhirPatientId", "")).strip();
        if (patientId.isEmpty()) {
            return "ERROR: fhirPatientId is required.";
        }
        String result = fetchPatientSummary(patientId);
        log.debug("execute() | return (length={})", result.length());
        return result;
    }

    // --- FHIR fetch and format ----------------------------------------------------

    // LOINC codes for bariatric PA key vitals
    private static final String VITALS_LOINC = "39156-5,29463-7,8302-2,8480-6,8462-4";

    private String fetchPatientSummary(String patientId) throws Exception {
        String encodedId   = URLEncoder.encode(patientId, StandardCharsets.UTF_8);
        JsonNode patient    = fetchResource("/Patient/" + encodedId);
        JsonNode conditions = fetchBundle("/Condition?patient=" + encodedId + "&_count=50");
        JsonNode meds       = fetchBundle("/MedicationRequest?patient=" + encodedId
                + "&status=active&_count=50");
        // Fetch key vitals (BMI, weight, height, BP) explicitly so they are never lost to pagination
        JsonNode keyVitals  = fetchBundle("/Observation?patient=" + encodedId
                + "&code=" + VITALS_LOINC + "&_count=20");
        JsonNode allObs     = fetchBundle("/Observation?patient=" + encodedId + "&_count=100");

        return buildSummary(patientId, patient, conditions, meds, keyVitals, allObs);
    }

    private JsonNode fetchResource(String path) throws Exception {
        String url = baseUrl + path + (path.contains("?") ? "&" : "?") + "_format=json";
        log.debug("fetchResource() | url={}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/fhir+json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("fetchResource() | status={} path={}", response.statusCode(), path);

        if (response.statusCode() == 404) {
            return json.createObjectNode();
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException("FHIR server returned HTTP " + response.statusCode()
                    + " for " + url);
        }
        return json.readTree(response.body());
    }

    private JsonNode fetchBundle(String path) throws Exception {
        return fetchResource(path);
    }

    private String buildSummary(String patientId, JsonNode patient,
                                JsonNode conditions, JsonNode meds,
                                JsonNode keyVitals, JsonNode allObs) {
        StringBuilder sb = new StringBuilder();
        sb.append("PATIENT RECORD (FHIR R4 — ").append(baseUrl).append(")\n");
        sb.append("=================================================\n");
        sb.append("Patient ID  : ").append(patientId).append("\n");

        if (patient != null && !patient.isEmpty()) {
            sb.append("Name        : ").append(extractName(patient)).append("\n");
            sb.append("Birth Date  : ").append(patient.path("birthDate").asText("unknown")).append("\n");
            sb.append("Gender      : ").append(patient.path("gender").asText("unknown")).append("\n");
        } else {
            sb.append("(Patient resource not found — verify patient ID)\n");
        }

        // Key vitals (BMI, weight, height, BP) — queried explicitly so they are never lost to pagination
        sb.append("\nKEY VITALS (BMI / weight / height / blood pressure):\n");
        List<String> vitalList = extractEntries(keyVitals, FhirPatientTool::describeObservation);
        if (vitalList.isEmpty()) {
            sb.append("  (not found in FHIR record — supplement with clinical notes)\n");
        } else {
            vitalList.forEach(v -> sb.append("  - ").append(v).append("\n"));
        }

        sb.append("\nACTIVE CONDITIONS:\n");
        List<String> condList = extractEntries(conditions, FhirPatientTool::describeCondition);
        if (condList.isEmpty()) {
            sb.append("  (none documented in FHIR record — supplement with clinical notes)\n");
        } else {
            condList.forEach(c -> sb.append("  - ").append(c).append("\n"));
        }

        sb.append("\nACTIVE MEDICATIONS:\n");
        List<String> medList = extractEntries(meds, FhirPatientTool::describeMedication);
        if (medList.isEmpty()) {
            sb.append("  (none documented in FHIR record — supplement with clinical notes)\n");
        } else {
            medList.forEach(m -> sb.append("  - ").append(m).append("\n"));
        }

        sb.append("\nALL OBSERVATIONS (up to 30 shown):\n");
        List<String> obsList = extractEntries(allObs, FhirPatientTool::describeObservation);
        if (obsList.isEmpty()) {
            sb.append("  (none documented in FHIR record)\n");
        } else {
            obsList.stream().limit(30).forEach(o -> sb.append("  - ").append(o).append("\n"));
        }

        return sb.toString();
    }

    // --- FHIR resource extractors -------------------------------------------------

    private static String extractName(JsonNode patient) {
        JsonNode names = patient.path("name");
        if (names.isArray() && !names.isEmpty()) {
            JsonNode n = names.get(0);
            List<String> given = new ArrayList<>();
            n.path("given").forEach(g -> given.add(g.asText()));
            String family = n.path("family").asText("");
            return (String.join(" ", given) + " " + family).strip();
        }
        return "unknown";
    }

    private static String describeCondition(JsonNode entry) {
        JsonNode resource = entry.path("resource");
        String display    = firstCodingDisplay(resource.path("code"));
        String icd        = extractIcdCode(resource.path("code"));
        return icd.isEmpty() ? display : display + " [" + icd + "]";
    }

    private static String describeMedication(JsonNode entry) {
        JsonNode resource = entry.path("resource");
        JsonNode concept  = resource.path("medicationCodeableConcept");
        if (!concept.isMissingNode()) return firstCodingDisplay(concept);
        return resource.path("medicationReference").path("display").asText("unknown medication");
    }

    private static String describeObservation(JsonNode entry) {
        JsonNode r    = entry.path("resource");
        String code   = firstCodingDisplay(r.path("code"));
        String date   = r.path("effectiveDateTime").asText(
                r.path("effectivePeriod").path("start").asText("no-date"));
        String value  = extractObsValue(r);
        return date + " | " + code + ": " + value;
    }

    private static String firstCodingDisplay(JsonNode codeableConcept) {
        JsonNode coding = codeableConcept.path("coding");
        if (coding.isArray() && !coding.isEmpty()) {
            String display = coding.get(0).path("display").asText("");
            if (!display.isEmpty()) return display;
            return coding.get(0).path("code").asText("unknown");
        }
        String text = codeableConcept.path("text").asText("");
        return text.isEmpty() ? "unknown" : text;
    }

    private static String extractIcdCode(JsonNode codeableConcept) {
        JsonNode coding = codeableConcept.path("coding");
        if (coding.isArray()) {
            for (JsonNode c : coding) {
                if (c.path("system").asText("").contains("icd")) {
                    return c.path("code").asText("");
                }
            }
        }
        return "";
    }

    private static String extractObsValue(JsonNode observation) {
        JsonNode qty = observation.path("valueQuantity");
        if (!qty.isMissingNode()) {
            return String.format("%.2f %s",
                    qty.path("value").asDouble(0),
                    qty.path("unit").asText(qty.path("code").asText(""))).strip();
        }
        JsonNode code = observation.path("valueCodeableConcept");
        if (!code.isMissingNode()) return firstCodingDisplay(code);
        String str = observation.path("valueString").asText("");
        return str.isEmpty() ? "(no value)" : str;
    }

    @FunctionalInterface
    private interface EntryMapper {
        String map(JsonNode entry) throws Exception;
    }

    private List<String> extractEntries(JsonNode bundle, EntryMapper mapper) {
        List<String> result = new ArrayList<>();
        if (bundle == null || bundle.isMissingNode()) return result;
        JsonNode entries = bundle.path("entry");
        if (!entries.isArray()) return result;
        for (JsonNode entry : entries) {
            try {
                String s = mapper.map(entry);
                if (s != null && !s.isBlank() && !s.equals("unknown")) {
                    result.add(s);
                }
            } catch (Exception e) {
                log.debug("extractEntries() | skipping entry: {}", e.getMessage());
            }
        }
        return result;
    }
}
