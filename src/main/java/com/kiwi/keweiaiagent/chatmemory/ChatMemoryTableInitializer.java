package com.kiwi.keweiaiagent.chatmemory;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * MySQL 聊天记忆表初始化器。仅当配置明确选择 MySQL 聊天记忆时执行建表语句；所依赖的
 * JdbcTemplate 必须由 MySQL 数据源配置提供，数据源缺失时直接终止启动，避免静默切换存储。
 */
@Component
@ConditionalOnProperty(prefix = "app.chat-memory", name = "type", havingValue = "mysql")
@Slf4j
public class ChatMemoryTableInitializer {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 显式限定注入 MySQL JdbcTemplate，避免主数据源的 PostgreSQL JdbcTemplate 被优先选中并执行
     * MySQL 专用 DDL。Qualifier 必须放在构造器参数上，确保 Spring 运行时能够读取限定信息。
     */
    public ChatMemoryTableInitializer(
            @Qualifier("mysqlChatMemoryJdbcTemplate") JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 创建现有聊天记忆表。SQL 使用 IF NOT EXISTS 保证重复启动安全，执行完成后记录结构初始化日志。
     */
    @PostConstruct
    public void init() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ai_chat_memory_message (
                    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
                    conversation_id VARCHAR(128) NOT NULL COMMENT '会话ID',
                    payload_json LONGTEXT NOT NULL COMMENT '消息JSON',
                    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    PRIMARY KEY (id),
                    KEY idx_conversation_id (conversation_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI聊天记忆消息表'
                """);
        log.info("MySQL 聊天记忆表结构初始化完成，table=ai_chat_memory_message");
    }
}
