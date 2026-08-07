package com.kiwi.keweiaiagent.admin.dto;

import com.kiwi.keweiaiagent.account.model.AccountStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 账号启停请求。version 参与目标账号更新条件，禁止旧页面覆盖其他管理员已经提交的新状态。
 */
public record UpdateAccountStatusRequest(
        @NotNull(message = "请选择目标状态") AccountStatus status,
        @NotNull(message = "缺少账号版本") @PositiveOrZero(message = "账号版本不能为负数") Long version
) {
}
