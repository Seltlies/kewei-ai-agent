package com.kiwi.keweiaiagent.account.model;

/**
 * 账号角色。角色值直接持久化到 MySQL，并由安全过滤器转换为 Spring Security 权限。
 */
public enum AccountRole {
    USER,
    ADMIN
}
