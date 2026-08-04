package com.kiwi.keweiaiagent.agent;

import com.kiwi.keweiaiagent.app.LongTermMemoryPromptService;
import com.kiwi.keweiaiagent.exception.BusinessException;
import com.kiwi.keweiaiagent.exception.ErrorCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Manus 会话服务，负责按任务类型选择工具并驱动会话继续执行。
 */
@Service
@Slf4j
public class ManusSessionService {

    private static final Set<String> MEMORY_TOOL_NAMES = Set.of(
            "MemoryView",
            "MemoryCreate",
            "MemoryStrReplace",
            "MemoryInsert",
            "MemoryDelete",
            "MemoryRename"
    );

    /**
     * 任务领域枚举，描述 Manus 会话当前适配的工具集合类别。
     */
    enum TaskDomain {
        GENERAL,
        RESEARCH,
        PPT,
        PDF,
        EMAIL
    }

    /**
     * 任务领域与允许工具名称之间的映射关系。
     */
    private static final Map<TaskDomain, Set<String>> DOMAIN_TOOL_NAMES = new EnumMap<>(TaskDomain.class);

    static {
        DOMAIN_TOOL_NAMES.put(TaskDomain.GENERAL, Set.of());
        DOMAIN_TOOL_NAMES.put(TaskDomain.RESEARCH, withMemoryTools(
                "AskUserQuestionTool",
                "TodoWrite",
                "delegateResearchToOpenClaw",
                "doTerminate"
        ));
        DOMAIN_TOOL_NAMES.put(TaskDomain.PPT, withMemoryTools(
                "AskUserQuestionTool",
                "TodoWrite",
                "delegateResearchToOpenClaw",
                "create_pptx",
                "doTerminate"
        ));
        DOMAIN_TOOL_NAMES.put(TaskDomain.PDF, withMemoryTools(
                "AskUserQuestionTool",
                "TodoWrite",
                "downloadResource",
                "readFile",
                "writeFile",
                "pdfToImages",
                "doTerminate"
        ));
        DOMAIN_TOOL_NAMES.put(TaskDomain.EMAIL, withMemoryTools(
                "AskUserQuestionTool",
                "TodoWrite",
                "readFile",
                "writeFile",
                "sendEmail",
                "doTerminate"
        ));
    }

    /**
     * 系统中注册的全部工具。
     */
    @Resource
    private ToolCallback[] allTools;

    /**
     * 用于创建 Manus 智能体的系统默认聊天模型，具体供应方由 Spring AI 配置决定。
     */
    @Resource
    private ChatModel chatModel;

    /**
     * Manus 会话存储组件。
     */
    @Resource
    private ManusSessionStore manusSessionStore;

    /**
     * 长期记忆系统提示词加载器。
     */
    @Resource
    private LongTermMemoryPromptService longTermMemoryPromptService;

    /**
     * 根据用户初始消息筛选任务工具，加载长期记忆提示词后创建 Manus 智能体，
     * 将会话编号和存储组件绑定到智能体，再调用 {@link KeweiManus#runStream(String)}
     * 返回流式结果。会话会在执行前写入 {@link ManusSessionStore}，供后续补充信息时恢复。
     *
     * @param chatId 前端生成的会话唯一标识
     * @param message 用户提交的初始任务内容
     * @return 持续推送 Manus 执行事件的 SSE 发射器
     */
    public SseEmitter startChatStream(String chatId, String message) {
        ToolCallback[] selectedTools = selectToolsForPrompt(message);
        log.info("使用百炼 ChatModel 启动 Manus 会话，chatId={}，工具数量={}", chatId, selectedTools.length);
        KeweiManus manus = new KeweiManus(selectedTools, chatModel, longTermMemoryPromptService.buildPrompt());
        manus.setSessionId(chatId);
        manus.setManusSessionStore(manusSessionStore);
        manusSessionStore.putSession(chatId, message, manus);
        return manus.runStream(message);
    }

