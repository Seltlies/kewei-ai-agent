package com.kiwi.keweiaiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用启动入口。
 *
 * <p>{@link SpringBootApplication} 同时启用自动配置、组件扫描和配置类注册，
 * Spring 会从当前包向下发现 Controller、Service、Mapper 与各类基础设施 Bean。</p>
 */
@SpringBootApplication
public class KeweiAiAgentApplication {

    /**
     * 创建 Spring 应用上下文并启动内嵌 Web 容器。
     *
     * @param args 命令行启动参数，会由 Spring Boot 解析为外部化配置
     */
    public static void main(String[] args) {
        SpringApplication.run(KeweiAiAgentApplication.class, args);
    }

}
