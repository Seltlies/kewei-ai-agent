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

    /**
     * 从持久化账号创建对外响应。
     *
     * @param account 数据库账号对象
     * @return 脱敏后的账号信息
     */
    public static AccountResponse from(UserAccountDO account) {
        return new AccountResponse(account.getId(), account.getAccount(), account.getRole(), account.getStatus());
    }

    /**
     * 从当前安全上下文中的认证快照创建对外响应。
     *
     * @param account 已认证账号快照
     * @return 脱敏后的账号信息
     */
    public static AccountResponse from(AuthenticatedAccount account) {
        return new AccountResponse(account.accountId(), account.account(), account.role(), account.status());
    }
}
