package com.kiwi.keweiaiagent.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.kiwi.keweiaiagent.account.model.AccountRole;
import com.kiwi.keweiaiagent.account.model.AccountStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户账号持久化对象，对应 ai_user_account 表。展示账号与规范化账号分开保存，确保页面
 * 保留用户注册时的大小写，同时登录和唯一约束对英文字母大小写不敏感。
 */
@Data
@TableName("ai_user_account")
public class UserAccountDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("account")
    private String account;

    @TableField("normalized_account")
    private String normalizedAccount;

    @TableField("password_hash")
    private String passwordHash;

    @TableField("role")
    private AccountRole role;

    @TableField("status")
    private AccountStatus status;

    @TableField("register_time")
    private LocalDateTime registerTime;

    @TableField("last_login_time")
    private LocalDateTime lastLoginTime;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @Version
    @TableField("version")
    private Long version;
}
