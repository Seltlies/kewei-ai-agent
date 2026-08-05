package com.kiwi.keweiaiagent.auth.dto;

import com.kiwi.keweiaiagent.account.entity.UserAccountDO;
import com.kiwi.keweiaiagent.account.model.AccountRole;
import com.kiwi.keweiaiagent.account.model.AccountStatus;
import com.kiwi.keweiaiagent.security.AuthenticatedAccount;

/**
 * 当前账号响应，只暴露页面需要的身份字段，不包含密码摘要、认证时间和 Redis Session ID。
 */
public record AccountResponse(
        Long accountId,
        String account,
        AccountRole role,
        AccountStatus status
) {

    public static AccountResponse from(UserAccountDO account) {
        return new AccountResponse(account.getId(), account.getAccount(), account.getRole(), account.getStatus());
    }

    public static AccountResponse from(AuthenticatedAccount account) {
        return new AccountResponse(account.accountId(), account.account(), account.role(), account.status());
    }
}
