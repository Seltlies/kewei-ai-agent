package com.kiwi.keweiaiagent.config;

import com.kiwi.keweiaiagent.chatmemory.FileBaseChatMemory;
import com.kiwi.keweiaiagent.chatmemory.MyRedisChatMemory;
import com.kiwi.keweiaiagent.chatmemory.MySqlChatMemory;
import com.kiwi.keweiaiagent.chatmemory.mapper.ChatMemoryMessageMapper;
import com.kiwi.keweiaiagent.chat.mapper.ChatSessionMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 聊天记忆存储策略配置。
 *
 * <p>通过 {@code app.chat-memory.type} 在 MySQL 与 Redis 实现间显式选择；
 * 当两者都未创建 Bean 时使用文件实现，保证 Spring 容器中始终只有一个默认 ChatMemory。</p>
 */
@Configuration
public class ChatMemoryConfig {

    /**
     * 创建持久化到 MySQL 的聊天记忆实现。
     *
     * @param chatMemoryMessageMapper 消息读写 Mapper
     * @param chatSessionMapper 会话归属和更新时间 Mapper
     * @return MySQL 聊天记忆
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.chat-memory", name = "type", havingValue = "mysql")
    public ChatMemory mysqlChatMemory(
            ChatMemoryMessageMapper chatMemoryMessageMapper,
            ChatSessionMapper chatSessionMapper
    ) {
        return new MySqlChatMemory(chatMemoryMessageMapper, chatSessionMapper);
    }

    /**
     * 创建基于 Redis List 的聊天记忆实现。
     *
     * @param stringRedisTemplate Redis 字符串操作模板
     * @param keyPrefix 隔离聊天记忆键的前缀
     * @return Redis 聊天记忆
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.chat-memory", name = "type", havingValue = "redis")
    public ChatMemory redisChatMemory(
            StringRedisTemplate stringRedisTemplate,
            @Value("${app.chat-memory.redis.key-prefix:chat:memory:}") String keyPrefix
    ) {
        return new MyRedisChatMemory(stringRedisTemplate, keyPrefix);
    }

    /**
     * 当未选择数据库或 Redis 时创建本地文件记忆实现。
     *
     * @param fileDir 每个会话消息文件的存储目录
     * @return 文件聊天记忆
     */
    @Bean
    @ConditionalOnMissingBean(ChatMemory.class)
    public ChatMemory fileChatMemory(
            @Value("${app.chat-memory.file-dir:${user.dir}/tmp/chat-memory}") String fileDir
    ) {
        return new FileBaseChatMemory(fileDir);
    }
}
