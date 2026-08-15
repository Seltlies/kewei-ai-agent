package com.kiwi.keweiaiagent.security;

/**
 * 认证会话属性名称。Redis HttpSession 只保存账号主键和认证时间，角色与状态每次从 MySQL
 * 读取，确保管理端修改角色后立即影响已有会话。
 */
public final class AuthSessionConstants {

    public static final String ACCOUNT_ID = "AUTH_ACCOUNT_ID";
    public static final String AUTHENTICATED_AT = "AUTH_AUTHENTICATED_AT";

    /** 常量容器不保存实例状态，禁止创建对象。 */
    private AuthSessionConstants() {
    }
}
