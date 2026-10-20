package com.wgblackmon.db;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

/**
 * One-time ingestion of a CMS Local Coverage Determination (LCD) text file into
 * the {@code coverage_policy_chunks} table, with Ollama-generated embeddings.
 *
 * <p>Before running:
 * <ol>
 *   <li>Obtain the LCD as plain text and save to {@code data/lcd-L38467.txt}.
 *   <li>Ensure Ollama is running with {@code nomic-embed-text} pulled.
 *   <li>Ensure the schema has been applied ({@code ./run.sh com.wgblackmon.db.SchemaMain}).
 * </ol>
 *
 * <p>Run via: {@code ./run.sh com.wgblackmon.db.LcdIngestMain}
 *
 * <p>Safe to re-run: clears existing rows for the same source_document before inserting.
 *
 * <p>Chunking/embedding/insert logic is shared with {@link AetnaIngestMain} via
 * {@link PolicyIngestSupport}.
 *
 * @author VGPS
 * @version 1.0
 * @since 2026-10-19
 */
public class LcdIngestMain {

    private static final String SOURCE_DOCUMENT = "CMS LCD L38467";
    private static final String SOURCE_URL = "https://www.cms.gov/medicare-coverage-database/view/lcd.aspx?lcdid=38467";
    private static final int TARGET_CHUNK_WORDS = 400;
    private static final int MIN_CHUNK_WORDS    = 30;

    public static void main(String[] args) throws Exception {
        Path lcdFile = Path.of("data/lcd-L38467.txt");
        if (!Files.exists(lcdFile)) {
            System.err.println("ERROR: " + lcdFile.toAbsolutePath() + " not found.");
            System.err.println("Download the LCD text from the CMS Coverage Database and save it there.");
            System.exit(1);
        }

        String text = Files.readString(lcdFile);
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
