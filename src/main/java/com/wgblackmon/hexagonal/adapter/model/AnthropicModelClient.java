package com.wgblackmon.hexagonal.adapter.model;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.core.type.TypeReference;
import com.wgblackmon.hexagonal.domain.ContentPart;
import com.wgblackmon.hexagonal.domain.Message;
import com.wgblackmon.hexagonal.domain.ModelClient;
import com.wgblackmon.hexagonal.domain.ModelRequest;
import com.wgblackmon.hexagonal.domain.ModelResponse;
import com.wgblackmon.hexagonal.domain.Role;
import com.wgblackmon.hexagonal.domain.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link ModelClient} adapter backed by the raw Anthropic Java SDK — the same
 * conversion logic already proven in Track A's {@code harness.Agent} (tool schema
 * mapping, {@code ContentBlock.toParam()}-equivalent reconstruction, thinking disabled),
 * relocated behind the port so {@code domain.Agent} never sees an Anthropic SDK type.
 *
 * @author  Bill Blackmon
 * @version 1.0
 * @since   2026-10-18
 * @updated 2026-10-18
 */
public class AnthropicModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicModelClient.class);

    private final AnthropicClient client;
    private final String model;

    public AnthropicModelClient(AnthropicClient client, String model) {
        log.debug("<init>() | model={}", model);

        this.client = client;
        this.model = model;

        log.debug("<init>() | return=void");
    }

    @Override
    public ModelResponse send(ModelRequest request) {
        log.debug("send() | request={}", request);

        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(request.systemPrompt())
                .thinking(ThinkingConfigDisabled.builder().build())
                .messages(toApiMessages(request.messages()));
        request.tools().forEach(t -> params.addTool(toApiTool(t)));

        com.anthropic.models.messages.Message response = client.messages().create(params.build());

        ModelResponse result = new ModelResponse(response.content().stream()
                .map(AnthropicModelClient::toContentPart)
                .collect(Collectors.toList()));

        log.debug("send() | return={}", result);
        return result;
    }

    private static List<MessageParam> toApiMessages(List<Message> messages) {
        return messages.stream().map(AnthropicModelClient::toApiMessage).collect(Collectors.toList());
    }

    private static MessageParam toApiMessage(Message message) {
        MessageParam.Role role = message.role() == Role.USER ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT;
        List<ContentBlockParam> blocks = message.parts().stream()
                .map(AnthropicModelClient::toApiContentBlock)
                .collect(Collectors.toList());
        return MessageParam.builder().role(role).contentOfBlockParams(blocks).build();
    }

    private static ContentBlockParam toApiContentBlock(ContentPart part) {
        if (part instanceof ContentPart.Text text) {
            return ContentBlockParam.ofText(TextBlockParam.builder().text(text.value()).build());
        }
        if (part instanceof ContentPart.ToolUse toolUse) {
            return ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                    .id(toolUse.id())
                    .name(toolUse.name())
                    .input(JsonValue.from(toolUse.input()))
                    .build());
        }
        if (part instanceof ContentPart.ToolResult toolResult) {
            return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(toolResult.toolUseId())
                    .content(toolResult.content())
                    .isError(toolResult.isError())
                    .build());
        }
        throw new IllegalStateException("Unsupported content part: " + part);
    }

    private static ContentPart toContentPart(ContentBlock block) {
        if (block.text().isPresent()) {
            return new ContentPart.Text(block.text().get().text());
        }
        if (block.toolUse().isPresent()) {
            ToolUseBlock toolUse = block.toolUse().get();
            Map<String, Object> input = toolUse._input().convert(new TypeReference<Map<String, Object>>() {
            });
            return new ContentPart.ToolUse(toolUse.id(), toolUse.name(), input);
        }
        throw new IllegalStateException("Unsupported content block in model response: " + block);
    }

    private static com.anthropic.models.messages.Tool toApiTool(Tool tool) {
        Map<String, Object> schema = tool.inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        @SuppressWarnings("unchecked")
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
