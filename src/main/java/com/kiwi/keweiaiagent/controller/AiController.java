package com.kiwi.keweiaiagent.controller;

import com.kiwi.keweiaiagent.agent.ManusSessionService;
import com.kiwi.keweiaiagent.app.LoveApp;
import com.kiwi.keweiaiagent.app.TodoDemoApp;
import com.kiwi.keweiaiagent.chat.model.ChatAppCode;
import com.kiwi.keweiaiagent.chat.dto.ChatAttachmentResponse;
import com.kiwi.keweiaiagent.chat.service.ChatAttachmentService;
import com.kiwi.keweiaiagent.chat.service.ChatSessionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import com.kiwi.keweiaiagent.security.AuthenticatedAccount;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 控制器，负责暴露聊天、Manus 会话和文件上传等接口。
 */
@RestController
@RequestMapping("/ai")
public class AiController {

    /**
     * 默认 SSE 连接超时时间。
     */
    private static final long DEFAULT_SSE_TIMEOUT = 0L;

    /**
     * 恋爱助手应用服务。
     */
    @Resource
    private LoveApp loveApp;

    /**
     * 当前系统注册的全部工具。
     */
    @Resource
    private ToolCallback[] allTools;

    /**
     * JSON 序列化工具。
     */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * Manus 会话服务。
     */
    @Resource
    private ManusSessionService manusSessionService;

    /**
     * Todo 演示应用服务。
     */
    @Resource
    private TodoDemoApp todoDemoApp;

    /**
     * 会话归属服务，所有 AI、Manus 和上传入口在进入应用服务前都通过该组件校验当前账号。
     */
    @Resource
    private ChatSessionService chatSessionService;

    /**
     * 受控附件服务，图片 AI 入口只能通过附件 ID 解析当前账号拥有的真实文件。
     */
    @Resource
    private ChatAttachmentService chatAttachmentService;

    /**
     * 同步调用恋爱助手完成一次对话。
     */
    @PostMapping("/love_app/chat/sync")
    public Object doChatWithLoveAppSync(
            String message,
            String chatId,
            String option,
            @RequestParam(required = false) String attachmentId,
            @AuthenticationPrincipal AuthenticatedAccount account
    ) {
        chatSessionService.requireOwnedSessionForApp(account.accountId(), chatId, resolveAppCode(option));
        try {
            if (shouldUseTodoDemoOption(option)) {
                return todoDemoApp.call(message, chatId);
            }
            if (shouldUseSkillsOption(option)) {
                return secureGeneratedImage(
                        account.accountId(), chatId, loveApp.callWithSkills(message, chatId));
            }
            if (shouldUseImageOption(option, attachmentId)) {
                Path imagePath = chatAttachmentService.requireOwnedAttachmentPath(
                        account.accountId(), chatId, attachmentId);
                return loveApp.doChatWithImage(message, chatId, attachmentId, imagePath.toString());
            }
            return loveApp.doChat(message, chatId);
        } finally {
            chatSessionService.touchOwnedSession(account.accountId(), chatId);
        }
    }

    /**
     * 以 SSE 方式调用恋爱助手并返回流式结果。
     */
    @PostMapping(value = "/love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithLoveAppSSE(
            String message,
            String chatId,
            String option,
            @RequestParam(required = false) String attachmentId,
            @AuthenticationPrincipal AuthenticatedAccount account
    ) {
        chatSessionService.requireOwnedSessionForApp(account.accountId(), chatId, resolveAppCode(option));
        Flux<String> result = shouldUseTodoDemoOption(option)
                ? todoDemoApp.stream(message, chatId)
                : shouldUseSkillsOption(option)
                ? loveApp.streamWithSkills(message, chatId)
                        .map(resultItem -> secureGeneratedImage(account.accountId(), chatId, resultItem))
                        .map(this::toJson)
                : shouldUseImageOption(option, attachmentId)
                ? loveApp.doChatWithImageStream(
                        message,
                        chatId,
                        attachmentId,
                        chatAttachmentService.requireOwnedAttachmentPath(
                                account.accountId(), chatId, attachmentId).toString())
                : loveApp.doChatWithStream(message, chatId);
        return result.doFinally(signalType -> chatSessionService.touchOwnedSession(account.accountId(), chatId));
    }

