package com.kiwi.keweiaiagent.agent;


import com.kiwi.keweiaiagent.agent.model.AgentState;
import com.kiwi.keweiaiagent.agent.todo.TodoSnapshot;
import com.kiwi.keweiaiagent.exception.BusinessException;
import com.kiwi.keweiaiagent.exception.ErrorCode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 智能体抽象基类，封装状态管理、执行循环和流式事件分发等通用能力。
 */
@Data
@Slf4j
public abstract class BaseAgent {

    /**
     * 智能体名称，用于日志输出和执行结果标识。
     */
    private String name;

    /**
     * 系统提示词，用于约束智能体的全局行为。
     */
    private String systemPrompt;

    /**
     * 下一步提示词，用于驱动每轮执行后的继续推理。
     */
    private String nextStepPrompt;

    /**
     * 当前智能体状态。
     */
    private volatile AgentState state = AgentState.IDLE;

    /**
     * 当前执行到的步骤序号。
     */
    private int currentStep = 0;

    /**
     * 允许执行的最大步骤数。
     */
    private int maxSteps = 10;

    /**
     * 关联的会话标识，用于流式场景下恢复上下文。
     */
    private String sessionId;

    /**
     * 当前 Manus 执行的服务端 UUID，用于首个 SSE 事件告知页面本次执行身份。
     */
    private String executionId;

    /**
     * 会话存储组件，用于同步问题与待办快照。
     */
    private ManusSessionStore manusSessionStore;

    // llm 大模型
    /**
     * 底层大模型客户端。
     */
    private ChatClient chatClient;

    // Memory 记忆模块
    /**
     * 当前会话内维护的消息历史。
     */
    private List<Message> messageList = new ArrayList<>();
    /**
     * 执行智能体的主要运行逻辑。
     *
     * @param userPrompt 用户输入的提示信息，不能为空。
     * @return 返回运行过程中每一步的结果，按行分隔。
     * @throws BusinessException 如果智能体当前状态不是空闲状态，抛出 AGENT_BUSY 异常；
     *                           如果用户输入为空，抛出 INVALID_PARAM 异常。
     */
    public String run(String userPrompt){
        validatePromptAndState(userPrompt);

        // 设置智能体状态为运行中
        this.state = AgentState.RUNNING;
        messageList.add(new UserMessage(userPrompt));

        // 执行循环逻辑
        List<String> results = new ArrayList<>();
        try {
            for(int i = 0; i < maxSteps && state == AgentState.RUNNING; i++){
                currentStep = i + 1;
                log.info("Agent {} executing step {}/{}", name, currentStep, maxSteps);
                String stepResult = step();
                String result = String.format("Step %d result: %s", currentStep, stepResult);
                results.add(result);
            }
            // 如果达到最大步数，设置状态为完成
            if(currentStep >= maxSteps && state == AgentState.RUNNING){
                state = AgentState.FINISHED;
                results.add(String.format("Agent {} reached max steps", name));
            }
        } catch (BusinessException e) {
            // 捕获异常并设置状态为错误
            state = AgentState.ERROR;
            log.error(e.getMessage());
            return String.format("Agent %s error: %s", name, e.getMessage());
        } finally {
            // 清理资源
            cleanup();
        }

        // 返回所有步骤的结果
        return String.join("\n", results);
    }

    /**
     * 以 SSE 方式启动智能体执行，并持续向前端推送过程事件。
     */
    public SseEmitter runStream(String userPrompt){
        validatePromptAndState(userPrompt);
        SseEmitter sseEmitter = new SseEmitter(300000L);
        this.state = AgentState.RUNNING;
        messageList.add(new UserMessage(userPrompt));

        return executeStreamLoop(sseEmitter, false);
    }

    /**
     * 在补充完用户输入后继续流式执行未完成的会话。
     */
    public SseEmitter resumeStream() {
        validateResumeState();
        SseEmitter sseEmitter = new SseEmitter(300000L);
        this.state = AgentState.RUNNING;
        return executeStreamLoop(sseEmitter, true);
    }

