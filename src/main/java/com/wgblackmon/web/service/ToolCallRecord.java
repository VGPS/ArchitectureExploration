package com.wgblackmon.web.service;

import java.util.Map;

/**
 * One captured tool invocation from a prior auth agent run — name, what was passed in,
 * and what came back. Collected by {@link TracingTool} and surfaced in the UI via
 * {@link PriorAuthResult#toolCalls()}.
 */
public record ToolCallRecord(String toolName, Map<String, Object> input, String output) {}
