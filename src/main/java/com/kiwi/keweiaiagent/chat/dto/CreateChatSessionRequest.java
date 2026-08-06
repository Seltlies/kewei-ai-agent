package com.kiwi.keweiaiagent.chat.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 文本首发会话创建请求。空白文本不会创建服务端会话；纯图片首发由附件接口在文件完成
 * 三重校验后调用同一会话服务创建，避免客户端声明有图片却留下空会话。
 */
public record CreateChatSessionRequest(
        @NotBlank(message = "应用编码不能为空") String appCode,
        @NotBlank(message = "首条消息不能为空") String firstMessage
) {
}
