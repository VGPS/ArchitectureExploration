package com.wgblackmon.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Parses Synthea FHIR R4 JSON bundles from {@code synthea-output/fhir/} and loads
 * patient demographics, conditions, medications, and observations into Postgres.
 *
 * <p>Before running:
 * <ol>
 *   <li>Generate output: {@code java -jar tools/synthea-with-dependencies.jar -p 50
 *       --exporter.fhir.export=true --exporter.baseDirectory=synthea-output "Massachusetts"}
 *   <li>Ensure the schema has been applied ({@code ./run.sh com.wgblackmon.db.SchemaMain}).
 * </ol>
 *
 * <p>Run via: {@code ./run.sh com.wgblackmon.db.SyntheaIngestMain}
 *
 * <p>Idempotent via INSERT ... ON CONFLICT DO NOTHING — safe to re-run.
 *
 * @author VGPS
 * @version 1.0
 * @since 2026-10-19
 */
public class SyntheaIngestMain {

    // LOINC codes we care about for BMI calculation
    private static final String LOINC_BODY_WEIGHT = "29463-7";
    private static final String LOINC_BODY_HEIGHT = "8302-2";
    private static final String LOINC_BMI         = "39156-5";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Path fhirDir = Path.of("synthea-output/fhir");
        if (!Files.exists(fhirDir)) {
            System.err.println("ERROR: " + fhirDir.toAbsolutePath() + " not found.");
            System.err.println("Run Synthea first: java -jar tools/synthea-with-dependencies.jar ...");
            System.exit(1);
        }

        DataSource ds = DataSources.postgres();
        int patientCount = 0;

        try (Stream<Path> files = Files.list(fhirDir)) {
            List<Path> bundles = files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().startsWith("practitioner"))
                    .filter(p -> !p.getFileName().toString().startsWith("hospital"))
                    .toList();

            System.out.printf("Found %d patient bundles in %s%n", bundles.size(), fhirDir);

