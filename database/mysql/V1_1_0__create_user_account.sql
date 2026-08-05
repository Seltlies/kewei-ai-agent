-- 账号体系第一批数据库结构。
-- 本文件需要在目标 MySQL 8.0.16 及以上版本执行；当前仅生成，未自动执行。
CREATE TABLE IF NOT EXISTS ai_system_lock (
    lock_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '全局业务锁名称',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (lock_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='跨实例业务初始化锁表';

-- 固定锁记录由事务内 SELECT ... FOR UPDATE 独占，保证多实例只能串行初始化首个管理员。
INSERT IGNORE INTO ai_system_lock (lock_name) VALUES ('BOOTSTRAP_ADMIN');

CREATE TABLE IF NOT EXISTS ai_user_account (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '账号主键',
    account VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '去除首尾空格后保留原始大小写的展示账号',
    normalized_account VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '英文字母小写化后的登录账号',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码摘要',
    role VARCHAR(16) NOT NULL COMMENT '账号角色：USER 或 ADMIN',
    status VARCHAR(16) NOT NULL COMMENT '账号状态：ACTIVE 或 DISABLED',
    register_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '注册时间',
    last_login_time DATETIME(3) NULL COMMENT '最后成功登录时间',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '并发更新版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_user_account_normalized_account (normalized_account),
    KEY idx_ai_user_account_role_status (role, status),
    CONSTRAINT chk_ai_user_account_display_length CHECK (CHAR_LENGTH(account) BETWEEN 3 AND 32),
    CONSTRAINT chk_ai_user_account_normalized_length CHECK (CHAR_LENGTH(normalized_account) BETWEEN 3 AND 32),
    CONSTRAINT chk_ai_user_account_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT chk_ai_user_account_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='AI 用户账号表';
