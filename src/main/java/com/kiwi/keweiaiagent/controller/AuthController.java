package com.kiwi.keweiaiagent.controller;

import com.kiwi.keweiaiagent.auth.dto.AccountResponse;
import com.kiwi.keweiaiagent.auth.dto.CsrfResponse;
import com.kiwi.keweiaiagent.auth.dto.LoginRequest;
import com.kiwi.keweiaiagent.auth.dto.RegisterRequest;
import com.kiwi.keweiaiagent.auth.service.AuthService;
import com.kiwi.keweiaiagent.auth.service.AuthSessionService;
import com.kiwi.keweiaiagent.security.AuthenticatedAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号认证 REST 接口，提供 CSRF Token、注册、登录、退出和当前账号查询。
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthSessionService authSessionService;

    /**
     * 创建或读取当前匿名/登录会话的 CSRF Token，供前端后续 POST 请求通过请求头回传。
     */
    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken csrfToken) {
        return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getParameterName(), csrfToken.getToken());
    }

    /**
     * 注册普通账号并自动登录当前设备。
     */
    @PostMapping("/register")
    public AccountResponse register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        return authService.register(request, servletRequest, servletResponse);
    }

    /**
     * 使用账号密码登录当前设备。
     */
    @PostMapping("/login")
    public AccountResponse login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        return authService.login(request, servletRequest, servletResponse);
    }

    /**
     * 查询安全过滤器解析出的最新账号、角色和状态。
     */
    @GetMapping("/me")
    public AccountResponse me(@AuthenticationPrincipal AuthenticatedAccount account) {
        return AccountResponse.from(account);
    }

    /**
     * 仅注销当前设备会话，其他设备会话保持有效。
     */
    @PostMapping("/logout")
    public void logout(
            @AuthenticationPrincipal AuthenticatedAccount account,
            HttpServletRequest servletRequest
    ) {
        authSessionService.invalidateCurrentSession(servletRequest, account.accountId());
    }
}