    /**
     * 通过 ServerSentEvent 包装流式输出。
     */
    @PostMapping(value = "/love_app/chat/server_sent_event")
    public Flux<ServerSentEvent<String>> zdoChatWithLoveAppServerSentEvent(
            String message,
            String chatId,
            String option,
            @RequestParam(required = false) String attachmentId,
            @AuthenticationPrincipal AuthenticatedAccount account
    ) {
        chatSessionService.requireOwnedSessionForApp(account.accountId(), chatId, resolveAppCode(option));
        Flux<String> contentFlux = shouldUseTodoDemoOption(option)
                ? todoDemoApp.stream(message, chatId)
                : shouldUseSkillsOption(option)
                ? loveApp.streamWithSkills(message, chatId)
                        .map(resultItem -> secureGeneratedImage(account.accountId(), chatId, resultItem))
                        .map(this::toJson)
                : shouldUseImageOption(option, attachmentId)
                ? loveApp.doChatWithImageStream(
                        message,
                        chatId,
                        attachmentId,
                        chatAttachmentService.requireOwnedAttachmentPath(
                                account.accountId(), chatId, attachmentId).toString())
                : loveApp.doChatWithStream(message, chatId);
        return contentFlux
                .map(chunk->ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build())
                .doFinally(signalType -> chatSessionService.touchOwnedSession(account.accountId(), chatId));
    }

