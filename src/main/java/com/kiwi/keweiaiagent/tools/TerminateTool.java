package com.kiwi.keweiaiagent.tools;


import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Agent 显式终止工具，模型在任务完成或确认无法继续时调用它结束 ReAct 循环。
 */
@Component
public class TerminateTool {

    /**
     * 返回终止确认文本，ToolCallAgent 会根据工具名称把状态切换为 FINISHED。
     *
     * @return 固定任务结束提示
     */
    @Tool(description = """
            Terminate the interaction when the request is met or if the assistant cannot proceed further with the task.
            When you have finished tall the tasks, call this tool to end the work.
            """)
    public String doTerminate(){
        return "任务结束";
    }
}
