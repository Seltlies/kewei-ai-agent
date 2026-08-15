package com.kiwi.keweiaiagent.agent.todo;

/**
 * 前端展示用的单条 Agent 待办项。
 *
 * @param id 本次执行内稳定的待办标识
 * @param content 待办内容
 * @param status 社区工具定义的执行状态名称
 */
public record TodoItem(String id, String content, String status) {
}
