package com.wgblackmon.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * Factory for the two available data sources.
 *
 * <p>Postgres is configured via environment variables (see .env):
 * {@code POSTGRES_URL}, {@code POSTGRES_USER}, {@code DB_PASSWORD}
 * ({@code DB_PASSWORD} matches the name used in the AIHealthcare sibling project).
 * To use vector columns, run {@code CREATE EXTENSION IF NOT EXISTS vector;} once in psql
 * before creating any tables with {@code vector} type columns.
 *
 * <p>H2 is in-memory; each unique name gets its own isolated database that
 * lives for the JVM's lifetime ({@code DB_CLOSE_DELAY=-1}).
 */
public final class DataSources {

    private static final String DEFAULT_POSTGRES_URL =
            "jdbc:postgresql://localhost:5432/architecture_exploration";

    private DataSources() {}

    /**
     * Returns a pooled Postgres {@link DataSource} configured from environment variables.
     */
    public static DataSource postgres() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(env("POSTGRES_URL", DEFAULT_POSTGRES_URL));
        config.setUsername(env("POSTGRES_USER", "postgres"));
        config.setPassword(env("DB_PASSWORD", ""));
        config.setMaximumPoolSize(5);
        return new HikariDataSource(config);
    }

    /**
     * Returns an in-memory H2 {@link DataSource} with the given logical name.
     * Passing the same name twice returns connections to the same database.
     */
    public static DataSource h2InMemory(String name) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(5);
        return new HikariDataSource(config);
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
