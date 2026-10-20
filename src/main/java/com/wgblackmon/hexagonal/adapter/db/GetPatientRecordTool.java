package com.wgblackmon.hexagonal.adapter.db;

import com.wgblackmon.hexagonal.domain.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link Tool} adapter that retrieves a patient's full clinical record from Postgres.
 *
 * <p>Queries four tables in two passes:
 * <ol>
 *   <li>Demographics, active conditions, active medications via a single aggregating query.
 *   <li>Most-recent 20 observations (vitals, labs) ordered by date descending.
 * </ol>
 * The result is returned as structured plain text so the agent can reason over it without
 * any JSON parsing step.
 *
 * @author  Bill Blackmon
 * @version 2.0
 * @since   2026-10-19
 * @updated 2026-10-19
 */
public class GetPatientRecordTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GetPatientRecordTool.class);

    private static final Map<String, Object> INPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "patientId", Map.of(
                            "type", "string",
                            "description", "The unique patient identifier (e.g. demo-001 or a UUID)")),
            "required", List.of("patientId"));

    private final DataSource dataSource;

    public GetPatientRecordTool(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String name() {
        log.debug("name() | (no args)");
        String result = "get_patient_record";
        log.debug("name() | return={}", result);
        return result;
    }

    @Override
    public String description() {
        log.debug("description() | (no args)");
        String result = "Retrieve a patient's demographics, active diagnoses, current medications, "
                + "and recent lab results and vitals from the medical record system.";
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
        String patientId = String.valueOf(input.getOrDefault("patientId", "UNKNOWN"));
        String result = fetchFromDatabase(patientId);
        log.debug("execute() | return={}", result);
        return result;
    }

    private String fetchFromDatabase(String patientId) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            String header = queryHeader(conn, patientId);
            if (header == null) {
                return "ERROR: No patient found with ID: " + patientId;
            }
            String observations = queryObservations(conn, patientId);
            return header + observations;
        }
    }

    private String queryHeader(Connection conn, String patientId) throws Exception {
        String sql = """
                SELECT p.patient_id,
                       p.first_name,
                       p.last_name,
                       p.birth_date,
                       p.gender,
                       p.bmi,
                       p.weight_kg,
                       p.height_cm,
                       string_agg(DISTINCT pc.icd10_code || '  ' || COALESCE(pc.description,'')
                           || ' (onset: ' || COALESCE(pc.onset_date::text,'unknown') || ')',
                           E'\\n' ORDER BY pc.icd10_code || '  ' || COALESCE(pc.description,'')
                           || ' (onset: ' || COALESCE(pc.onset_date::text,'unknown') || ')') AS conditions,
                       string_agg(DISTINCT pm.medication_name, E'\\n'
                           ORDER BY pm.medication_name) AS medications
                FROM patients p
                LEFT JOIN patient_conditions  pc ON pc.patient_id = p.patient_id AND pc.status = 'active'
                LEFT JOIN patient_medications pm ON pm.patient_id = p.patient_id AND pm.status = 'active'
                WHERE p.patient_id = ?
                GROUP BY p.patient_id, p.first_name, p.last_name,
                         p.birth_date, p.gender, p.bmi, p.weight_kg, p.height_cm
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, patientId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                String age = ageFrom(rs.getString("birth_date"));
                return """
                        PATIENT RECORD
                        ==============
                        Patient ID : %s
                        Name       : %s %s
                        DOB        : %s  (Age: %s)
                        Gender     : %s
                        BMI        : %s  (Weight: %s kg, Height: %s cm)

                        Active Diagnoses
                        ----------------
                        %s

                        Current Medications
                        -------------------
                        %s

                        """.formatted(
                        rs.getString("patient_id"),
                        rs.getString("first_name"), rs.getString("last_name"),
                        rs.getString("birth_date"), age,
                        rs.getString("gender"),
                        rs.getString("bmi"), rs.getString("weight_kg"), rs.getString("height_cm"),
                        nvl(rs.getString("conditions"), "(none on file)"),
                        nvl(rs.getString("medications"), "(none on file)"));
            }
        }
    }

    private String queryObservations(Connection conn, String patientId) throws Exception {
        String sql = """
                SELECT loinc_code, description, value, unit, observed_date
                FROM patient_observations
                WHERE patient_id = ?
                ORDER BY observed_date DESC
                LIMIT 25
                """;

        List<String> lines = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, patientId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String line = String.format("%-12s  %-45s  %s %s  (%s)",
                            rs.getString("loinc_code"),
                            rs.getString("description"),
                            rs.getString("value"),
                            nvl(rs.getString("unit"), ""),
                            rs.getString("observed_date"));
                    lines.add(line);
                }
            }
        }

        if (lines.isEmpty()) return "Observations / Labs\n-------------------\n(none on file)\n";

        return "Observations / Labs (most recent 25)\n"
                + "--------------------------------------\n"
                + String.join("\n", lines) + "\n";
    }

    private static String ageFrom(String birthDate) {
        if (birthDate == null) return "unknown";
        try {
            int birthYear = Integer.parseInt(birthDate.substring(0, 4));
            return String.valueOf(java.time.Year.now().getValue() - birthYear);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String nvl(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }
}
