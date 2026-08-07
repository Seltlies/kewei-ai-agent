package com.kiwi.keweiaiagent.admin.dto;

import com.kiwi.keweiaiagent.account.model.AccountRole;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 角色调整请求。version 来自账号列表响应，用于检测页面读取后发生的并发角色或状态变化。
 */
public record UpdateAccountRoleRequest(
        @NotNull(message = "请选择目标角色") AccountRole role,
        @NotNull(message = "缺少账号版本") @PositiveOrZero(message = "账号版本不能为负数") Long version
) {
}
