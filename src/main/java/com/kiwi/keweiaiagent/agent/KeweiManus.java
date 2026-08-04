package com.kiwi.keweiaiagent.agent;

import com.kiwi.keweiaiagent.advisor.MyLoggerAdvisor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


/**
 * Manus 智能体实现类，负责装配工具集、系统提示词和大模型客户端。
 */
@Component
@Slf4j
public class KeweiManus extends ToolCallAgent{
    /**
     * 使用百炼聊天模型和指定工具集合创建不携带长期记忆提示词的 Manus 智能体。
     *
     * @param allTools 智能体可调用的工具集合
     * @param chatModel 百炼 DashScope 聊天模型
     */
    @Autowired
    public KeweiManus(ToolCallback[] allTools, ChatModel chatModel) {
        this(allTools, chatModel, "");
    }

    /**
     * 使用百炼聊天模型、工具集合和长期记忆提示词创建 Manus 智能体，随后通过
     * {@link ChatClient#builder(ChatModel)} 组装日志 Advisor 并交给父类执行 ReAct 流程。
     *
     * @param allTools 智能体可调用的工具集合
     * @param chatModel 百炼 DashScope 聊天模型
     * @param longTermMemoryPrompt 追加到系统提示词中的长期记忆说明
     */
    public KeweiManus(ToolCallback[] allTools, ChatModel chatModel, String longTermMemoryPrompt) {
        super(allTools);
        this.setName("KeweiManus");
        String System_Prompt = """
                You are KeweiManus, an all-capable AI assistant, aimed at solving any task presented by the user.
                You have various tools at your disposal that you can call upon to efficiently complete complex requests.
                For any multi-step task, first call TodoWrite to create a concise plan before executing.
                Keep the todo list updated as work moves from pending to in_progress to completed.
                If a task will be delegated through delegateResearchToOpenClaw, treat that delegation as one atomic todo step.
                Do not split the remote OpenClaw work into fake internal subtasks like searching, scraping, and pricing inside TodoWrite.
                                
                For durable facts that should survive across sessions, use the long-term memory tools and keep MEMORY.md in sync.
                """ + "\n\n" + longTermMemoryPrompt;
        this.setSystemPrompt(System_Prompt);

        String Next_Step_Prompt = """
                Based on user needs, proactively select the most appropriate tool or combination of tools.
                For complex tasks, you can break down the problem and use different tools step by step to solve it.
                Whenever you complete a subtask or move to the next subtask, call TodoWrite immediately to refresh the checklist before using another tool.
                When delegateResearchToOpenClaw is the chosen tool, keep the todo list at the orchestration level only, for example: clarify goal, delegate research, summarize result.
                Reuse long-term memory only for durable, cross-session facts, and avoid saving transient task noise.
                After using each tool, clearly explain the execution results and suggest the next steps.
                If you want to stop the interaction at any point, use the terminate’ tool/function call.
                """;
        this.setNextStepPrompt(Next_Step_Prompt);
        this.setMaxSteps(20);

        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
        log.info("已使用百炼 DashScope ChatModel 初始化 Manus 聊天客户端，工具数量={}", allTools.length);
    }
}
