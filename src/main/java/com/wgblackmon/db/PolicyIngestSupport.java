package com.wgblackmon.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shared chunking + embedding + insert logic for one-time coverage-policy ingestion
 * scripts ({@link LcdIngestMain}, {@link AetnaIngestMain}). Each caller supplies its own
 * source document name/URL and chunk-size thresholds; this class has no document-specific
 * state.
 *
 * @author VGPS
 * @version 1.0
 * @since 2026-10-19
 */
final class PolicyIngestSupport {

    private PolicyIngestSupport() {}

    record Chunk(String sectionTitle, String text, int index) {}

    // --- Chunking -----------------------------------------------------------------

    static List<Chunk> chunk(String text, int targetChunkWords, int minChunkWords) {
        // Split on blank lines to get raw paragraphs
        String[] paragraphs = text.split("\\n\\s*\\n");

        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentSection = "General";
        int chunkIndex = 0;

        for (String para : paragraphs) {
            String trimmed = para.strip();
            if (trimmed.isEmpty()) continue;

            // Detect section headings: all-caps lines, or lines ending with a colon, short lines
            if (isHeading(trimmed)) {
                if (wordCount(current.toString()) >= minChunkWords) {
                    chunks.add(new Chunk(currentSection, current.toString().strip(), chunkIndex++));
                    current.setLength(0);
                }
                currentSection = trimmed.replaceAll("[:\\n]+$", "").strip();
                continue;
            }

            current.append(trimmed).append("\n\n");

            if (wordCount(current.toString()) >= targetChunkWords) {
                chunks.add(new Chunk(currentSection, current.toString().strip(), chunkIndex++));
                current.setLength(0);
            }
        }

        if (wordCount(current.toString()) >= minChunkWords) {
            chunks.add(new Chunk(currentSection, current.toString().strip(), chunkIndex));
        }

        return chunks;
    }

    private static boolean isHeading(String line) {
        // Single-line, no period at end, fewer than 12 words, mostly uppercase or title-case
        if (line.contains("\n")) return false;
        if (line.endsWith(".")) return false;
        int words = wordCount(line);
        if (words > 12) return false;
        long upperWords = Arrays.stream(line.split("\\s+"))
                .filter(w -> w.length() > 1 && Character.isUpperCase(w.charAt(0)))
                .count();
        return upperWords >= words * 0.6;
    }

    private static int wordCount(String s) {
        if (s.isBlank()) return 0;
        return s.trim().split("\\s+").length;
    }

    // --- Database -----------------------------------------------------------------

    static void clearExisting(Connection conn, String sourceDocument) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM coverage_policy_chunks WHERE source_document = ?")) {
            ps.setString(1, sourceDocument);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                System.out.printf("Cleared %d existing rows for %s%n", deleted, sourceDocument);
            }
        }
    }

    static void insert(Connection conn, OllamaEmbedder embedder, List<Chunk> chunks,
                       String sourceDocument, String sourceUrl) throws Exception {
        String sql = """
                INSERT INTO coverage_policy_chunks
                    (source_document, source_url, section_title, chunk_text, chunk_index, embedding)
                VALUES (?, ?, ?, ?, ?, ?::vector)
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                System.out.printf("  [%d/%d] Embedding: %s (%.0f words)%n",
                        i + 1, chunks.size(), c.sectionTitle(), (double) wordCount(c.text()));

                float[] embedding = embedder.embed(c.text());
                String vectorLiteral = OllamaEmbedder.toVectorLiteral(embedding);

                ps.setString(1, sourceDocument);
                ps.setString(2, sourceUrl);
                ps.setString(3, c.sectionTitle());
                ps.setString(4, c.text());
                ps.setInt(5, c.index());
                ps.setString(6, vectorLiteral);
                ps.executeUpdate();
            }
        }
    }
}