            for (Path bundle : bundles) {
                try {
                    boolean loaded = processBundle(bundle, ds);
                    if (loaded) patientCount++;
                } catch (Exception e) {
                    System.err.println("WARN: Skipping " + bundle.getFileName() + ": " + e.getMessage());
                }
            }
        }

        System.out.printf("Done. Loaded %d patients into Postgres.%n", patientCount);
    }

    // --- Bundle processing --------------------------------------------------------

    private static boolean processBundle(Path bundlePath, DataSource ds) throws Exception {
        JsonNode bundle = MAPPER.readTree(bundlePath.toFile());
        JsonNode entries = bundle.get("entry");
        if (entries == null || !entries.isArray()) return false;

        // First pass: find the Patient resource
        JsonNode patientResource = null;
        for (JsonNode entry : entries) {
            JsonNode resource = entry.get("resource");
            if (resource != null && "Patient".equals(resource.path("resourceType").asText())) {
                patientResource = resource;
                break;
            }
        }
        if (patientResource == null) return false;

        String patientId = patientResource.path("id").asText();
        if (patientId.isBlank()) return false;

        // Collect observations to compute BMI
        Double weightKg = null, heightCm = null, bmi = null;
        for (JsonNode entry : entries) {
            JsonNode resource = entry.get("resource");
            if (resource == null || !"Observation".equals(resource.path("resourceType").asText())) continue;
            String loincCode = extractLoincCode(resource);
            double value = resource.path("valueQuantity").path("value").asDouble(Double.NaN);
            if (Double.isNaN(value)) continue;

            if (LOINC_BMI.equals(loincCode))           bmi      = value;
            else if (LOINC_BODY_WEIGHT.equals(loincCode)) weightKg = value;
            else if (LOINC_BODY_HEIGHT.equals(loincCode)) heightCm = value;
        }

        // Calculate BMI if not directly available
        if (bmi == null && weightKg != null && heightCm != null && heightCm > 0) {
            double heightM = heightCm / 100.0;
            bmi = weightKg / (heightM * heightM);
        }

        try (Connection conn = ds.getConnection()) {
            boolean isNewPatient = insertPatient(conn, patientId, patientResource, weightKg, heightCm, bmi);
            if (!isNewPatient) {
                // Patient already ingested — skip child rows too, since they have no unique
                // constraint of their own and would duplicate on every re-run otherwise.
                return false;
            }

            for (JsonNode entry : entries) {
                JsonNode resource = entry.get("resource");
                if (resource == null) continue;
                String type = resource.path("resourceType").asText();
                switch (type) {
                    case "Condition"          -> insertCondition(conn, patientId, resource);
                    case "MedicationRequest"  -> insertMedication(conn, patientId, resource);
                    case "Observation"        -> insertObservation(conn, patientId, resource);
                }
            }
        }

        return true;
    }

    // --- Inserts ------------------------------------------------------------------

    /**
     * Inserts the patient row. Returns {@code false} without touching child tables when
     * the patient already exists (ON CONFLICT DO NOTHING), so callers can skip re-inserting
     * conditions/medications/observations, which have no unique constraint to dedupe on.
     */
    private static boolean insertPatient(Connection conn, String patientId, JsonNode patient,
                                      Double weightKg, Double heightCm, Double bmi) throws Exception {
        JsonNode nameNode = patient.path("name").path(0);
        String firstName = nameNode.path("given").path(0).asText("");
        String lastName  = nameNode.path("family").asText("");
        String birthDate = patient.path("birthDate").asText(null);
        String gender    = patient.path("gender").asText(null);

        String sql = """
                INSERT INTO patients (patient_id, first_name, last_name, birth_date, gender, weight_kg, height_cm, bmi)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (patient_id) DO NOTHING
                """;
        int inserted;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, patientId);
            ps.setString(2, firstName);
            ps.setString(3, lastName);
            ps.setDate(4, birthDate != null ? Date.valueOf(birthDate) : null);
            ps.setString(5, gender);
            setNullableDecimal(ps, 6, weightKg);
            setNullableDecimal(ps, 7, heightCm);
            setNullableDecimal(ps, 8, bmi);
            inserted = ps.executeUpdate();
        }
        if (inserted > 0) {
            System.out.printf("  Patient: %s %s (id=%s, BMI=%.1f)%n",
                    firstName, lastName, patientId, bmi != null ? bmi : 0.0);
        } else {
            System.out.printf("  Patient %s already loaded — skipping conditions/medications/observations%n",
                    patientId);
        }
        return inserted > 0;
    }

    private static void insertCondition(Connection conn, String patientId, JsonNode condition) throws Exception {
        String code        = condition.path("code").path("coding").path(0).path("code").asText("");
        String description = condition.path("code").path("text").asText(
                condition.path("code").path("coding").path(0).path("display").asText(""));
        String onsetDate   = condition.path("onsetDateTime").asText(null);
        if (onsetDate != null && onsetDate.length() > 10) onsetDate = onsetDate.substring(0, 10);
        String status = condition.path("clinicalStatus").path("coding").path(0).path("code").asText("active");

        if (code.isBlank()) return;

        String sql = """
                INSERT INTO patient_conditions (patient_id, icd10_code, description, onset_date, status)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, patientId);
            ps.setString(2, code);
            ps.setString(3, description);
            ps.setDate(4, onsetDate != null ? Date.valueOf(onsetDate) : null);
            ps.setString(5, status);
            ps.executeUpdate();
        }
    }

    private static void insertMedication(Connection conn, String patientId, JsonNode med) throws Exception {
        String name = med.path("medicationCodeableConcept").path("text").asText(
                med.path("medicationCodeableConcept").path("coding").path(0).path("display").asText(""));
        String rxnorm = med.path("medicationCodeableConcept").path("coding").path(0).path("code").asText("");
        String status = med.path("status").asText("active");

        if (name.isBlank()) return;

        String sql = """
                INSERT INTO patient_medications (patient_id, medication_name, rxnorm_code, status)
                VALUES (?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, patientId);
            ps.setString(2, name);
            ps.setString(3, rxnorm.isBlank() ? null : rxnorm);
            ps.setString(4, status);
            ps.executeUpdate();
        }
    }

    private static void insertObservation(Connection conn, String patientId, JsonNode obs) throws Exception {
        String loincCode    = extractLoincCode(obs);
        String description  = obs.path("code").path("text").asText(
                obs.path("code").path("coding").path(0).path("display").asText(""));
        JsonNode valueQ     = obs.path("valueQuantity");
        String value        = valueQ.path("value").asText(obs.path("valueString").asText(""));
        String unit         = valueQ.path("unit").asText("");
        String dateStr      = obs.path("effectiveDateTime").asText(null);
        if (dateStr != null && dateStr.length() > 10) dateStr = dateStr.substring(0, 10);

        if (loincCode.isBlank() || value.isBlank()) return;

        String sql = """
                INSERT INTO patient_observations (patient_id, loinc_code, description, value, unit, observed_date)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, patientId);
            ps.setString(2, loincCode);
            ps.setString(3, description);
            ps.setString(4, value);
            ps.setString(5, unit);
            ps.setDate(6, dateStr != null ? Date.valueOf(dateStr) : null);
            ps.executeUpdate();
        }
    }

    // --- Helpers ------------------------------------------------------------------

    private static String extractLoincCode(JsonNode resource) {
        for (JsonNode coding : resource.path("code").path("coding")) {
            String system = coding.path("system").asText("");
            if (system.contains("loinc")) {
                return coding.path("code").asText("");
            }
        }
        return "";
    }

    private static void setNullableDecimal(PreparedStatement ps, int idx, Double value) throws Exception {
        if (value == null) {
            ps.setNull(idx, java.sql.Types.DECIMAL);
        } else {
            ps.setBigDecimal(idx, BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP));
        }
    }
}