    /**
     * 从 {@link ManusSessionStore} 读取待继续会话，把用户补充答案整理为跟进提示词，
     * 按原始任务重新选择工具并创建 Manus 智能体，随后覆盖保存最新会话状态并调用
     * {@link KeweiManus#runStream(String)} 继续流式执行。
     *
     * @param chatId 待继续会话的唯一标识
     * @param answers 问题编号与用户补充答案之间的映射
     * @return 持续推送后续执行事件的 SSE 发射器
     * @throws BusinessException 会话不存在或不处于可继续状态时抛出
     */
    public SseEmitter continueChatStream(String chatId, Map<String, String> answers) {
        ManusSessionStore.ManusSession session = manusSessionStore.getSession(chatId);
        if (session == null || session.agent() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "未找到待继续的会话");
        }
        String followupPrompt = buildFollowupPrompt(session, answers);

        ToolCallback[] selectedTools = selectToolsForPrompt(session.initialPrompt());
        log.info("使用百炼 ChatModel 继续 Manus 会话，chatId={}，工具数量={}", chatId, selectedTools.length);
        KeweiManus manus = new KeweiManus(selectedTools, chatModel, longTermMemoryPromptService.buildPrompt());
        manus.setSessionId(chatId);
        manus.setManusSessionStore(manusSessionStore);
        manusSessionStore.putSession(chatId, followupPrompt, manus);
        return manus.runStream(followupPrompt);
    }

    ToolCallback[] selectToolsForPrompt(String prompt) {
        TaskDomain domain = routeTaskDomain(prompt);
        if (domain == TaskDomain.GENERAL) {
            return allTools;
        }
        Set<String> allowedTools = DOMAIN_TOOL_NAMES.getOrDefault(domain, Set.of());
        ToolCallback[] selected = Arrays.stream(allTools)
                .filter(tool -> allowedTools.contains(tool.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
        if (selected.length == 0) {
            log.warn("No tools matched for domain {}; falling back to all tools", domain);
            return allTools;
        }
        Set<String> selectedNames = new LinkedHashSet<>();
        for (ToolCallback tool : selected) {
            selectedNames.add(tool.getToolDefinition().name());
        }
        log.info("Selected Manus tool subset for domain {}: {}", domain, selectedNames);
        return selected;
    }

    TaskDomain routeTaskDomain(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return TaskDomain.GENERAL;
        }
        String normalized = prompt.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "ppt", "幻灯片", "演示文稿")) {
            return TaskDomain.PPT;
        }
        if (containsAny(normalized, "research", "调研", "搜集资料", "网页来源", "资料收集", "研究一下")) {
            return TaskDomain.RESEARCH;
        }
        if (containsAny(normalized, "pdf", "表单", "填写pdf", "填充pdf", "转换pdf", "convert pdf")) {
            return TaskDomain.PDF;
        }
        if (containsAny(normalized, "email", "mail", "邮件", "发信", "发送邮箱", "发送邮件")) {
            return TaskDomain.EMAIL;
        }
        return TaskDomain.GENERAL;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> withMemoryTools(String... toolNames) {
        LinkedHashSet<String> names = new LinkedHashSet<>(MEMORY_TOOL_NAMES);
        names.addAll(Arrays.asList(toolNames));
        return Set.copyOf(names);
    }

    /**
     * 将用户补充答案整理为可继续执行的跟进提示词。
     */
    private String buildFollowupPrompt(ManusSessionStore.ManusSession session, Map<String, String> answers) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("原始任务：\n").append(session.initialPrompt()).append("\n\n");
        prompt.append("用户补充信息如下：\n");
        if (session.pendingQuestions() != null) {
            for (ManusSessionStore.PendingQuestion question : session.pendingQuestions()) {
                String answer = answers.get(question.id());
                if (answer == null || answer.isBlank()) {
                    continue;
                }
                prompt.append("- ").append(question.question()).append("：").append(answer).append("\n");
            }
        }
        prompt.append("\n请基于以上已确认信息继续执行任务，不要重复提问；只有在确实缺少完成任务所必需的信息时，才再次调用 AskUserQuestionTool。");
        return prompt.toString();
    }
}
