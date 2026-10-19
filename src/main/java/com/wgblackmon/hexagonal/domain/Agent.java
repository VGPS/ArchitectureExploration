package com.wgblackmon.hexagonal.domain;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The orchestration loop: call the model, execute any requested tools, append the
 * results, repeat until the model stops asking for tools. Identical shape to Track A's
 * {@code harness.Agent}, but built only against {@link ModelClient} and {@link Tool} —
 * no Anthropic SDK, no MCP SDK, no Spring import anywhere in this class.
 *
 * Uses the JDK's {@code System.Logger} rather than SLF4J for its debug tracing, per the
 * domain-purity rule: this module may depend on nothing beyond the JDK, and a logging
 * framework is an infrastructure dependency like any other.
 *
 * @author  Bill Blackmon
 * @version 1.0
 * @since   2026-10-18
 * @updated 2026-10-18
 */
public class Agent {

    private static final System.Logger LOG = System.getLogger(Agent.class.getName());

    private final ModelClient modelClient;
    private final String systemPrompt;
    private final List<Tool> tools;
    private final Map<String, Tool> toolsByName;
    private final List<Message> history = new ArrayList<>();

    public Agent(ModelClient modelClient, String systemPrompt, List<Tool> tools) {
        LOG.log(Level.DEBUG, "<init>() | systemPrompt={0}, tools={1}", systemPrompt, tools);

        this.modelClient = modelClient;
        this.systemPrompt = systemPrompt;
        this.tools = tools;
        this.toolsByName = tools.stream().collect(Collectors.toMap(Tool::name, t -> t));

        LOG.log(Level.DEBUG, "<init>() | return=void");
    }

    public String run(String userMessage) {
        LOG.log(Level.DEBUG, "run() | userMessage={0}", userMessage);

        history.add(new Message(Role.USER, List.of(new ContentPart.Text(userMessage))));

        while (true) {
            ModelResponse response = modelClient.send(new ModelRequest(systemPrompt, history, tools));
            history.add(new Message(Role.ASSISTANT, response.content()));

            List<ContentPart> toolResults = response.content().stream()
                    .filter(part -> part instanceof ContentPart.ToolUse)
                    .map(part -> (ContentPart) executeTool((ContentPart.ToolUse) part))
                    .collect(Collectors.toList());

            if (toolResults.isEmpty()) {
                String result = response.content().stream()
                        .filter(part -> part instanceof ContentPart.Text)
                        .map(part -> ((ContentPart.Text) part).value())
                        .collect(Collectors.joining("\n"));
                LOG.log(Level.DEBUG, "run() | return={0}", result);
                return result;
            }

            history.add(new Message(Role.USER, toolResults));
        }
    }

    private ContentPart.ToolResult executeTool(ContentPart.ToolUse toolUse) {
        LOG.log(Level.DEBUG, "executeTool() | toolUse={0}", toolUse);

        Tool tool = toolsByName.get(toolUse.name());
        String result;
        boolean isError;
        if (tool == null) {
            result = "Unknown tool: " + toolUse.name();
            isError = true;
        } else {
            try {
                result = tool.execute(toolUse.input());
                isError = false;
            } catch (Exception e) {
                result = "Error: " + e.getMessage();
                isError = true;
            }
        }

        ContentPart.ToolResult toolResult = new ContentPart.ToolResult(toolUse.id(), result, isError);
        LOG.log(Level.DEBUG, "executeTool() | return={0}", toolResult);
        return toolResult;
    }
}
