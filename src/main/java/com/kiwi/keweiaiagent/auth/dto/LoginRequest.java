package com.kiwi.keweiaiagent.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求，只接收账号和密码，不允许客户端提交角色、状态或账号 ID。
 */
public record LoginRequest(
        @NotBlank(message = "请输入账号") String account,
        @NotBlank(message = "请输入密码") String password
) {
}
