package com.kiwi.keweiaiagent.agent.model;

/**
 * Agent 生命周期状态。
 *
 * <p>状态用于限制重复执行并向 SSE 调用方描述当前阶段；其中
 * {@link #WAITING_FOR_USER_INPUT} 表示本轮执行暂停，但执行上下文仍需保留。</p>
 */
public enum AgentState {

    IDLE,

    RUNNING,

    WAITING_FOR_USER_INPUT,

    FINISHED,

    ERROR
}
