package com.kiwi.keweiaiagent.agent;

import com.kiwi.keweiaiagent.app.LongTermMemoryPromptService;
import com.kiwi.keweiaiagent.agent.entity.ManusExecutionDO;
import com.kiwi.keweiaiagent.exception.BusinessException;
import com.kiwi.keweiaiagent.exception.ErrorCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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
     * Manus 执行数据库服务，每次启动任务先创建独立执行记录，再启动异步智能体。
     */
    @Resource
    private ManusExecutionService manusExecutionService;

    /**
     * 同一服务进程内按 chatId 串行化 Manus 启动和继续操作，确保数据库执行记录提交顺序
     * 与内存会话发布顺序一致，避免较早请求在较晚请求之后覆盖最新 executionId。
     */
    private final ConcurrentHashMap<String, SessionExecutionLock> sessionExecutionLocks = new ConcurrentHashMap<>();

    /**
     * 根据用户初始消息筛选任务工具，加载长期记忆提示词后创建 Manus 智能体，
     * 将会话编号和存储组件绑定到智能体，再调用 {@link KeweiManus#runStream(String)}
     * 返回流式结果。会话会在执行前写入 {@link ManusSessionStore}，供后续补充信息时恢复。
     *
     * @param accountId 当前认证账号主键
     * @param chatId 服务端生成且已经完成归属校验的会话标识
     * @param message 用户提交的初始任务内容
     * @return 持续推送 Manus 执行事件的 SSE 发射器
     */
    public SseEmitter startChatStream(Long accountId, String chatId, String message) {
        if (!StringUtils.hasText(message)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "Manus 任务不能为空");
        }
        SessionExecutionLock sessionLock = acquireSessionExecutionLock(chatId);
        try {
            ToolCallback[] selectedTools = selectToolsForPrompt(message);
            log.info("使用百炼 ChatModel 启动 Manus 会话，chatId={}，工具数量={}", chatId, selectedTools.length);
            KeweiManus manus = new KeweiManus(selectedTools, chatModel, longTermMemoryPromptService.buildPrompt());
            manus.setSessionId(chatId);
            manus.setManusSessionStore(manusSessionStore);
            ManusExecutionDO execution = manusExecutionService.startExecution(accountId, chatId, message);
            manus.setExecutionId(execution.getExecutionId());
            manusSessionStore.putSession(chatId, accountId, execution.getExecutionId(), message, manus);
            log.info("Manus 执行已按会话顺序发布到内存，chatId={}，executionId={}",
                    chatId, execution.getExecutionId());
            return manus.runStream(message);
        } finally {
            releaseSessionExecutionLock(chatId, sessionLock);
        }
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
        if (answers == null || answers.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "Manus 补充答案不能为空");
        }
        SessionExecutionLock sessionLock = acquireSessionExecutionLock(chatId);
        try {
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
            manus.setExecutionId(session.executionId());
            ManusSessionStore.ManusSession continued =
                    manusSessionStore.prepareContinuation(chatId, manus, answers);
            if (continued == null) {
                throw new BusinessException(ErrorCode.CHAT_SESSION_CONFLICT, "Manus 执行已失效，不能继续提交答案");
            }
            log.info("Manus 补充答案已按会话顺序提交，chatId={}，executionId={}",
                    chatId, session.executionId());
            return manus.runStream(followupPrompt);
        } finally {
            releaseSessionExecutionLock(chatId, sessionLock);
        }
    }

    /**
     * 根据任务领域从已注册工具中筛选最小可用集合。
     *
     * <p>GENERAL 保留全部工具；有明确领域时只暴露该领域工具和长期记忆工具，
     * 减少模型误选工具并降低工具定义占用的上下文。</p>
     *
     * @param prompt 用户原始任务
     * @return 传给 KeweiManus 的工具回调数组
     */
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

    /**
     * 使用任务中的显式领域词识别工具路由类别，匹配顺序处理可能同时出现的关键词。
     *
     * @param prompt 用户原始任务
     * @return 任务领域；空白或未命中时为 GENERAL
     */
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

    /**
     * 判断已标准化文本是否包含任一领域关键词。
     *
     * @param text 已转为小写的任务文本
     * @param keywords 候选关键词
     * @return 命中任一关键词时返回 {@code true}
     */
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 合并领域工具与所有长期记忆工具，并生成不可变名称集合。
     *
     * @param toolNames 领域专用工具名称
     * @return 去重后的完整允许列表
     */
    private static Set<String> withMemoryTools(String... toolNames) {
        LinkedHashSet<String> names = new LinkedHashSet<>(MEMORY_TOOL_NAMES);
        names.addAll(Arrays.asList(toolNames));
        return Set.copyOf(names);
    }

    /**
     * 原子增加会话锁引用后再获取互斥锁，保证等待中的请求与持锁请求始终使用同一对象。
     */
    private SessionExecutionLock acquireSessionExecutionLock(String chatId) {
        SessionExecutionLock sessionLock = sessionExecutionLocks.compute(chatId, (key, existing) -> {
            SessionExecutionLock current = existing == null ? new SessionExecutionLock() : existing;
            current.referenceCount++;
            return current;
        });
        sessionLock.lock.lock();
        return sessionLock;
    }

    /**
     * 释放互斥锁并原子减少引用；最后一个持有者或等待者退出后移除 chatId，避免锁表随历史
     * 会话数量永久增长。引用在加锁前增加，因此不会把仍有等待请求的锁提前移除。
     */
    private void releaseSessionExecutionLock(String chatId, SessionExecutionLock sessionLock) {
        sessionLock.lock.unlock();
        sessionExecutionLocks.computeIfPresent(chatId, (key, current) -> {
            if (current != sessionLock) {
                return current;
            }
            current.referenceCount--;
            return current.referenceCount == 0 ? null : current;
        });
    }

    /**
     * 会话锁及其引用计数。引用计数只在 ConcurrentHashMap.compute 临界区内修改。
     */
    private static final class SessionExecutionLock {
        private final ReentrantLock lock = new ReentrantLock();
        private int referenceCount;
    }

    /**
     * 将用户补充答案整理为可继续执行的跟进提示词。
     */
    private String buildFollowupPrompt(ManusSessionStore.ManusSession session, Map<String, String> answers) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("原始任务：\n").append(session.initialPrompt()).append("\n\n");
        prompt.append("用户补充信息如下：\n");
        int acceptedAnswerCount = 0;
        if (session.pendingQuestions() != null) {
            for (ManusSessionStore.PendingQuestion question : session.pendingQuestions()) {
                String answer = answers.get(question.id());
                if (answer == null || answer.isBlank()) {
                    continue;
                }
                prompt.append("- ").append(question.question()).append("：").append(answer).append("\n");
                acceptedAnswerCount++;
            }
        }
        if (acceptedAnswerCount == 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "未提交任何有效的 Manus 补充答案");
        }
        prompt.append("\n请基于以上已确认信息继续执行任务，不要重复提问；只有在确实缺少完成任务所必需的信息时，才再次调用 AskUserQuestionTool。");
        return prompt.toString();
    }
}
