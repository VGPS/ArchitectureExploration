package com.wgblackmon.harness;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The whole harness: call model, execute requested tools, append results, repeat until no tool call.
 */
public class Agent {

    private final AnthropicClient client;
    private final String model;
    private final String systemPrompt;
    private final Map<String, Tool> tools;
    private final List<MessageParam> history = new ArrayList<>();

    public Agent(AnthropicClient client, String model, String systemPrompt, List<Tool> tools) {
        this.client = client;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.tools = tools.stream().collect(Collectors.toMap(Tool::name, t -> t));
    }

    public String run(String userMessage) {
        history.add(MessageParam.builder().role(MessageParam.Role.USER).content(userMessage).build());

        while (true) {
            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(16000L)
                    .system(systemPrompt)
                    .thinking(ThinkingConfigDisabled.builder().build())
                    .messages(history);
            tools.values().forEach(t -> params.addTool(toApiTool(t)));

            Message response = client.messages().create(params.build());

            List<ContentBlockParam> assistantTurn = response.content().stream()
                    .map(ContentBlock::toParam)
                    .collect(Collectors.toList());
            history.add(MessageParam.builder().role(MessageParam.Role.ASSISTANT).contentOfBlockParams(assistantTurn).build());

            List<ContentBlockParam> toolResults = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                block.toolUse().ifPresent(toolUse -> toolResults.add(executeTool(toolUse)));
            }

            if (toolResults.isEmpty()) {
                return response.content().stream()
                        .flatMap(b -> b.text().stream())
                        .map(t -> t.text())
                        .collect(Collectors.joining("\n"));
            }

            history.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(toolResults).build());
        }
    }

    private ContentBlockParam executeTool(ToolUseBlock toolUse) {
        Tool tool = tools.get(toolUse.name());
        String result;
        boolean isError = false;
        if (tool == null) {
            result = "Unknown tool: " + toolUse.name();
            isError = true;
        } else {
            try {
                JsonNode input = toolUse._input().convert(JsonNode.class);
                result = tool.execute(input);
            } catch (Exception e) {
                result = "Error: " + e.getMessage();
                isError = true;
            }
        }
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(toolUse.id())
                .content(result)
                .isError(isError)
                .build());
    }

    @SuppressWarnings("unchecked")
    private static com.anthropic.models.messages.Tool toApiTool(Tool tool) {
        Map<String, Object> schema = tool.inputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        List<String> required = (List<String>) schema.getOrDefault("required", List.of());

        com.anthropic.models.messages.Tool.InputSchema.Properties.Builder propsBuilder =
                com.anthropic.models.messages.Tool.InputSchema.Properties.builder();
        properties.forEach((key, value) -> propsBuilder.putAdditionalProperty(key, JsonValue.from(value)));

        return com.anthropic.models.messages.Tool.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(com.anthropic.models.messages.Tool.InputSchema.builder()
                        .properties(propsBuilder.build())
                        .required(required)
                        .build())
                .build();
    }
}
