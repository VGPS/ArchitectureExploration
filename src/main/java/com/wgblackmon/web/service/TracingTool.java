package com.wgblackmon.web.service;

import com.wgblackmon.hexagonal.domain.Tool;

import java.util.List;
import java.util.Map;

/**
 * Thin decorator that wraps a domain {@link Tool} and records every invocation into a
 * caller-supplied list. Used by {@link PriorAuthService} to capture the agent's tool-call
 * trace for display in the UI without modifying the domain {@code Agent}.
 *
 * <p>The {@code trace} list is shared across all {@code TracingTool} instances created for
 * a single request, so calls from different tools appear in chronological order.
 */
public class TracingTool implements Tool {

    private final Tool delegate;
    private final List<ToolCallRecord> trace;

    public TracingTool(Tool delegate, List<ToolCallRecord> trace) {
        this.delegate = delegate;
        this.trace = trace;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public Map<String, Object> inputSchema() {
        return delegate.inputSchema();
    }

    @Override
    public String execute(Map<String, Object> input) throws Exception {
        String output = delegate.execute(input);
        trace.add(new ToolCallRecord(name(), input, output));
        return output;
    }
}
