-- 第二批页面权限与聊天数据隔离数据库结构。
-- 目标数据库为 MySQL 8.0.16 及以上版本；本文件当前仅生成，未经批准不得执行。

-- 会话由服务端生成 session_id，并通过 user_id 固定归属于当前登录账号。
CREATE TABLE IF NOT EXISTS ai_chat_session (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会话主键',
    session_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '服务端生成的对外会话标识',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '所属账号主键',
    app_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '会话所属应用编码',
    title VARCHAR(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '首次有效消息生成的固定标题',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后活跃时间',
    version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '并发更新版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_chat_session_session_id (session_id),
    UNIQUE KEY uk_ai_chat_session_owner (session_id, user_id),
    KEY idx_ai_chat_session_user_update (user_id, update_time, id),
    KEY idx_ai_chat_session_user_app_update (user_id, app_code, update_time, id),
    CONSTRAINT fk_ai_chat_session_user
        FOREIGN KEY (user_id) REFERENCES ai_user_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_ai_chat_session_title_length CHECK (CHAR_LENGTH(title) BETWEEN 1 AND 30)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='AI 聊天会话表';

-- 保证全新环境无需依赖应用启动初始化器，也能够继续建立会话与消息之间的外键。
CREATE TABLE IF NOT EXISTS ai_chat_memory_message (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '消息主键',
    conversation_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '服务端会话标识',
    payload_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Spring AI 消息 JSON',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_conversation_id (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 聊天记忆消息表';

-- 现有表最初使用不区分大小写的会话标识排序规则；统一改为二进制比较，防止不同标识被错误视为相同。
ALTER TABLE ai_chat_memory_message
    MODIFY COLUMN conversation_id VARCHAR(128)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '服务端会话标识';

-- 迁移脚本允许在人工复核时重复执行：仅在外键尚不存在时建立消息到会话的约束。
SET @chat_message_fk_exists = (
    SELECT COUNT(*)
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_chat_memory_message'
      AND CONSTRAINT_NAME = 'fk_ai_chat_memory_message_session'
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @chat_message_fk_sql = IF(
    @chat_message_fk_exists = 0,
    'ALTER TABLE ai_chat_memory_message ADD CONSTRAINT fk_ai_chat_memory_message_session FOREIGN KEY (conversation_id) REFERENCES ai_chat_session (session_id) ON DELETE RESTRICT ON UPDATE RESTRICT',
    'SELECT 1'
);
PREPARE chat_message_fk_statement FROM @chat_message_fk_sql;
EXECUTE chat_message_fk_statement;
DEALLOCATE PREPARE chat_message_fk_statement;

-- 附件使用独立 UUID 作为对外标识，storage_path 只保存由服务端生成的相对存储位置。
CREATE TABLE IF NOT EXISTS ai_chat_attachment (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '附件主键',
    attachment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端生成的对外附件 UUID',
    session_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属会话标识',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '所属账号主键',
    original_name VARCHAR(255) NOT NULL COMMENT '仅用于展示的原始文件名',
    storage_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端生成的实际文件名',
    storage_path VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '相对附件根目录的存储位置',
    content_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '校验后的真实图片类型',
    file_size BIGINT UNSIGNED NOT NULL COMMENT '文件字节数',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '上传时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_chat_attachment_attachment_id (attachment_id),
    UNIQUE KEY uk_ai_chat_attachment_storage_name (storage_name),
    KEY idx_ai_chat_attachment_owner_session (user_id, session_id, id),
    CONSTRAINT fk_ai_chat_attachment_session_owner
        FOREIGN KEY (session_id, user_id) REFERENCES ai_chat_session (session_id, user_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_ai_chat_attachment_content_type
        CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT chk_ai_chat_attachment_file_size
        CHECK (file_size BETWEEN 1 AND 10485760)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='AI 聊天附件表';

-- Manus 每次执行使用新的 execution_id；重启时只更新未完成状态，不自动恢复或重新执行任务。
CREATE TABLE IF NOT EXISTS ai_agent_execution (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '执行主键',
    execution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端生成的对外执行 UUID',
    session_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属会话标识',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '所属账号主键',
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '执行状态',
    task_payload LONGTEXT NOT NULL COMMENT '用于历史展示的任务载荷',
    todo_payload LONGTEXT NULL COMMENT '用于历史展示的 Todo 载荷',
    question_payload LONGTEXT NULL COMMENT '用于历史展示的补充问题载荷',
    reason VARCHAR(500) NULL COMMENT '失败或中断原因',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '状态更新时间',
    version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '并发更新版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_agent_execution_execution_id (execution_id),
    KEY idx_ai_agent_execution_owner_session (user_id, session_id, id),
    KEY idx_ai_agent_execution_status_update (status, update_time, id),
    CONSTRAINT fk_ai_agent_execution_session_owner
        FOREIGN KEY (session_id, user_id) REFERENCES ai_chat_session (session_id, user_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_ai_agent_execution_status
        CHECK (status IN ('RUNNING', 'WAITING_USER', 'COMPLETED', 'FAILED', 'INTERRUPTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Manus 执行记录表';
