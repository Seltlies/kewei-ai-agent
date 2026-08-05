package com.kiwi.keweiaiagent.security;

import com.kiwi.keweiaiagent.account.model.AccountRole;
import com.kiwi.keweiaiagent.account.model.AccountStatus;

import java.io.Serializable;

/**
 * 当前请求的可信账号身份。该对象由后端根据 HttpSession 中的账号 ID 和 MySQL 最新账号数据
 * 构造，禁止使用前端提交的账号、角色或状态创建认证上下文。
 */
public record AuthenticatedAccount(
        Long accountId,
        String account,
        AccountRole role,
        AccountStatus status
) implements Serializable {
}
