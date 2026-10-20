package com.wgblackmon.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Calls the local Ollama REST API to generate text embeddings.
 *
 * <p>Endpoint: {@code POST http://localhost:11434/api/embeddings}
 * Model: {@code nomic-embed-text} (768-dimensional output, matches the
 * {@code vector(768)} column in {@code coverage_policy_chunks}).
 *
 * <p>Requires Ollama to be running and the model to be pulled:
 * {@code ollama pull nomic-embed-text}
 *
 * @author VGPS
 * @version 1.0
 * @since 2026-10-19
 */
public class OllamaEmbedder {

    private static final String DEFAULT_BASE_URL = "http://localhost:11434";
    private static final String MODEL = "nomic-embed-text";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public OllamaEmbedder() {
        this(DEFAULT_BASE_URL);
    }

    public OllamaEmbedder(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Returns a 768-dimensional embedding vector for the given text.
     *
     * @param text the text to embed (chunk or query string)
     * @return float array of length 768
     * @throws RuntimeException if Ollama is unreachable or returns an error
     */
    public float[] embed(String text) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", MODEL);
            body.put("prompt", text);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/embeddings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Ollama returned HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode embeddingNode = root.get("embedding");
            if (embeddingNode == null || !embeddingNode.isArray()) {
                throw new RuntimeException("Unexpected Ollama response: no embedding array");
            }

            float[] result = new float[embeddingNode.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = (float) embeddingNode.get(i).asDouble();
            }
            return result;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call Ollama embeddings API", e);
        }
    }

    /**
     * Formats a float[] embedding as a Postgres-compatible vector literal, e.g. "[0.1,0.2,...]".
     */
    public static String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
