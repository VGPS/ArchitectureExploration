package com.wgblackmon.hexagonal.domain;

import java.util.List;

/**
 * Everything a {@link ModelClient} needs for one call: the system prompt, the full
 * conversation history so far, and the tools currently on offer.
 *
 * @author  Bill Blackmon
 * @version 1.0
 * @since   2026-10-18
 * @updated 2026-10-18
 */
public record ModelRequest(String systemPrompt, List<Message> messages, List<Tool> tools) {
}
