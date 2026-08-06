package com.kiwi.keweiaiagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kiwi.keweiaiagent.account.service.UserAccountService;
import com.kiwi.keweiaiagent.common.BaseResponse;
import com.kiwi.keweiaiagent.exception.ErrorCode;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Web 安全配置。公开接口、登录接口、admin 接口和普通受限接口在此统一声明，业务控制器
 * 不再自行判断前端传入的角色。认证使用 Redis HttpSession，修改请求使用会话 CSRF Token。
 */
@Configuration
@EnableWebSecurity
@Slf4j
public class SecurityConfig {

    /**
     * BCrypt 使用强度 12 保存用户密码和初始 admin 密码，数据库只接收编码后的摘要。
     */
    @Bean
    public static PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * 统一创建基于 HttpSession 的 CSRF Token 仓储。安全过滤链使用该仓储校验请求，认证会话服务
     * 在登录或注册轮换 Session ID 后使用同一仓储删除旧 Token，确保登录前 Token 不会继续有效。
     */
    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository csrfTokenRepository = new HttpSessionCsrfTokenRepository();
        csrfTokenRepository.setHeaderName("X-CSRF-TOKEN");
        return csrfTokenRepository;
    }

    /**
     * 建立统一接口权限、CSRF、CORS、异常响应和会话策略。原始 HTTP 请求仍按接口路径完成
     * 登录态与 CSRF 校验；Servlet 容器在 SseEmitter 建立后触发的 ASYNC、ERROR 二次分派
     * 不会再次进入业务控制器，因此只放行这两类服务端分派，避免已经认证的 SSE 连接被
     * 二次授权误判为匿名请求。默认登录页、HTTP Basic 和框架默认退出端点全部关闭。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            UserAccountService userAccountService,
            ObjectMapper objectMapper,
            CsrfTokenRepository csrfTokenRepository,
            @Value("${app.security.session.absolute-timeout:7d}") Duration absoluteTimeout
    ) throws Exception {
        // 过滤器仅加入 Spring Security 链，不注册为通用 Servlet Filter，避免一次请求重复执行。
        SessionAuthenticationFilter sessionAuthenticationFilter = new SessionAuthenticationFilter(
                userAccountService,
                objectMapper,
                absoluteTimeout
        );

        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(context -> context.requireExplicitSave(true))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize
                        // ASYNC、ERROR 类型只能由 Servlet 容器在原请求完成安全校验后触发，
                        // 客户端无法通过请求参数伪造 DispatcherType，因此不会绕过入口鉴权。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/auth/csrf",
                                "/auth/register",
                                "/auth/login",
                                "/health",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/doc.html",
                                "/webjars/**"
                        ).permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/auth/logout", "/auth/me", "/ai/**", "/chat/**").authenticated()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeSecurityError(response, ErrorCode.UNAUTHENTICATED, objectMapper))
                        .accessDeniedHandler((request, response, exception) ->
                                writeSecurityError(response, ErrorCode.FORBIDDEN, objectMapper))
                )
                .addFilterBefore(sessionAuthenticationFilter, AnonymousAuthenticationFilter.class);
        log.info("安全过滤链已允许 SSE 的 ASYNC、ERROR 服务端二次分派，原始接口继续执行登录态与 CSRF 校验");
        return http.build();
    }

    private void writeSecurityError(
            HttpServletResponse response,
            ErrorCode errorCode,
            ObjectMapper objectMapper
    ) throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), BaseResponse.fail(errorCode));
    }
}
