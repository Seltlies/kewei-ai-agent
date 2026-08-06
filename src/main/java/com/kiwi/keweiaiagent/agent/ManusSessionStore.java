package com.kiwi.keweiaiagent.agent;

import com.kiwi.keweiaiagent.agent.todo.TodoSnapshot;
import com.kiwi.keweiaiagent.exception.BusinessException;
import com.kiwi.keweiaiagent.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manus 会话存储组件，负责保存会话状态、待答问题和待办快照。
 */
@Component
@RequiredArgsConstructor
public class ManusSessionStore {

    /**
     * Manus 执行持久化服务，内存状态每次变化时同步写入 MySQL 和页面历史消息。
     */
    private final ManusExecutionService manusExecutionService;

    /**
     * 待办快照监听器接口，用于在会话待办列表变化时接收通知。
     */
    @FunctionalInterface
    public interface TodoSnapshotListener {
        void onTodoSnapshot(TodoSnapshot todoSnapshot);
    }

    /**
     * 待回答选项对象，描述单个问题选项的展示文案和说明。
     */
    public record PendingOption(String label, String description) {}

    /**
     * 待回答问题对象，封装前端展示所需的问题元数据。
     */
    public record PendingQuestion(String id, String header, String question, Boolean multiSelect, List<PendingOption> options) {}

    /**
     * Manus 会话记录对象，聚合会话上下文、待答问题与待办状态。
     */
    public record ManusSession(
            String chatId,
            Long accountId,
            String executionId,
            String initialPrompt,
            KeweiManus agent,
            List<AskUserQuestionTool.Question> rawPendingQuestions,
            List<PendingQuestion> pendingQuestions,
            Map<String, String> pendingAnswers,
            TodoSnapshot todoSnapshot
    ) {}

    /**
     * 当前已缓存的会话数据。
     */
    private final ConcurrentHashMap<String, ManusSession> sessions = new ConcurrentHashMap<>();
    /**
     * 执行级待办快照监听器集合。键使用 executionId 而不是 chatId，防止同一会话启动
     * 新任务后，旧工作线程产生的 Todo 事件被推送到新任务的 SSE 连接。
     */
    private final ConcurrentHashMap<String, List<TodoSnapshotListener>> todoSnapshotListeners = new ConcurrentHashMap<>();
    /**
     * 当前线程正在处理的会话标识。
     */
    private final ThreadLocal<ExecutionContext> currentExecutionContext = new ThreadLocal<>();

    /**
     * 保存新的会话记录。
     */
    public void putSession(
            String chatId,
            Long accountId,
            String executionId,
            String initialPrompt,
            KeweiManus agent
    ) {
        ManusSession previous = sessions.put(chatId, new ManusSession(
                chatId, accountId, executionId, initialPrompt, agent, null, null, null, null));
        if (previous != null && previous.agent() != null) {
            // 新执行接管同一会话前停止旧智能体，避免旧任务继续调用外部工具产生副作用。
            previous.agent().setState(com.kiwi.keweiaiagent.agent.model.AgentState.ERROR);
        }
    }

    /**
     * 按会话标识读取会话记录。
     */
    public ManusSession getSession(String chatId) {
        return sessions.get(chatId);
    }

    /**
     * 移除指定会话及其关联监听器。
     */
    public void removeSession(String chatId) {
        ManusSession removed = sessions.remove(chatId);
        if (removed != null && removed.executionId() != null) {
            todoSnapshotListeners.remove(removed.executionId());
        }
    }

    /**
     * 仅当内存中的 executionId 仍属于当前智能体时移除会话，防止旧异步任务结束时误删
     * 同一 chatId 下刚创建的新执行。
     */
    public void removeSession(String chatId, String executionId) {
        ManusSession session = sessions.get(chatId);
        if (session != null
                && Objects.equals(session.executionId(), executionId)
                && sessions.remove(chatId, session)) {
            todoSnapshotListeners.remove(executionId);
        }
    }

