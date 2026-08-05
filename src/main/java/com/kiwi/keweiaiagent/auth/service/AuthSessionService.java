package com.kiwi.keweiaiagent.auth.service;

import com.kiwi.keweiaiagent.account.entity.UserAccountDO;
import com.kiwi.keweiaiagent.security.AuthSessionConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;

/**
 * 认证会话服务，集中创建和注销 Redis HttpSession。每个设备持有独立 Session ID，因此新登录
 * 不会覆盖其他设备会话，退出也只影响当前请求携带的会话。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthSessionService {

    private static final int MAX_INACTIVE_INTERVAL_SECONDS = 24 * 60 * 60;

    private final CsrfTokenRepository csrfTokenRepository;

    /**
     * 登录或注册成功后轮换已有 Session ID，删除认证前 CSRF Token，写入账号 ID 和认证时间，
     * 并设置 24 小时不活跃期限。前端随后必须重新获取与认证后会话绑定的新 CSRF Token。
     */
    public void establishSession(
            HttpServletRequest request,
            HttpServletResponse response,
            UserAccountDO account
    ) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            session = request.getSession(true);
        } else {
            request.changeSessionId();
        }
        // changeSessionId 只轮换标识而保留 Session Attribute，必须显式删除登录前的 CSRF Token。
        csrfTokenRepository.saveToken(null, request, response);
        session.setMaxInactiveInterval(MAX_INACTIVE_INTERVAL_SECONDS);
        session.setAttribute(AuthSessionConstants.ACCOUNT_ID, account.getId());
        session.setAttribute(AuthSessionConstants.AUTHENTICATED_AT, System.currentTimeMillis());
        // Session ID 等同认证凭据，不写入日志，避免日志读取权限扩大后造成会话泄露。
        log.info("认证会话建立且旧 CSRF Token 已清理，accountId={}", account.getId());
    }

    /**
     * 注销当前设备的认证会话并清理当前请求安全上下文，不查询或删除其他设备的会话。
     */
    public void invalidateCurrentSession(HttpServletRequest request, Long accountId) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
            log.info("当前设备退出登录，accountId={}", accountId);
        }
        SecurityContextHolder.clearContext();
    }
}
