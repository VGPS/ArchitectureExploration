package com.wgblackmon.hexagonal.adapter.db;

import com.wgblackmon.db.OllamaEmbedder;
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
 * {@link Tool} adapter that performs a semantic similarity search over payer coverage
 * policies stored as pgvector embeddings in the {@code coverage_policy_chunks} table.
 *
 * <p>Execution flow:
 * <ol>
 *   <li>Embed the incoming query string via {@link OllamaEmbedder} (nomic-embed-text, 768d).
 *   <li>Run pgvector cosine-similarity search: {@code embedding <=> ?::vector}.
 *   <li>Return the top-5 chunks concatenated with section titles and similarity scores.
 * </ol>
 *
 * <p>The embedding literal is inlined into the SQL as a vector string rather than a
 * parameterised {@code PGvector} object. This is safe because the value comes from
 * Ollama, not from user input, and avoids a JDBC type-registration dependency.
 *
 * @author  Bill Blackmon
 * @version 2.0
 * @since   2026-10-19
 * @updated 2026-10-19
 */
public class SearchCoveragePolicyTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SearchCoveragePolicyTool.class);
    private static final int TOP_K = 5;

    private static final Map<String, Object> INPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "query", Map.of(
                            "type", "string",
                            "description", "Clinical question or procedure description to search against "
                                    + "coverage policies (e.g. 'bariatric surgery BMI criteria')")),
            "required", List.of("query"));

    private final DataSource dataSource;
    private final OllamaEmbedder embedder;

    public SearchCoveragePolicyTool(DataSource dataSource, OllamaEmbedder embedder) {
        this.dataSource = dataSource;
        this.embedder   = embedder;
    }

    @Override
    public String name() {
        log.debug("name() | (no args)");
        String result = "search_coverage_policy";
        log.debug("name() | return={}", result);
        return result;
    }

    @Override
    public String description() {
        log.debug("description() | (no args)");
        String result = "Search payer coverage policies and CMS coverage determinations using "
                + "semantic similarity. Returns the most relevant policy sections for the given "
                + "procedure or clinical question.";
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
        String query = String.valueOf(input.getOrDefault("query", ""));
        String result = searchVector(query);
        log.debug("execute() | return (length={})", result.length());
        return result;
    }

    private String searchVector(String query) throws Exception {
        float[] queryEmbedding = embedder.embed(query);
        String vectorLiteral  = OllamaEmbedder.toVectorLiteral(queryEmbedding);

        // Inline the vector literal — it comes from Ollama (controlled float values), not user input.
        String sql = """
                SELECT section_title,
                       chunk_text,
                       source_document,
                       round((1 - (embedding <=> '%s'::vector))::numeric, 4) AS similarity
                FROM coverage_policy_chunks
                ORDER BY embedding <=> '%s'::vector
                LIMIT %d
                """.formatted(vectorLiteral, vectorLiteral, TOP_K);

        List<String> sections = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                sections.add("""
                        --- %s (source: %s, similarity: %s) ---
                        %s
                        """.formatted(
                        rs.getString("section_title"),
                        rs.getString("source_document"),
                        rs.getString("similarity"),
                        rs.getString("chunk_text")));
            }
        }

        if (sections.isEmpty()) {
            return "No coverage policy chunks found. Ensure LcdIngestMain has been run.";
        }

        return "COVERAGE POLICY SEARCH RESULTS\n"
                + "================================\n"
                + "Query: \"" + query + "\"\n\n"
                + String.join("\n", sections);
    }
}
