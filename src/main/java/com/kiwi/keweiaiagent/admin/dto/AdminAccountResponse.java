package com.kiwi.keweiaiagent.admin.dto;

import com.kiwi.keweiaiagent.account.entity.UserAccountDO;
import com.kiwi.keweiaiagent.account.model.AccountRole;
import com.kiwi.keweiaiagent.account.model.AccountStatus;

import java.time.LocalDateTime;

/**
 * Console 账号列表项。响应只包含管理页面需要的身份和时间字段，不包含规范化账号、密码摘要、
 * Session、CSRF Token 或聊天数据。version 只作为并发更新令牌，不用于展示。
 */
public record AdminAccountResponse(
        Long accountId,
        String account,
        AccountRole role,
        AccountStatus status,
        LocalDateTime registerTime,
        LocalDateTime lastLoginTime,
        boolean currentAccount,
        Long version
) {

    public static AdminAccountResponse from(UserAccountDO account, Long currentAccountId) {
        return new AdminAccountResponse(
                account.getId(),
                account.getAccount(),
                account.getRole(),
                account.getStatus(),
                account.getRegisterTime(),
                account.getLastLoginTime(),
                account.getId().equals(currentAccountId),
                account.getVersion()
        );
    }
}
