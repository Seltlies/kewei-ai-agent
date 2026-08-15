package com.kiwi.keweiaiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 兼容性配置，为未使用 Spring Boot 默认 ObjectMapper 的调用点提供统一模块发现能力。
 */
@Configuration
public class JacksonCompatibilityConfig {

    /**
     * 创建能够处理 Java 时间等扩展类型的 ObjectMapper。
     *
     * @return 已自动注册 classpath 模块的 JSON 映射器
     */
    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
