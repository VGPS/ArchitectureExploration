package com.wgblackmon.hexagonal.domain;

import java.util.Map;

/**
 * One piece of a {@link Message}: plain text, a tool-call request from the model, or a
 * tool-call result being sent back to it. A single sealed family covers both directions —
 * {@code Agent} only ever produces {@code ToolUse} by reading it out of a
 * {@code ModelResponse}, and only ever produces {@code ToolResult} when sending it back —
 * so there's no separate request/response hierarchy to keep in sync.
 *
 * @author  Bill Blackmon
 * @version 1.0
 * @since   2026-10-18
 * @updated 2026-10-18
 */
public sealed interface ContentPart {

    record Text(String value) implements ContentPart {
    }

    record ToolUse(String id, String name, Map<String, Object> input) implements ContentPart {
    }

    record ToolResult(String toolUseId, String content, boolean isError) implements ContentPart {
    }
}