    /**
     * 执行流式循环并在过程中分发问题、待办和错误事件。
     */
    private SseEmitter executeStreamLoop(SseEmitter sseEmitter, boolean resumePendingStep) {
        CompletableFuture.runAsync(() -> {
            boolean activateSession = manusSessionStore != null && StringUtils.hasText(sessionId);
            ManusSessionStore.TodoSnapshotListener todoListener = null;
            try {
                if (activateSession) {
                    manusSessionStore.activateSession(sessionId, executionId);
                    if (StringUtils.hasText(executionId)) {
                        if (!manusSessionStore.isCurrentExecution(sessionId, executionId)) {
                            throw new BusinessException(
                                    ErrorCode.CHAT_SESSION_CONFLICT,
                                    "Manus 执行已被新的执行中断"
                            );
                        }
                        sseEmitter.send(SseEmitter.event()
                                .name("execution")
                                .data(new ExecutionEventPayload(executionId)));
                        todoListener = snapshot -> sendTodoEvent(sseEmitter, snapshot);
                        manusSessionStore.registerTodoSnapshotListener(executionId, todoListener);
                        TodoSnapshot existingSnapshot = manusSessionStore.getTodoSnapshot(sessionId, executionId);
                        if (existingSnapshot != null) {
                            sendTodoEvent(sseEmitter, existingSnapshot);
                        }
                    }
                }
                for(int i = 0; i < maxSteps && state == AgentState.RUNNING; i++){
                    currentStep = i + 1;
                    log.info("Agent {} executing step {}/{}", name, currentStep, maxSteps);
                    String stepResult = (resumePendingStep && i == 0) ? resumeStep() : step();
                    String result = String.format("Step %d result: %s", currentStep, stepResult);
                    if (activateSession) {
                        manusSessionStore.saveAssistantMessage(sessionId, executionId, result);
                    }
                    sseEmitter.send(SseEmitter.event().name("message").data(result));
                }
                // 如果达到最大步数，设置状态为完成
                if(currentStep >= maxSteps && state == AgentState.RUNNING){
                    state = AgentState.FINISHED;
                    sseEmitter.send(SseEmitter.event()
                            .name("message")
                            .data(String.format("Agent %s reached max steps", name)));
                }
                if (state != AgentState.FINISHED) {
                    throw new BusinessException(
                            ErrorCode.CHAT_SESSION_CONFLICT,
                            "Manus 执行已被新的执行中断"
                    );
                }
                if (activateSession) {
                    // Todo 是 Manus 对任务完成度的结构化事实。只要仍存在 pending 或 in_progress，
                    // 就不能把执行写成 COMPLETED；统一抛给下方异常分支落为 FAILED，防止“工具失败、
                    // 模型停止继续调用”被页面误判为任务成功。
                    TodoSnapshot finalTodoSnapshot = manusSessionStore.getTodoSnapshot(sessionId, executionId);
                    boolean hasUnfinishedTodo = finalTodoSnapshot != null
                            && finalTodoSnapshot.items() != null
                            && finalTodoSnapshot.items().stream()
                            .anyMatch(item -> !"completed".equalsIgnoreCase(item.status()));
                    if (hasUnfinishedTodo) {
                        log.warn("Manus 模型已停止但仍有未完成 Todo，执行按失败收口，sessionId={}，executionId={}",
                                sessionId, executionId);
                        throw new BusinessException(ErrorCode.AGENT_RUN_FAILED, "Manus 仍有未完成任务");
                    }
                    manusSessionStore.completeExecution(sessionId, executionId);
                }
                sseEmitter.send(SseEmitter.event().name("done").data("[DONE]"));
                sseEmitter.complete();
            } catch (PendingUserQuestionException e) {
                if (activateSession
                        && !manusSessionStore.isCurrentExecution(sessionId, executionId)) {
                    // 同一 chatId 已由新 execution 接管时，旧连接不能再切回等待态或发送问题事件。
                    state = AgentState.ERROR;
                    BusinessException conflict = new BusinessException(
                            ErrorCode.CHAT_SESSION_CONFLICT,
                            "Manus 执行已被新的执行中断"
                    );
                    log.info("旧 Manus 执行的问题事件已被丢弃，sessionId={}，executionId={}",
                            sessionId, executionId);
                    sendErrorEvent(sseEmitter, formatErrorMessage(conflict));
                    sseEmitter.completeWithError(conflict);
                } else {
                    state = AgentState.WAITING_FOR_USER_INPUT;
                    sendQuestionEvent(sseEmitter, e);
                    sseEmitter.complete();
                }
            } catch (BusinessException e) {
                state = AgentState.ERROR;
                log.error("Agent {} runStream failed: {}", name, e.getMessage(), e);
                if (activateSession) {
                    manusSessionStore.failExecution(sessionId, executionId, e.getMessage());
                }
                sendErrorEvent(sseEmitter, formatErrorMessage(e));
                sseEmitter.completeWithError(e);
            } catch (Exception e) {
                state = AgentState.ERROR;
                log.error("Agent {} runStream unexpected error", name, e);
                if (activateSession) {
                    manusSessionStore.failExecution(sessionId, executionId, e.getMessage());
                }
                sendErrorEvent(sseEmitter, String.format("Agent %s error: %s", name, e.getMessage()));
                sseEmitter.completeWithError(e);
            } finally {
                if (activateSession && todoListener != null) {
                    manusSessionStore.unregisterTodoSnapshotListener(executionId, todoListener);
                }
                if (activateSession) {
                    manusSessionStore.clearActiveSession();
                }
                if (state != AgentState.WAITING_FOR_USER_INPUT) {
                    cleanup();
                }
            }
        });

        sseEmitter.onTimeout(()->{
            this.state = AgentState.ERROR;
            if (manusSessionStore != null && StringUtils.hasText(sessionId)) {
                manusSessionStore.failExecution(sessionId, executionId, "Manus 流式执行超时");
            }
            this.cleanup();
                log.warn("Agent {} runStream timed out", name);
                sendErrorEvent(sseEmitter, String.format("Agent %s error: runStream timed out", name));
        });

        sseEmitter.onCompletion(()->{
            // onCompletion 由 Servlet 容器线程触发，可能与工作线程的 FAILED 持久化并发。
            // 这里只记录连接完成，不清理内存会话；执行线程 finally 或超时回调会在数据库终态
            // 写入完成后统一 cleanup，避免先移除会话导致 failExecution 静默失效。
            log.info("Agent {} SSE connection completed, currentState={}，sessionId={}，executionId={}",
                    name, state, sessionId, executionId);
        });

        return sseEmitter;
    }

