package com.kiwi.keweiaiagent.agent.model;

/**
 * Manus 执行持久化状态。状态值与 ai_agent_execution 表检查约束保持一致。
 */
public enum ManusExecutionStatus {
    RUNNING,
    WAITING_USER,
    COMPLETED,
    FAILED,
    INTERRUPTED
}
