package com.kiwi.keweiaiagent.agent.todo;

import org.springaicommunity.agent.tools.TodoWriteTool;

import java.util.ArrayList;
import java.util.List;

/**
 * 社区版 TodoWriteTool 数据到项目稳定响应模型的转换器。
 *
 * <p>隔离第三方工具模型可以避免其字段变化直接影响前端协议，并在转换时补充稳定的待办项编号。</p>
 */
public final class CommunityTodoMapper {

    /** 工具类不保存状态，禁止创建实例。 */
    private CommunityTodoMapper() {
    }

    /**
     * 将社区工具的待办快照转换为可序列化的项目模型。
     *
     * @param todos 社区工具返回的待办集合，允许为 {@code null}
     * @return 非空的待办快照；输入为空时返回空列表
     */
    public static TodoSnapshot toSnapshot(TodoWriteTool.Todos todos) {
        List<TodoItem> items = new ArrayList<>();
        List<TodoWriteTool.Todos.TodoItem> communityItems = todos == null ? List.of() : todos.todos();
        for (int i = 0; i < communityItems.size(); i++) {
            TodoWriteTool.Todos.TodoItem item = communityItems.get(i);
            items.add(new TodoItem(
                    "todo_" + (i + 1),
                    item.content(),
                    item.status().name()
            ));
        }
        return new TodoSnapshot(items);
    }
}
