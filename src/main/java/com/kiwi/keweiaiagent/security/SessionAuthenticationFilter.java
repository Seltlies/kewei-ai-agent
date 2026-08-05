package com.kiwi.keweiaiagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kiwi.keweiaiagent.account.entity.UserAccountDO;
import com.kiwi.keweiaiagent.account.service.UserAccountService;
import com.kiwi.keweiaiagent.common.BaseResponse;
import com.kiwi.keweiaiagent.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 服务端认证会话过滤器。过滤器只信任 Redis HttpSession 中的账号 ID，并在每次请求时读取
 * MySQL 最新角色。账号停用不会使已有会话失效，但登录入口会阻止停用账号建立新会话。
 */
@Slf4j
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    private final UserAccountService userAccountService;
    private final ObjectMapper objectMapper;
    private final Duration absoluteTimeout;

    public SessionAuthenticationFilter(
            UserAccountService userAccountService,
            ObjectMapper objectMapper,
            Duration absoluteTimeout
    ) {
        this.userAccountService = userAccountService;
        this.objectMapper = objectMapper;
        this.absoluteTimeout = absoluteTimeout;
    }

    /**
     * 校验认证会话的 7 天绝对期限并创建当前请求 SecurityContext。SSE 与 Manus 在连接建立时
     * 通过本过滤器认证，连接建立后不会因为会话到期而主动中断正在执行的请求。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Long accountId = readLongAttribute(session, AuthSessionConstants.ACCOUNT_ID);
        Long authenticatedAt = readLongAttribute(session, AuthSessionConstants.AUTHENTICATED_AT);
        if (accountId == null || authenticatedAt == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (System.currentTimeMillis() - authenticatedAt >= absoluteTimeout.toMillis()) {
            log.info("认证会话达到绝对有效期，accountId={}", accountId);
            session.invalidate();
            filterChain.doFilter(request, response);
            return;
        }

        UserAccountDO account;
        try {
            account = userAccountService.findById(accountId);
        } catch (RuntimeException exception) {
            // 过滤器先于 Controller 执行，数据库异常无法交给全局异常处理器，必须在此返回统一协议。
            log.error("认证会话读取账号失败，accountId={}", accountId, exception);
            SecurityContextHolder.clearContext();
            response.setStatus(ErrorCode.SYSTEM_ERROR.getHttpStatus().value());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), BaseResponse.fail(ErrorCode.SYSTEM_ERROR));
            return;
        }
        if (account == null) {
            log.warn("认证会话关联账号不存在，accountId={}", accountId);
            session.invalidate();
            filterChain.doFilter(request, response);
            return;
        }

        AuthenticatedAccount principal = new AuthenticatedAccount(
                account.getId(),
                account.getAccount(),
                account.getRole(),
                account.getStatus()
        );
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole().name()))
        );
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
        filterChain.doFilter(request, response);
    }

    private Long readLongAttribute(HttpSession session, String attributeName) {
        Object value = session.getAttribute(attributeName);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }
}
