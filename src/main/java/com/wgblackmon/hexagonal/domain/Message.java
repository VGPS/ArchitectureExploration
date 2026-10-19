package com.wgblackmon.hexagonal.domain;

import java.util.List;

/**
 * One turn in the conversation history.
 *
 * @author  Bill Blackmon
 * @version 1.0
 * @since   2026-10-18
 * @updated 2026-10-18
 */
public record Message(Role role, List<ContentPart> parts) {
}