    /**
     * 校验输入提示词和当前智能体状态是否合法。
     */
    private void validatePromptAndState(String userPrompt) {
        if(this.state != AgentState.IDLE){
            throw new BusinessException(ErrorCode.AGENT_BUSY);
        }
        if (!StringUtils.hasText(userPrompt)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "userPrompt不能为空");
        }
    }

    /**
     * 校验当前智能体是否允许继续执行。
     */
    private void validateResumeState() {
        if (this.state != AgentState.WAITING_FOR_USER_INPUT) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "当前会话不处于待回答状态");
        }
    }

    /**
     * 将业务异常转换为与普通 Agent 输出一致的前端错误文本。
     *
     * @param e 已包含稳定业务语义的异常
     * @return 带 Agent 名称的错误消息
     */
    private String formatErrorMessage(BusinessException e) {
        return String.format("Agent %s error: %s", name, e.getMessage());
    }

    /**
     * 尝试发送命名为 {@code error} 的 SSE 事件。
     *
     * <p>发送失败通常意味着浏览器已经断开，此处只记录日志，避免覆盖真正的业务异常。</p>
     *
     * @param sseEmitter 当前流式连接
     * @param errorMessage 可展示的错误信息
     */
    private void sendErrorEvent(SseEmitter sseEmitter, String errorMessage) {
        try {
            sseEmitter.send(SseEmitter.event().name("error").data(errorMessage));
        } catch (IOException ioException) {
            log.warn("Agent {} failed to send error event: {}", name, ioException.getMessage(), ioException);
        }
    }

    /**
     * 向前端推送待回答问题事件。
     */
    private void sendQuestionEvent(SseEmitter sseEmitter, PendingUserQuestionException exception) {
        try {
            Object payload = exception.getQuestions();
            if (manusSessionStore != null
                    && StringUtils.hasText(sessionId)
                    && StringUtils.hasText(executionId)) {
                List<ManusSessionStore.PendingQuestion> pendingQuestions =
                        manusSessionStore.getPendingQuestions(sessionId, executionId);
                if (pendingQuestions != null) {
                    payload = pendingQuestions;
                }
            }
            sseEmitter.send(SseEmitter.event()
                    .name("question")
                    .data(new QuestionEventPayload(executionId, payload)));
        } catch (IOException ioException) {
            log.warn("Agent {} failed to send question event: {}", name, ioException.getMessage(), ioException);
        }
    }

    /**
     * 向前端推送待办快照事件。
     */
    private void sendTodoEvent(SseEmitter sseEmitter, TodoSnapshot snapshot) {
        try {
            TodoEventPayload payload = new TodoEventPayload(executionId, snapshot);
            if (payload != null) {
                sseEmitter.send(SseEmitter.event().name("todo").data(payload));
            }
        } catch (IOException ioException) {
            log.warn("Agent {} failed to send todo event: {}", name, ioException.getMessage(), ioException);
        }
    }

    /**
     * 从当前会话读取最新待办快照并构造事件载荷。
     *
     * @return 当前执行对应的待办事件；会话未激活或尚无快照时返回 {@code null}
     */
    TodoEventPayload buildTodoEventPayload() {
        if (manusSessionStore == null || !StringUtils.hasText(sessionId)) {
            return null;
        }
        TodoSnapshot snapshot = manusSessionStore.getTodoSnapshot(sessionId);
        if (snapshot == null) {
            return null;
        }
        return new TodoEventPayload(executionId, snapshot);
    }

    /**
     * 执行单步逻辑，交由子类给出具体实现。
     */
    public abstract String step();

    /**
     * 恢复未完成步骤，默认复用单步执行逻辑。
     */
    protected String resumeStep() {
        return step();
    }

    /**
     * 执行结束后的清理钩子，供子类按需扩展。
     */
    protected void cleanup(){
        // 清理资源，重置状态等
        if ((state == AgentState.FINISHED || state == AgentState.ERROR)
                && manusSessionStore != null
                && StringUtils.hasText(sessionId)) {
            manusSessionStore.removeSession(sessionId, executionId);
        }
    }

    /**
     * 待回答问题事件载荷，携带 executionId 和问题内容，页面可过滤旧执行迟到的事件。
     */
    public record QuestionEventPayload(String executionId, Object questions) {}

    /**
     * 待办快照事件载荷，同时携带 executionId 和当前任务清单，页面据此忽略已经被新执行
     * 替换的旧连接事件。
     */
    public record TodoEventPayload(String executionId, TodoSnapshot todo) {}

    /**
     * Manus 执行标识事件，页面可用该标识关联后续 Todo、问题和终态。
     */
    public record ExecutionEventPayload(String executionId) {}

}
