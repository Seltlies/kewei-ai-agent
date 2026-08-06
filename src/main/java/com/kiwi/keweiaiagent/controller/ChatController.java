package com.kiwi.keweiaiagent.controller;

import com.kiwi.keweiaiagent.chat.dto.ChatMessageResponse;
import com.kiwi.keweiaiagent.chat.dto.ChatAttachmentResponse;
import com.kiwi.keweiaiagent.chat.dto.ChatSessionPageResponse;
import com.kiwi.keweiaiagent.chat.dto.ChatSessionResponse;
import com.kiwi.keweiaiagent.chat.dto.CreateChatSessionRequest;
import com.kiwi.keweiaiagent.chat.service.ChatSessionService;
import com.kiwi.keweiaiagent.chat.service.ChatAttachmentService;
import com.kiwi.keweiaiagent.security.AuthenticatedAccount;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 当前账号聊天会话 REST 接口。账号 ID 只从 Spring Security 认证主体取得，禁止客户端在
 * 请求体或查询参数中指定数据所属账号。
 */
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatSessionService chatSessionService;
    private final ChatAttachmentService chatAttachmentService;

    /**
     * 首条有效文本发送前创建服务端正式会话，并返回后续 AI 请求必须使用的会话标识。
     */
    @PostMapping("/sessions")
    public ChatSessionResponse createSession(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @Valid @RequestBody CreateChatSessionRequest request
    ) {
        return chatSessionService.createTextSession(
                account.accountId(),
                request.appCode(),
                request.firstMessage()
        );
    }

    /**
     * 按标题搜索并使用稳定游标分页查询当前账号的会话列表。
     */
    @GetMapping("/sessions")
    public ChatSessionPageResponse listSessions(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit
    ) {
        return chatSessionService.listOwnedSessions(account.accountId(), keyword, cursor, limit);
    }

    /**
     * 校验当前账号拥有目标会话后，返回能够恢复页面状态的结构化历史消息。
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatMessageResponse> listMessages(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String sessionId
    ) {
        return chatSessionService.listOwnedMessages(account.accountId(), sessionId);
    }

    /**
     * 为首条纯图片消息创建服务端会话并保存附件。请求不能自行指定 sessionId，确保图片
     * 会话和第一张有效附件在同一业务操作中建立。
     */
    @PostMapping(value = "/sessions/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatAttachmentResponse createImageSession(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam String appCode,
            @RequestParam("file") MultipartFile file
    ) {
        return chatAttachmentService.createImageSessionAndStore(account.accountId(), appCode, file);
    }

    /**
     * 向当前账号拥有的已有会话追加图片附件。服务端同时验证会话归属和图片真实内容。
     */
    @PostMapping(value = "/sessions/{sessionId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatAttachmentResponse uploadAttachment(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String sessionId,
            @RequestParam("file") MultipartFile file
    ) {
        return chatAttachmentService.storeForExistingSession(account.accountId(), sessionId, file);
    }

    /**
     * 读取当前账号拥有的附件。响应以内联资源形式输出，并且不暴露磁盘存储路径。
     */
    @GetMapping("/attachments/{attachmentId}")
    public ResponseEntity<Resource> getAttachment(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String attachmentId
    ) {
        ChatAttachmentService.ChatAttachmentFile attachment =
                chatAttachmentService.loadOwnedAttachment(account.accountId(), attachmentId);
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(attachment.originalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.contentType()))
                .contentLength(attachment.fileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(attachment.resource());
    }
}
