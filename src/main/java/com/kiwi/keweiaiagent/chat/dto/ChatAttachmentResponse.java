package com.kiwi.keweiaiagent.chat.dto;

import com.kiwi.keweiaiagent.chat.entity.ChatAttachmentDO;

/**
 * 附件上传响应。下载地址指向受登录态和数据归属校验保护的接口，不暴露服务器物理路径。
 */
public record ChatAttachmentResponse(
        String attachmentId,
        String sessionId,
        String originalName,
        String contentType,
        Long fileSize,
        String contentUrl
) {

    public static ChatAttachmentResponse from(ChatAttachmentDO attachment) {
        return new ChatAttachmentResponse(
                attachment.getAttachmentId(),
                attachment.getSessionId(),
                attachment.getOriginalName(),
                attachment.getContentType(),
                attachment.getFileSize(),
                "/chat/attachments/" + attachment.getAttachmentId()
        );
    }
}
