package com.wgblackmon.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

/**
 * One-time setup: creates the {@code architecture_exploration} database if it does not
 * exist, then applies {@code /db/schema.sql} against it. Safe to re-run — every
 * statement in the schema uses {@code IF NOT EXISTS}.
 *
 * <p>Run via: {@code ./run.sh com.wgblackmon.db.SchemaMain}
 */
public class SchemaMain {

    public static void main(String[] args) throws Exception {
        String jdbcUrl  = env("POSTGRES_URL",      "jdbc:postgresql://localhost:5432/architecture_exploration");
        String user     = env("POSTGRES_USER",     "admin");
        String password = env("DB_PASSWORD", "");

        // Database and pgvector extension are created once by the postgres superuser
        // (see CLAUDE.md — run psql as postgres to create the DB and install pgvector).
        // SchemaMain only needs to apply the table DDL as the application user.
        System.out.println("Applying schema to: " + jdbcUrl + " as " + user);
        applySchema(jdbcUrl, user, password);
        System.out.println("Done. Tables and indexes are ready.");
    }

    private static void applySchema(String jdbcUrl, String user, String password) throws Exception {
        InputStream stream = SchemaMain.class.getResourceAsStream("/db/schema.sql");
        if (stream == null) {
            throw new IllegalStateException("Cannot find /db/schema.sql on classpath");
        }
        String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(1);

        try (HikariDataSource ds = new HikariDataSource(cfg);
             Connection conn = ds.getConnection();
             Statement  stmt = conn.createStatement()) {

            for (String statement : sql.split(";")) {
                String trimmed = statement.strip();
                // Skip blanks and comment-only blocks
                if (trimmed.isEmpty()) continue;
                String noComments = trimmed.replaceAll("(?m)^--.*$", "").strip();
                if (noComments.isEmpty()) continue;

                System.out.println("  " + firstLine(trimmed));
                stmt.execute(trimmed);
            }
        }
    }

    private static String firstLine(String s) {
        for (String line : s.split("\n")) {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("--")) return t;
        }
        return s;
    }

    private static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }
}
