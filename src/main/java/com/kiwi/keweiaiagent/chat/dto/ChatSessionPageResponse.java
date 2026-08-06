package com.kiwi.keweiaiagent.chat.dto;

import java.util.List;

/**
 * 基于稳定游标的会话列表响应。nextCursor 由服务端生成，客户端不得解析或自行构造游标内容。
 */
public record ChatSessionPageResponse(
        List<ChatSessionResponse> items,
        String nextCursor,
        boolean hasMore
) {
}
