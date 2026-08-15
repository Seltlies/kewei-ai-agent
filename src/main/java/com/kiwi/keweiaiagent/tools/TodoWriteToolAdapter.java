package com.kiwi.keweiaiagent.tools;

import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 社区 TodoWriteTool 的 Spring AI 注解适配器，使 Manus 能以统一工具名维护结构化任务清单。
 */
@Component
public class TodoWriteToolAdapter {

    private final TodoWriteTool delegate;

    /** @param delegate 社区版 Todo 工具实现 */
    public TodoWriteToolAdapter(TodoWriteTool delegate) {
        this.delegate = delegate;
    }

    /**
     * 用完整列表覆盖当前执行的 Todo 状态，由委托工具校验状态转换并通知监听器。
     *
     * @param todos 本轮完整待办项
     * @return 委托工具结果或参数校验消息
     */
    @Tool(name = "TodoWrite", description = """
            Use this tool to create and manage a structured task list for the current task.
            Provide a todos array where each item includes content, status, and activeForm.
            Use it for complex multi-step work before executing other tools.
            """, returnDirect = false)
    public String todoWrite(
            @ToolParam(description = "Structured todo items for the current task") List<TodoWriteTool.Todos.TodoItem> todos
    ) {
        try {
            return delegate.todoWrite(new TodoWriteTool.Todos(todos));
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }
}
