package com.wgblackmon.db;

import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * One-shot patch: adds supervised weight loss visits 8–13 to demo-001 (Margaret Thornton)
 * so her program clears both CMS LCD L38467 (6 months ✓) and Aetna CPB 0157 (12 sessions ✓).
 *
 * <p>Safe to re-run: observations have no unique constraint, but running twice would duplicate
 * the rows — run once only.
 *
 * <p>Run via: {@code ./run.sh com.wgblackmon.db.PatchDemo001Main}
 */
public class PatchDemo001Main {

    private static final Object[][] VISITS = {
        {"SUPWT-8",  "Supervised wt loss visit 8  - 121.0 kg - Dr. Kim NP", "121.0", "kg", "2024-09-30"},
        {"SUPWT-9",  "Supervised wt loss visit 9  - 120.5 kg - Dr. Kim NP", "120.5", "kg", "2024-10-28"},
        {"SUPWT-10", "Supervised wt loss visit 10 - 120.1 kg - Dr. Kim NP", "120.1", "kg", "2024-11-25"},
        {"SUPWT-11", "Supervised wt loss visit 11 - 119.8 kg - Dr. Kim NP", "119.8", "kg", "2024-12-23"},
        {"SUPWT-12", "Supervised wt loss visit 12 - 119.5 kg - Dr. Kim NP", "119.5", "kg", "2025-01-20"},
        {"SUPWT-13", "Supervised wt loss visit 13 - 119.3 kg - Dr. Kim NP", "119.3", "kg", "2025-02-17"},
    };

    public static void main(String[] args) throws Exception {
        String sql = """
                INSERT INTO patient_observations
                    (patient_id, loinc_code, description, value, unit, observed_date)
                VALUES ('demo-001', ?, ?, ?, ?, ?)
                """;

        try (Connection conn = DataSources.postgres().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            for (Object[] row : VISITS) {
                ps.setString(1, (String) row[0]);
                ps.setString(2, (String) row[1]);
                ps.setString(3, (String) row[2]);
                ps.setString(4, (String) row[3]);
                ps.setObject(5, java.sql.Date.valueOf((String) row[4]));
                ps.executeUpdate();
                System.out.printf("Inserted %s (%s)%n", row[0], row[4]);
            }
        }

        System.out.println("Done. demo-001 now has 13 supervised weight loss visits.");
    }
}
