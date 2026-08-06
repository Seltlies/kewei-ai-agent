package com.kiwi.keweiaiagent.chat.dto;

import com.kiwi.keweiaiagent.chat.entity.ChatSessionDO;

import java.time.LocalDateTime;

/**
 * 会话响应，只返回页面需要的对外标识、标题、应用和时间，不暴露数据库主键与账号主键。
 */
public record ChatSessionResponse(
        String sessionId,
        String title,
        String appCode,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
    public static ChatSessionResponse from(ChatSessionDO session) {
        return new ChatSessionResponse(
                session.getSessionId(),
                session.getTitle(),
                session.getAppCode().name(),
                session.getCreateTime(),
                session.getUpdateTime()
        );
    }
}
