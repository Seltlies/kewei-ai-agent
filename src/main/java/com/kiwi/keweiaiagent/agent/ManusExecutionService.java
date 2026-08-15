package com.kiwi.keweiaiagent.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kiwi.keweiaiagent.agent.entity.ManusExecutionDO;
import com.kiwi.keweiaiagent.agent.mapper.ManusExecutionMapper;
import com.kiwi.keweiaiagent.agent.model.ManusExecutionStatus;
import com.kiwi.keweiaiagent.agent.todo.TodoSnapshot;
import com.kiwi.keweiaiagent.chat.service.ChatSessionService;
import com.kiwi.keweiaiagent.chat.entity.ChatSessionDO;
import com.kiwi.keweiaiagent.chat.mapper.ChatSessionMapper;
import com.kiwi.keweiaiagent.chatmemory.entity.ChatMemoryMessageDO;
import com.kiwi.keweiaiagent.chatmemory.mapper.ChatMemoryMessageMapper;
import com.kiwi.keweiaiagent.exception.BusinessException;
import com.kiwi.keweiaiagent.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manus 执行持久化服务，负责执行状态机、历史展示事件和服务重启中断处理。所有更新条件
 * 同时包含 executionId、sessionId 和 userId，避免内存会话键碰撞造成跨账号写入。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManusExecutionService {

    private static final String RESTART_REASON = "服务重启导致执行中断";
    private static final String NEW_EXECUTION_REASON = "用户在原会话发起新的 Manus 执行";

    private final ManusExecutionMapper manusExecutionMapper;
    private final ChatMemoryMessageMapper chatMemoryMessageMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatSessionService chatSessionService;
    private final ObjectMapper objectMapper;

    /**
     * 创建新的 Manus 执行。同一会话中尚未结束的旧执行先被明确标记为中断，确保只有
     * 最新 executionId 可以继续接收 Todo 和补充答案。
     */
    @Transactional(transactionManager = "mysqlTransactionManager")
    public ManusExecutionDO startExecution(Long accountId, String sessionId, String task) {
        lockOwnedSession(accountId, sessionId);
        List<ManusExecutionDO> activeExecutions = manusExecutionMapper.selectList(
                new LambdaQueryWrapper<ManusExecutionDO>()
                        .eq(ManusExecutionDO::getUserId, accountId)
                        .eq(ManusExecutionDO::getSessionId, sessionId)
                        .in(ManusExecutionDO::getStatus,
                                ManusExecutionStatus.RUNNING,
                                ManusExecutionStatus.WAITING_USER)
        );
        for (ManusExecutionDO execution : activeExecutions) {
            updateTerminalStatus(execution, ManusExecutionStatus.INTERRUPTED, NEW_EXECUTION_REASON);
        }

        LocalDateTime now = LocalDateTime.now();
        ManusExecutionDO execution = new ManusExecutionDO();
        execution.setExecutionId(UUID.randomUUID().toString());
        execution.setSessionId(sessionId);
        execution.setUserId(accountId);
        execution.setStatus(ManusExecutionStatus.RUNNING);
        execution.setTaskPayload(task);
        execution.setCreateTime(now);
        execution.setUpdateTime(now);
        execution.setVersion(0L);
        manusExecutionMapper.insert(execution);
        appendDisplayMessage(execution, "USER", task, "MANUS_TASK", Map.of());
        chatSessionService.touchOwnedSession(accountId, sessionId);
        log.info("Manus 执行创建成功，executionId={}，sessionId={}，accountId={}",
                execution.getExecutionId(), sessionId, accountId);
        return execution;
    }

    /**
     * 保存最新 Todo 快照，同时追加结构化历史事件，保证服务重启后页面仍能恢复最近进度。
     */
    @Transactional(transactionManager = "mysqlTransactionManager")
    public void saveTodo(ManusSessionStore.ManusSession session, TodoSnapshot todoSnapshot) {
        lockOwnedSession(session.accountId(), session.chatId());
        int updated = manusExecutionMapper.update(
                null,
                activeExecutionUpdate(session)
                        .set(ManusExecutionDO::getTodoPayload, writeJson(todoSnapshot))
        );
        requireUpdated(updated, session.executionId());
        appendDisplayMessage(session, "ASSISTANT", "", "MANUS_TODO", Map.of("todo", todoSnapshot));
        log.info("Manus Todo 已持久化，executionId={}，sessionId={}", session.executionId(), session.chatId());
    }

    /**
     * 保存补充问题并把执行置为 WAITING_USER。问题会作为历史事件长期保留。
     */
    @Transactional(transactionManager = "mysqlTransactionManager")
    public void waitForUser(
            ManusSessionStore.ManusSession session,
            List<ManusSessionStore.PendingQuestion> questions
    ) {
        lockOwnedSession(session.accountId(), session.chatId());
        int updated = manusExecutionMapper.update(
                null,
                ownedExecutionUpdate(session)
                        .eq(ManusExecutionDO::getStatus, ManusExecutionStatus.RUNNING)
                        .set(ManusExecutionDO::getStatus, ManusExecutionStatus.WAITING_USER)
                        .set(ManusExecutionDO::getQuestionPayload, writeJson(questions))
                        .set(ManusExecutionDO::getUpdateTime, LocalDateTime.now())
                        .setSql("version = version + 1")
        );
        requireUpdated(updated, session.executionId());
        appendDisplayMessage(session, "ASSISTANT", "", "MANUS_QUESTION", Map.of("questions", questions));
        log.info("Manus 执行等待用户补充，executionId={}，sessionId={}", session.executionId(), session.chatId());
    }

    /**
     * 校验持久化状态仍为 WAITING_USER 后恢复为 RUNNING，并记录用户提交的补充答案。
     */
    @Transactional(transactionManager = "mysqlTransactionManager")
    public void resumeExecution(ManusSessionStore.ManusSession session, Map<String, String> answers) {
        lockOwnedSession(session.accountId(), session.chatId());
        int updated = manusExecutionMapper.update(
                null,
                ownedExecutionUpdate(session)
                        .eq(ManusExecutionDO::getStatus, ManusExecutionStatus.WAITING_USER)
                        .set(ManusExecutionDO::getStatus, ManusExecutionStatus.RUNNING)
                        .set(ManusExecutionDO::getUpdateTime, LocalDateTime.now())
                        .setSql("version = version + 1")
        );
        requireUpdated(updated, session.executionId());
        appendDisplayMessage(session, "USER", "", "MANUS_ANSWER", Map.of("answers", answers));
        log.info("Manus 执行恢复运行，executionId={}，sessionId={}", session.executionId(), session.chatId());
    }

    /**
     * 保存已经向页面推送的 Manus 步骤文本，刷新页面后仍可从消息接口恢复。
     */
    @Transactional(transactionManager = "mysqlTransactionManager")
    public void saveAssistantMessage(ManusSessionStore.ManusSession session, String content) {
        lockOwnedSession(session.accountId(), session.chatId());
        requireActiveExecution(session);
        appendDisplayMessage(session, "ASSISTANT", content, "MANUS_MESSAGE", Map.of());
        chatSessionService.touchOwnedSession(session.accountId(), session.chatId());
    }

    /**
     * 将仍处于活动状态的执行原子更新为完成，并拒绝已经被新任务替换的旧执行。
     *
     * @param session 当前内存执行上下文
     */
    @Transactional(transactionManager = "mysqlTransactionManager")
    public void completeExecution(ManusSessionStore.ManusSession session) {
        lockOwnedSession(session.accountId(), session.chatId());
        boolean completed = updateTerminalStatus(session, ManusExecutionStatus.COMPLETED, null);
        if (!completed) {
            log.warn("Manus 执行完成状态更新失败，executionId={}，sessionId={}",
                    session.executionId(), session.chatId());
            throw new BusinessException(ErrorCode.CHAT_SESSION_CONFLICT, "Manus 执行已被替换，不能发送完成事件");
        }
    }

    /**
     * 将活动执行标记为失败，并对写入数据库的原因做长度和换行归一化。
     *
     * @param session 当前内存执行上下文
     * @param reason 原始失败原因
     */
    @Transactional(transactionManager = "mysqlTransactionManager")
    public void failExecution(ManusSessionStore.ManusSession session, String reason) {
        lockOwnedSession(session.accountId(), session.chatId());
        updateTerminalStatus(session, ManusExecutionStatus.FAILED, normalizeReason(reason));
    }

    /**
     * 应用完成启动后中断上个进程遗留的 RUNNING 和 WAITING_USER。这里只写状态与历史事件，
     * 不创建智能体、不调用工具，也不自动重新执行任何任务。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(transactionManager = "mysqlTransactionManager")
    public void interruptExecutionsLeftByRestart() {
        List<ManusExecutionDO> unfinished = manusExecutionMapper.selectList(
                new LambdaQueryWrapper<ManusExecutionDO>()
                        .in(ManusExecutionDO::getStatus,
                                ManusExecutionStatus.RUNNING,
                                ManusExecutionStatus.WAITING_USER)
                        .orderByAsc(ManusExecutionDO::getId)
        );
        for (ManusExecutionDO execution : unfinished) {
            lockOwnedSession(execution.getUserId(), execution.getSessionId());
            updateTerminalStatus(execution, ManusExecutionStatus.INTERRUPTED, RESTART_REASON);
        }
        if (!unfinished.isEmpty()) {
            log.warn("服务启动时已中断遗留 Manus 执行，数量={}", unfinished.size());
        }
    }

    /**
     * 构造只允许更新 RUNNING 或 WAITING_USER 记录的乐观状态更新条件。
     *
     * @param session 当前执行上下文
     * @return 带归属、活动状态、更新时间和版本递增规则的更新条件
     */
    private LambdaUpdateWrapper<ManusExecutionDO> activeExecutionUpdate(
            ManusSessionStore.ManusSession session
    ) {
        return ownedExecutionUpdate(session)
                .in(ManusExecutionDO::getStatus,
                        ManusExecutionStatus.RUNNING,
                        ManusExecutionStatus.WAITING_USER)
                .set(ManusExecutionDO::getUpdateTime, LocalDateTime.now())
                .setSql("version = version + 1");
    }

    /**
     * 构造 executionId、sessionId、accountId 三重归属约束，防止跨会话或跨账号更新。
     *
     * @param session 当前执行上下文
     * @return 基础归属更新条件
     */
    private LambdaUpdateWrapper<ManusExecutionDO> ownedExecutionUpdate(
            ManusSessionStore.ManusSession session
    ) {
        return new LambdaUpdateWrapper<ManusExecutionDO>()
                .eq(ManusExecutionDO::getExecutionId, session.executionId())
                .eq(ManusExecutionDO::getSessionId, session.chatId())
                .eq(ManusExecutionDO::getUserId, session.accountId());
    }

    /**
     * 原子写入内存会话对应的执行终态，并追加可恢复的状态历史事件。
     *
     * @param session 当前执行上下文
     * @param status 目标终态
     * @param reason 可选原因
     * @return 仅在一条活动记录成功更新时返回 {@code true}
     */
    private boolean updateTerminalStatus(
            ManusSessionStore.ManusSession session,
            ManusExecutionStatus status,
            String reason
    ) {
        int updated = manusExecutionMapper.update(
                null,
                activeExecutionUpdate(session)
                        .set(ManusExecutionDO::getStatus, status)
                        .set(ManusExecutionDO::getReason, reason)
        );
        if (updated == 1) {
            appendDisplayMessage(session, "ASSISTANT", "", "MANUS_STATUS", terminalMetadata(status, reason));
            chatSessionService.touchOwnedSession(session.accountId(), session.chatId());
            log.info("Manus 执行状态已更新，executionId={}，sessionId={}，status={}",
                    session.executionId(), session.chatId(), status);
            return true;
        }
        return false;
    }

    /**
     * 将数据库执行记录适配为统一会话模型后复用终态更新逻辑。
     *
     * @param execution 数据库执行记录
     * @param status 目标终态
     * @param reason 可选原因
     */
    private void updateTerminalStatus(
            ManusExecutionDO execution,
            ManusExecutionStatus status,
            String reason
    ) {
        ManusSessionStore.ManusSession session = new ManusSessionStore.ManusSession(
                execution.getSessionId(),
                execution.getUserId(),
                execution.getExecutionId(),
                execution.getTaskPayload(),
                null,
                null,
                null,
                null,
                null
        );
        updateTerminalStatus(session, status, reason);
    }

    /**
     * 构造历史状态事件的元数据，只有存在原因时才写入 reason 字段。
     *
     * @param status 执行终态
     * @param reason 可选原因
     * @return 保持字段顺序的事件元数据
     */
    private Map<String, Object> terminalMetadata(ManusExecutionStatus status, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("status", status);
        if (reason != null) {
            metadata.put("reason", reason);
        }
        return metadata;
    }

    /**
     * 从内存会话提取执行标识并追加一条前端可恢复的历史事件。
     */
    private void appendDisplayMessage(
            ManusSessionStore.ManusSession session,
            String type,
            String text,
            String messageKind,
            Map<String, Object> eventMetadata
    ) {
        appendDisplayMessage(
                session.executionId(), session.chatId(), type, text, messageKind, eventMetadata);
    }

    /**
     * 从数据库执行记录提取标识并追加一条前端可恢复的历史事件。
     */
    private void appendDisplayMessage(
            ManusExecutionDO execution,
            String type,
            String text,
            String messageKind,
            Map<String, Object> eventMetadata
    ) {
        appendDisplayMessage(
                execution.getExecutionId(),
                execution.getSessionId(),
                type,
                text,
                messageKind,
                eventMetadata
        );
    }

    /**
     * 按 ChatMemoryMessageDO 的统一 JSON 结构保存 Manus 展示事件。
     *
     * <p>messageKind 和 executionId 放在 metadata 中，使历史接口无需识别数据库表即可还原
     * 普通文本、Todo、问题、回答和终态。</p>
     *
     * @param executionId 执行标识
     * @param sessionId 会话标识
     * @param type 消息角色
     * @param text 展示文本
     * @param messageKind Manus 事件种类
     * @param eventMetadata 事件扩展数据
     */
    private void appendDisplayMessage(
            String executionId,
            String sessionId,
            String type,
            String text,
            String messageKind,
            Map<String, Object> eventMetadata
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("messageKind", messageKind);
        metadata.put("executionId", executionId);
        metadata.putAll(eventMetadata);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("text", text);
        payload.put("metadata", metadata);
        payload.put("toolCalls", List.of());
        payload.put("toolResponses", List.of());

        ChatMemoryMessageDO message = new ChatMemoryMessageDO();
        message.setConversationId(sessionId);
        message.setPayloadJson(writeJson(payload));
        chatMemoryMessageMapper.insert(message);
    }

    /**
     * 使用全局 ObjectMapper 序列化执行载荷，并把序列化失败转换为统一业务异常。
     *
     * @param value 待序列化对象
     * @return JSON 文本
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Manus 执行数据序列化失败", e);
        }
    }

    /**
     * 将失败原因整理为适合数据库和页面展示的单行、定长文本。
     *
     * @param reason 原始失败原因
     * @return 非空且最长 500 字符的原因
     */
    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Manus 执行失败";
        }
        String normalized = reason.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    /**
     * 校验状态机更新恰好命中一条记录，否则视为并发替换或非法状态转换。
     *
     * @param updated 数据库更新行数
     * @param executionId 用于日志定位的执行标识
     */
    private void requireUpdated(int updated, String executionId) {
        if (updated != 1) {
            log.warn("Manus 执行状态更新冲突，executionId={}", executionId);
            throw new BusinessException(ErrorCode.CHAT_SESSION_CONFLICT, "Manus 执行状态已变化，不能继续当前操作");
        }
    }

    /**
     * 所有 Manus 状态变更统一先锁定所属会话，再更新执行记录和历史消息。固定的
     * “会话行 -> 执行行”加锁顺序可以避免启动、完成和失败路径交叉时形成数据库死锁。
     */
    private void lockOwnedSession(Long accountId, String sessionId) {
        ChatSessionDO lockedSession = chatSessionMapper.selectOne(
                new LambdaQueryWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getUserId, accountId)
                        .eq(ChatSessionDO::getSessionId, sessionId)
                        .last("FOR UPDATE")
        );
        if (lockedSession == null) {
            throw new BusinessException(ErrorCode.CHAT_SESSION_NOT_FOUND);
        }
    }

    /**
     * 在会话行锁保护下确认当前 execution 仍可写入过程消息。新任务若已把旧 execution 标记
     * 为 INTERRUPTED，旧工作线程即使早先通过内存校验，也不能再追加过期历史记录。
     */
    private void requireActiveExecution(ManusSessionStore.ManusSession session) {
        ManusExecutionDO activeExecution = manusExecutionMapper.selectOne(
                new LambdaQueryWrapper<ManusExecutionDO>()
                        .eq(ManusExecutionDO::getExecutionId, session.executionId())
                        .eq(ManusExecutionDO::getSessionId, session.chatId())
                        .eq(ManusExecutionDO::getUserId, session.accountId())
                        .in(ManusExecutionDO::getStatus,
                                ManusExecutionStatus.RUNNING,
                                ManusExecutionStatus.WAITING_USER)
                        .last("FOR UPDATE")
        );
        if (activeExecution == null) {
            log.warn("已拒绝过期 Manus 执行写入历史，executionId={}，sessionId={}",
                    session.executionId(), session.chatId());
            throw new BusinessException(ErrorCode.CHAT_SESSION_CONFLICT, "Manus 执行已结束，不能继续写入消息");
        }
    }
}
