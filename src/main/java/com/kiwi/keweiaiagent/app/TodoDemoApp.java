package com.kiwi.keweiaiagent.app;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.Set;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Component
@Slf4j
public class TodoDemoApp {

    private static final String SYSTEM_PROMPT = """
            You are a TodoWrite demo assistant.
            For tasks with more than a couple of steps, first call TodoWrite to create a short plan.
            Then keep the todo list updated while working.
            Use doTerminate when the task is complete.
            """;

    private static final Set<String> DEMO_TOOL_NAMES = Set.of(
            "TodoWrite",
            "AskUserQuestionTool",
            "doTerminate"
    );

    private final ChatClient chatClient;
    private final ToolCallback[] demoTools;

    /**
     * 使用系统默认的百炼聊天模型创建 Todo 演示客户端，并从全部工具中筛选演示所需工具。
     *
     * @param chatModel 百炼 DashScope 聊天模型
     * @param allTools Spring 容器中注册的全部工具
     */
    public TodoDemoApp(ChatModel chatModel, ToolCallback[] allTools) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
        this.demoTools = Arrays.stream(allTools)
                .filter(tool -> DEMO_TOOL_NAMES.contains(tool.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
        log.info("已使用百炼 DashScope ChatModel 初始化 Todo 演示客户端，工具数量={}", demoTools.length);
    }

    public String call(String message, String chatId) {
        ChatResponse chatResponse = chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(CONVERSATION_ID, chatId))
                .toolCallbacks(demoTools)
                .call()
                .chatResponse();
        assert chatResponse != null;
        return chatResponse.getResult().getOutput().getText();
    }

    public Flux<String> stream(String message, String chatId) {
        return Flux.defer(() -> Flux.just(call(message, chatId)));
    }
}
