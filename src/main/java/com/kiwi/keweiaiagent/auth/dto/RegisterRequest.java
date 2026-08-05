package com.kiwi.keweiaiagent.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 注册请求。复杂账号和密码规则在账号领域服务中再次校验，避免只依赖前端或注解校验。
 */
public record RegisterRequest(
        @NotBlank(message = "请输入账号") String account,
        @NotBlank(message = "请输入密码") String password,
        @NotBlank(message = "请再次输入密码") String confirmPassword
) {
}
