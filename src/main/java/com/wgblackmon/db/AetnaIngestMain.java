package com.wgblackmon.db;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

/**
 * One-time ingestion of the Aetna Clinical Policy Bulletin 0157 (Bariatric Surgery)
 * into the {@code coverage_policy_chunks} table, with Ollama-generated embeddings.
 *
 * <p>Before running:
 * <ol>
 *   <li>Ensure {@code data/aetna-cpb-0157.txt} exists (fetched from Aetna's website).
 *   <li>Ensure Ollama is running with {@code nomic-embed-text} pulled.
 *   <li>Ensure the schema has been applied ({@code ./run.sh com.wgblackmon.db.SchemaMain}).
 * </ol>
 *
 * <p>Run via: {@code ./run.sh com.wgblackmon.db.AetnaIngestMain}
 *
 * <p>Safe to re-run: clears existing rows for {@code source_document = "Aetna CPB 0157"}
 * before inserting.
 *
 * <p>Chunking/embedding/insert logic is shared with {@link LcdIngestMain} via
 * {@link PolicyIngestSupport}.
 *
 * @author VGPS
 * @version 1.0
 * @since 2026-10-19
 */
public class AetnaIngestMain {

    private static final String SOURCE_DOCUMENT = "Aetna CPB 0157";
    private static final String SOURCE_URL      = "https://www.aetna.com/cpb/medical/data/100_199/0157.html";
    private static final int    TARGET_CHUNK_WORDS = 400;
    private static final int    MIN_CHUNK_WORDS    = 30;

    public static void main(String[] args) throws Exception {
        Path policyFile = Path.of("data/aetna-cpb-0157.txt");
        if (!Files.exists(policyFile)) {
            System.err.println("ERROR: " + policyFile.toAbsolutePath() + " not found.");
            System.exit(1);
        }

        String text = Files.readString(policyFile);
        List<PolicyIngestSupport.Chunk> chunks =
                PolicyIngestSupport.chunk(text, TARGET_CHUNK_WORDS, MIN_CHUNK_WORDS);
        System.out.printf("Chunked into %d pieces (target %d words each)%n", chunks.size(), TARGET_CHUNK_WORDS);

        OllamaEmbedder embedder = new OllamaEmbedder();
        DataSource ds = DataSources.postgres();

        try (Connection conn = ds.getConnection()) {
            PolicyIngestSupport.clearExisting(conn, SOURCE_DOCUMENT);
            PolicyIngestSupport.insert(conn, embedder, chunks, SOURCE_DOCUMENT, SOURCE_URL);
        }

        System.out.printf("Done. Inserted %d policy chunks into coverage_policy_chunks.%n", chunks.size());
    }
}
