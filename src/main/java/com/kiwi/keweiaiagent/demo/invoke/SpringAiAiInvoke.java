package com.kiwi.keweiaiagent.demo.invoke;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

//@Component
/**
 * Spring AI 模型调用演示，仅在 dashscope Profile 下且重新启用 Component 后运行。
 */
@Profile("dashscope")
public class SpringAiAiInvoke implements CommandLineRunner {

    @Resource(name = "dashScopeChatModel")
    private ChatModel dashscopeChatModel;

    /**
     * 应用启动后调用一次 DashScope ChatModel 并输出模型文本。
     *
     * @param args 应用启动参数
     */
    @Override
    public void run(String... args) throws Exception {
        AssistantMessage msg = dashscopeChatModel.call(new Prompt("你好我是kiw"))
                .getResult()
                .getOutput();
        System.out.println(msg.getText());
    }
}