    /**
     * 保存等待用户回答的问题列表。
     */
    public void savePendingQuestions(String chatId, List<AskUserQuestionTool.Question> questions) {
        List<PendingQuestion> pendingQuestions = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            AskUserQuestionTool.Question question = questions.get(i);
            List<PendingOption> options = question.options() == null ? List.of() : question.options().stream()
                    .map(option -> new PendingOption(option.label(), option.description()))
                    .toList();
            pendingQuestions.add(new PendingQuestion(
                    "q_" + (i + 1),
                    question.header(),
                    question.question(),
                    question.multiSelect(),
                    options
            ));
        }
        AtomicReference<ManusSession> updatedSession = new AtomicReference<>();
        sessions.compute(chatId, (key, session) -> {
            requireMatchingExecution(key, session);
            ManusSession updated = new ManusSession(
                        key,
                        session.accountId(),
                        session.executionId(),
                        session.initialPrompt(),
                        session.agent(),
                        questions,
                        pendingQuestions,
                        session.pendingAnswers(),
                        session.todoSnapshot()
                );
            updatedSession.set(updated);
            return updated;
        });
        manusExecutionService.waitForUser(updatedSession.get(), pendingQuestions);
    }

    /**
     * 记录用户提交的答案。
     */
    public void submitAnswers(String chatId, Map<String, String> answers) {
        sessions.computeIfPresent(chatId, (key, session) ->
                new ManusSession(
                        key,
                        session.accountId(),
                        session.executionId(),
                        session.initialPrompt(),
                        session.agent(),
                        session.rawPendingQuestions(),
                        session.pendingQuestions(),
                        answers,
                        session.todoSnapshot()
                ));
    }

    /**
     * 提取并消费用户答案，转换为原始问题对应的映射。
     */
    public Map<String, String> consumeAnswers(String chatId) {
        ManusSession session = sessions.get(chatId);
        if (session == null || session.pendingAnswers() == null) {
            return null;
        }
        sessions.put(chatId, new ManusSession(
                chatId,
                session.accountId(),
                session.executionId(),
                session.initialPrompt(),
                session.agent(),
                session.rawPendingQuestions(),
                session.pendingQuestions(),
                null,
                session.todoSnapshot()
        ));
        if (session.pendingQuestions() == null || session.rawPendingQuestions() == null) {
            return session.pendingAnswers();
        }
        Map<String, String> mappedAnswers = new ConcurrentHashMap<>();
        for (int i = 0; i < session.pendingQuestions().size() && i < session.rawPendingQuestions().size(); i++) {
            PendingQuestion pendingQuestion = session.pendingQuestions().get(i);
            String answer = session.pendingAnswers().get(pendingQuestion.id());
            if (answer != null) {
                mappedAnswers.put(session.rawPendingQuestions().get(i).question(), answer);
            }
        }
        return mappedAnswers;
    }

    /**
     * 清空会话中的待回答问题。
     */
    public void clearPendingQuestions(String chatId) {
        sessions.computeIfPresent(chatId, (key, session) ->
                new ManusSession(
                        key,
                        session.accountId(),
                        session.executionId(),
                        session.initialPrompt(),
                        session.agent(),
                        null,
                        null,
                        session.pendingAnswers(),
                        session.todoSnapshot()
                ));
    }

    /**
     * 保存并广播当前会话的待办快照。
     */
    public void saveTodoSnapshot(String chatId, TodoSnapshot todoSnapshot) {
        AtomicReference<ManusSession> updatedSession = new AtomicReference<>();
        sessions.compute(chatId, (key, session) -> {
            requireMatchingExecution(key, session);
            ManusSession updated = new ManusSession(
                        key,
                        session.accountId(),
                        session.executionId(),
                        session.initialPrompt(),
                        session.agent(),
                        session.rawPendingQuestions(),
                        session.pendingQuestions(),
                        session.pendingAnswers(),
                        todoSnapshot
                );
            updatedSession.set(updated);
            return updated;
        });
        manusExecutionService.saveTodo(updatedSession.get(), todoSnapshot);
        List<TodoSnapshotListener> listeners = todoSnapshotListeners.get(updatedSession.get().executionId());
        if (listeners != null) {
            for (TodoSnapshotListener listener : List.copyOf(listeners)) {
                listener.onTodoSnapshot(todoSnapshot);
            }
        }
    }

    public TodoSnapshot getTodoSnapshot(String chatId) {
        ManusSession session = sessions.get(chatId);
        return session == null ? null : session.todoSnapshot();
    }

    /**
     * 仅返回指定 executionId 仍为当前执行时的 Todo，防止延迟启动的旧线程读取新任务快照。
     */
    public TodoSnapshot getTodoSnapshot(String chatId, String executionId) {
        ManusSession session = getSessionForExecution(chatId, executionId);
        return session == null ? null : session.todoSnapshot();
    }

    /**
     * 将指定会话设置为当前线程的活跃会话。
     */
    public void activateSession(String chatId, String executionId) {
        currentExecutionContext.set(new ExecutionContext(chatId, executionId));
    }

    /**
     * 清理当前线程记录的活跃会话。
     */
    public void clearActiveSession() {
        currentExecutionContext.remove();
    }

    public String currentSessionId() {
        ExecutionContext context = currentExecutionContext.get();
        return context == null ? null : context.chatId();
    }

    public List<PendingQuestion> getPendingQuestions(String chatId) {
        ManusSession session = sessions.get(chatId);
        return session == null ? null : session.pendingQuestions();
    }

    /**
     * 仅返回指定 executionId 仍为当前执行时的待答问题，避免旧 SSE 从相同 chatId 读取到
     * 新任务的问题内容。
     */
    public List<PendingQuestion> getPendingQuestions(String chatId, String executionId) {
        ManusSession session = getSessionForExecution(chatId, executionId);
        return session == null ? null : session.pendingQuestions();
    }

    /**
     * 判断指定执行是否仍是当前会话内存中的最新执行。
     */
    public boolean isCurrentExecution(String chatId, String executionId) {
        return getSessionForExecution(chatId, executionId) != null;
    }

    /**
     * 用户提交补充答案后，用新的智能体实例继续同一 executionId，并保留初始任务和 Todo。
     */
    public ManusSession prepareContinuation(
            String chatId,
            KeweiManus agent,
            Map<String, String> answers
    ) {
        ManusSession session = sessions.get(chatId);
        if (session == null) {
            return null;
        }
        manusExecutionService.resumeExecution(session, answers);
        ManusSession continued = new ManusSession(
                chatId,
                session.accountId(),
                session.executionId(),
                session.initialPrompt(),
                agent,
                null,
                null,
                null,
                session.todoSnapshot()
        );
        sessions.put(chatId, continued);
        return continued;
    }

    /**
     * 保存已经推送给页面的 Manus 文本结果。
     */
    public void saveAssistantMessage(String chatId, String executionId, String content) {
        ManusSession session = getSessionForExecution(chatId, executionId);
        if (session != null) {
            manusExecutionService.saveAssistantMessage(session, content);
        }
    }

    /**
     * 将内存中的活跃执行更新为完成状态。
     */
    public void completeExecution(String chatId, String executionId) {
        ManusSession session = getSessionForExecution(chatId, executionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.CHAT_SESSION_CONFLICT, "Manus 执行已被替换，不能标记完成");
        }
        manusExecutionService.completeExecution(session);
    }

    /**
     * 将内存中的活跃执行更新为失败状态，并记录可展示的失败原因。
     */
    public void failExecution(String chatId, String executionId, String reason) {
        ManusSession session = getSessionForExecution(chatId, executionId);
        if (session != null) {
            manusExecutionService.failExecution(session, reason);
        }
    }

    public String getExecutionId(String chatId) {
        ManusSession session = sessions.get(chatId);
        return session == null ? null : session.executionId();
    }

    private ManusSession getSessionForExecution(String chatId, String executionId) {
        ManusSession session = sessions.get(chatId);
        return session != null && Objects.equals(session.executionId(), executionId) ? session : null;
    }

    private void requireMatchingExecution(String chatId, ManusSession session) {
        ExecutionContext context = currentExecutionContext.get();
        if (session == null
                || context == null
                || !Objects.equals(context.chatId(), chatId)
                || !Objects.equals(context.executionId(), session.executionId())) {
            throw new BusinessException(ErrorCode.CHAT_SESSION_CONFLICT, "Manus 执行已被新的执行替换");
        }
    }

    private record ExecutionContext(String chatId, String executionId) {
    }

    /**
     * 为指定 Manus 执行注册待办快照监听器。
     */
    public void registerTodoSnapshotListener(String executionId, TodoSnapshotListener listener) {
        todoSnapshotListeners.compute(executionId, (key, existing) -> {
            List<TodoSnapshotListener> next = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            next.add(listener);
            return next;
        });
    }

    /**
     * 移除指定 Manus 执行上的待办快照监听器。
     */
    public void unregisterTodoSnapshotListener(String executionId, TodoSnapshotListener listener) {
        todoSnapshotListeners.computeIfPresent(executionId, (key, existing) -> {
            List<TodoSnapshotListener> next = new ArrayList<>(existing);
            next.remove(listener);
            return next.isEmpty() ? null : next;
        });
    }
}
