package com.wgblackmon.hexagonal.domain;

import java.util.List;

/**
 * What the model produced for one turn — a list of {@link ContentPart}, normally some
 * mix of {@code Text} and {@code ToolUse}.
 *
 * @author  Bill Blackmon
 * @version 1.0
 * @since   2026-10-18
 * @updated 2026-10-18
 */
public record ModelResponse(List<ContentPart> content) {
}
