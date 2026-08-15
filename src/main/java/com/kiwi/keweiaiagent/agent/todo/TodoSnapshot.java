package com.kiwi.keweiaiagent.agent.todo;

import java.util.List;

/**
 * 某一时刻完整的 Agent 待办列表，供 SSE 事件一次性刷新前端状态。
 *
 * @param items 按工具原始顺序排列的待办项
 */
public record TodoSnapshot(List<TodoItem> items) {
}
