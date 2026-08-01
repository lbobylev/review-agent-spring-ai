package review;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatOptions.Builder;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import review.ReviewModels.ReviewFindingsPayload;

@Service
final class ReviewAgent {

    private static final int MAX_TOOL_CALLS = 3;
    private static final String MODEL_NAME = "gpt-4.1";

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;

    ReviewAgent(ChatModel chatModel, ToolCallingManager toolCallingManager) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
    }

    private Builder getBuilder() {
        return OpenAiChatOptions.builder()
                .model(MODEL_NAME)
                .temperature(0.0)
                .maxTokens(12000);
    }

    ReviewFindingsPayload review(Path repoPath, String diff, String changedFiles) {
        BeanOutputConverter<ReviewFindingsPayload> outputConverter = new BeanOutputConverter<>(
                ReviewFindingsPayload.class);
        WorkspaceTools workspaceTools = new WorkspaceTools(repoPath);
        List<ToolCallback> toolCallbacks = Arrays.asList(MethodToolCallbackProvider.builder()
                .toolObjects(workspaceTools)
                .build()
                .getToolCallbacks());

        OpenAiChatOptions options = getBuilder().toolCallbacks(toolCallbacks).build();
        OpenAiChatOptions finalAnswerOptions = getBuilder().build();

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(ReviewPrompts.SYSTEM_PROMPT));
        messages.add(
                new UserMessage(ReviewPrompts.userPrompt(diff, changedFiles) + "\n\n" + outputConverter.getFormat()));

        Prompt prompt = new Prompt(messages, options);
        ChatResponse response = chatModel.call(prompt);
        int usedToolCalls = 0;

        // Spring AI 2 does not currently expose a built-in max tool call / recursion
        // limit.
        // Keep the loop explicit so review runs cannot get stuck in tool-calling
        // cycles.
        // See: https://github.com/spring-projects/spring-ai/issues/3333
        while (response.hasToolCalls()) {
            int requestedToolCalls = response.getResult().getOutput().getToolCalls().size();
            if (usedToolCalls + requestedToolCalls > MAX_TOOL_CALLS) {
                messages = new ArrayList<>(prompt.getInstructions());
                messages.add(new UserMessage(ReviewPrompts.toolLimitPrompt(MAX_TOOL_CALLS)));
                prompt = new Prompt(messages, finalAnswerOptions);
                response = chatModel.call(prompt);
                break;
            }

            usedToolCalls += requestedToolCalls;
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            prompt = new Prompt(result.conversationHistory(), options);
            response = chatModel.call(prompt);
        }

        String content = response.getResult().getOutput().getText();
        if (content == null || content.isBlank()) {
            throw new ReviewException("Error: agent returned no final response");
        }

        try {
            return outputConverter.convert(content);
        } catch (RuntimeException error) {
            throw new ReviewException("Error: structured response is malformed: " + error.getMessage(), error);
        }
    }
}
