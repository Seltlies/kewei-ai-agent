package com.kiwi.keweiaiagent.auth.service;

import com.kiwi.keweiaiagent.account.entity.UserAccountDO;
import com.kiwi.keweiaiagent.account.service.UserAccountService;
import com.kiwi.keweiaiagent.auth.dto.AccountResponse;
import com.kiwi.keweiaiagent.auth.dto.LoginRequest;
import com.kiwi.keweiaiagent.auth.dto.RegisterRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 认证应用服务，编排账号领域操作和 Redis HttpSession 操作，实现注册并登录和账号密码登录。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserAccountService userAccountService;
    private final AuthSessionService authSessionService;

    /**
     * 创建普通账号并立即为当前设备建立认证会话。
     */
    public AccountResponse register(
            RegisterRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        UserAccountDO account = userAccountService.registerUser(
                request.account(),
                request.password(),
                request.confirmPassword()
        );
        authSessionService.establishSession(servletRequest, servletResponse, account);
        return AccountResponse.from(account);
    }

    /**
     * 校验账号密码和停用状态，成功后为当前设备建立独立认证会话。
     */
    public AccountResponse login(
            LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        UserAccountDO account = userAccountService.authenticate(request.account(), request.password());
        authSessionService.establishSession(servletRequest, servletResponse, account);
        return AccountResponse.from(account);
    }
}
