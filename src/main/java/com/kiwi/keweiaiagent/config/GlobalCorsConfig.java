package com.kiwi.keweiaiagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * 全局跨域配置。认证使用 Cookie 后不能再允许任意来源携带凭据，因此只接受环境配置中明确
 * 列出的前端 Origin，并只开放业务实际使用的请求头和方法。
 */
@Configuration
public class GlobalCorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public GlobalCorsConfig(@Value("${app.cors.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
    }

    /**
     * 为全部后端接口应用明确来源 CORS 规则，允许认证 Cookie 和 CSRF 请求头跨本地前后端端口发送。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("Accept", "Content-Type", "X-CSRF-TOKEN", "X-XSRF-TOKEN")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
