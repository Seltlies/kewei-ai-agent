package com.kiwi.keweiaiagent.chat.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 页面历史消息响应。payload 使用标准 Java Map/List/基础类型承载持久化 JSON，既保留
 * Spring AI 消息类型、正文、元数据和工具结果结构，也避免 Jackson 2 JsonNode 被
 * Spring Boot 4 的 Jackson 3 HTTP 转换器当作普通 Bean 展开。
 */
public record ChatMessageResponse(
        Long id,
        Map<String, Object> payload,
        LocalDateTime createTime
) {
}