    /**
     * 通过 SseEmitter 直接向客户端推送恋爱助手回复。
     */
    @PostMapping(value = "/love_app/chat/sse_emitter", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter doChatWithLoveAppSseEmitter(
            String message,
            String chatId,
            String option,
            @RequestParam(required = false) String attachmentId,
            @AuthenticationPrincipal AuthenticatedAccount account
    ) {
        chatSessionService.requireOwnedSessionForApp(account.accountId(), chatId, resolveAppCode(option));
        SseEmitter emitter = new SseEmitter(DEFAULT_SSE_TIMEOUT);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                Flux<String> resultFlux = shouldUseSkillsOption(option)
                        ? loveApp.streamWithSkills(message, chatId)
                                .map(resultItem -> secureGeneratedImage(account.accountId(), chatId, resultItem))
                                .map(this::toJson)
                        : shouldUseTodoDemoOption(option)
                        ? todoDemoApp.stream(message, chatId)
                        : shouldUseImageOption(option, attachmentId)
                        ? loveApp.doChatWithImageStream(
                                message,
                                chatId,
                                attachmentId,
                                chatAttachmentService.requireOwnedAttachmentPath(
                                        account.accountId(), chatId, attachmentId).toString())
                        : loveApp.doChatWithStream(message, chatId);

                for (String chunk : resultFlux.toIterable()) {
                    emitter.send(SseEmitter.event().name("message").data(chunk));
                }
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            } catch (Exception e) {
                if (!isClientDisconnect(e)) {
                    emitter.completeWithError(e);
                } else {
                    emitter.complete();
                }
            } finally {
                chatSessionService.touchOwnedSession(account.accountId(), chatId);
                executor.shutdown();
            }
        });
        return emitter;
    }

    private boolean isClientDisconnect(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof IOException) {
                return true;
            }
            if (current instanceof IllegalStateException && current.getMessage() != null) {
                String message = current.getMessage();
                if (message.contains("ResponseBodyEmitter has already completed")
                        || message.contains("Broken pipe")
                        || message.contains("broken pipe")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }


    /**
     * 启动 Manus 智能体流式会话。
     */
    @PostMapping("manus/chat")
    public SseEmitter doChatWithManus(
            String message,
            String chatId,
            String option,
            @RequestParam(required = false) String attachmentId,
            @AuthenticationPrincipal AuthenticatedAccount account
    ) {
        chatSessionService.requireOwnedSessionForApp(account.accountId(), chatId, ChatAppCode.MANUS);
        return manusSessionService.startChatStream(account.accountId(), chatId, message);
    }

    /**
     * 提交补充答案后继续执行 Manus 会话。
     */
    @PostMapping(value = "manus/chat/continue", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter continueChatWithManus(
            @Valid @RequestBody ManusContinueRequest request,
            @AuthenticationPrincipal AuthenticatedAccount account
    ) {
        chatSessionService.requireOwnedSessionForApp(
                account.accountId(),
                request.chatId(),
                ChatAppCode.MANUS
        );
        return manusSessionService.continueChatStream(request.chatId(), request.answers());
    }

    /**
     * 上传恋爱助手聊天中使用的图片文件。
     */
    @PostMapping(value = "/love_app/image/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatAttachmentResponse uploadLoveAppImage(
            @RequestParam("chatId") String chatId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AuthenticatedAccount account
    ) {
        chatSessionService.requireOwnedSessionForApp(account.accountId(), chatId, ChatAppCode.LOVE_APP);
        return chatAttachmentService.storeForExistingSession(account.accountId(), chatId, file);
    }

    private boolean shouldUseImageOption(String option, String attachmentId) {
        return "image".equalsIgnoreCase(option) && attachmentId != null && !attachmentId.isBlank();
    }

    private boolean shouldUseSkillsOption(String option) {
        return "skills".equalsIgnoreCase(option);
    }

    private boolean shouldUseTodoDemoOption(String option) {
        return "todo-demo".equalsIgnoreCase(option);
    }

    /**
     * 根据现有 option 参数确定请求必须使用的服务端会话类型，未知 option 仍属于 Love App
     * 的普通能力，不允许借此绕过会话应用隔离。
     */
    private ChatAppCode resolveAppCode(String option) {
        return shouldUseTodoDemoOption(option) ? ChatAppCode.TODO_DEMO : ChatAppCode.LOVE_APP;
    }

    private String toJson(LoveApp.SkillChatResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize skill result", e);
        }
    }

    /**
     * AI 技能返回图片文件时登记为当前账号会话的正式附件，并替换掉不可对外暴露的本地路径。
     * PDF、PPT 等尚无受控下载接口的文件只返回通用完成结果，客户端与历史均不接收物理路径。
     */
    private LoveApp.SkillChatResult secureGeneratedImage(
            Long accountId,
            String chatId,
            LoveApp.SkillChatResult result
    ) {
        if (result.type() != LoveApp.SkillChatResultType.FILE || result.filePath() == null) {
            loveApp.saveSkillAssistantResult(chatId, result);
            return result;
        }
        String normalizedPath = result.filePath().toLowerCase(java.util.Locale.ROOT);
        if (!(normalizedPath.endsWith(".jpg")
                || normalizedPath.endsWith(".jpeg")
                || normalizedPath.endsWith(".png")
                || normalizedPath.endsWith(".webp"))) {
            LoveApp.SkillChatResult securedFileResult = LoveApp.SkillChatResult.securedFile();
            loveApp.saveSkillAssistantResult(chatId, securedFileResult);
            return securedFileResult;
        }
        ChatAttachmentResponse attachment = chatAttachmentService.storeGeneratedImage(
                accountId, chatId, result.filePath());
        LoveApp.SkillChatResult securedResult = LoveApp.SkillChatResult.attachment(
                attachment.attachmentId(), attachment.contentUrl());
        loveApp.saveSkillAssistantResult(chatId, securedResult);
        return securedResult;
    }

    /**
     * Manus 续聊请求对象，封装继续执行时提交的答案集合。
     */
    public record ManusContinueRequest(
            @NotBlank(message = "chatId 不能为空") String chatId,
            @NotEmpty(message = "answers 不能为空") Map<String, String> answers
    ) {}

}
