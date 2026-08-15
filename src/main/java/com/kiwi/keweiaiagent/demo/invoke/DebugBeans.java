package com.kiwi.keweiaiagent.demo.invoke;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import java.util.Arrays;

/**
 * 启动期调试组件，用于输出当前 Spring 容器中实际注册的 ChatModel Bean 名称。
 */
@Component
public class DebugBeans implements CommandLineRunner {
    private final ApplicationContext ctx;
    /** @param ctx Spring 应用上下文 */
    public DebugBeans(ApplicationContext ctx) { this.ctx = ctx; }

    /**
     * 应用启动后查询 ChatModel Bean，帮助确认模型自动配置是否生效。
     *
     * @param args 应用启动参数
     */
    @Override
    public void run(String... args) {
        String[] names = ctx.getBeanNamesForType(org.springframework.ai.chat.model.ChatModel.class);
        System.out.println("ChatModel beans = " + Arrays.toString(names));
    }
}
