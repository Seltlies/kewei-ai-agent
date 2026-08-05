package com.kiwi.keweiaiagent.account.model;

/**
 * 账号状态。停用状态只阻止建立新的认证会话，不会主动注销已经存在的会话。
 */
public enum AccountStatus {
    ACTIVE,
    DISABLED
}
