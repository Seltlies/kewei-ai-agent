package com.kiwi.keweiaiagent.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kiwi.keweiaiagent.chat.dto.ChatMessageResponse;
import com.kiwi.keweiaiagent.chat.dto.ChatSessionPageResponse;
import com.kiwi.keweiaiagent.chat.dto.ChatSessionResponse;
import com.kiwi.keweiaiagent.chat.entity.ChatSessionDO;
import com.kiwi.keweiaiagent.chat.mapper.ChatSessionMapper;
import com.kiwi.keweiaiagent.chat.model.ChatAppCode;
import com.kiwi.keweiaiagent.chatmemory.entity.ChatMemoryMessageDO;
import com.kiwi.keweiaiagent.chatmemory.mapper.ChatMemoryMessageMapper;
import com.kiwi.keweiaiagent.exception.BusinessException;
import com.kiwi.keweiaiagent.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天会话领域服务，统一负责服务端会话创建、账号归属校验、稳定游标分页和历史消息读取。
 * 其他 AI、附件及 Manus 服务必须调用本服务校验所有权，禁止自行只按 sessionId 查询。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatSessionService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_TITLE_CODE_POINTS = 30;
    private static final DateTimeFormatter CURSOR_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMemoryMessageMapper chatMemoryMessageMapper;
    private final ObjectMapper objectMapper;

    /**
     * 根据首条有效文本创建正式会话。标题由服务端按照统一空白清理与 30 字符规则生成，
     * sessionId 使用 UUID，客户端不能提交或覆盖会话归属。
     */
    @Transactional(transactionManager = "mysqlTransactionManager")
    public ChatSessionResponse createTextSession(Long accountId, String rawAppCode, String firstMessage) {
        String normalizedMessage = normalizeMessage(firstMessage);
        if (!StringUtils.hasText(normalizedMessage)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "首条消息不能为空");
        }
        return ChatSessionResponse.from(createSession(
                accountId,
                ChatAppCode.fromRequest(rawAppCode),
                buildTitle(normalizedMessage)
        ));
    }

    /**
     * 在图片已完成格式、大小和真实内容校验后创建纯图片首发会话。该方法不接受客户端
     * 图片存在标志，只供附件服务调用，从而保证“图片会话”一定对应一张有效附件。
     */
    @Transactional(transactionManager = "mysqlTransactionManager")
    public ChatSessionDO createImageSession(Long accountId, String rawAppCode) {
        return createSession(accountId, ChatAppCode.fromRequest(rawAppCode), "图片会话");
    }

    /**
     * 使用账号 ID 和会话 ID 联合读取会话。不存在和不属于当前账号统一返回 404，避免通过
     * 响应差异探测其他账号的会话是否存在。
     */
    public ChatSessionDO requireOwnedSession(Long accountId, String sessionId) {
        if (accountId == null || !StringUtils.hasText(sessionId)) {
            throw new BusinessException(ErrorCode.CHAT_SESSION_NOT_FOUND);
        }
        ChatSessionDO session = chatSessionMapper.selectOne(
                new LambdaQueryWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getUserId, accountId)
                        .eq(ChatSessionDO::getSessionId, sessionId)
                        .last("LIMIT 1")
        );
        if (session == null) {
            log.warn("会话归属校验失败，accountId={}，sessionId={}", accountId, sessionId);
            throw new BusinessException(ErrorCode.CHAT_SESSION_NOT_FOUND);
        }
        return session;
    }

    /**
     * 在账号归属校验基础上继续校验会话所属应用，防止将 Love App 会话标识用于 Manus、
     * Todo 等其他执行入口，导致不同应用的消息结构写入同一会话。
     */
    public ChatSessionDO requireOwnedSessionForApp(
            Long accountId,
            String sessionId,
            ChatAppCode expectedAppCode
    ) {
        ChatSessionDO session = requireOwnedSession(accountId, sessionId);
        if (session.getAppCode() != expectedAppCode) {
            log.warn("会话应用类型校验失败，accountId={}，sessionId={}，expected={}，actual={}",
                    accountId, sessionId, expectedAppCode, session.getAppCode());
            throw new BusinessException(ErrorCode.CHAT_SESSION_CONFLICT, "会话所属应用与请求入口不一致");
        }
        return session;
    }

    /**
     * 查询当前账号会话列表。标题搜索依赖 title 字段的不区分大小写排序规则；分页游标同时
     * 包含 updateTime 和数据库 id，确保相同更新时间下不会重复或遗漏。
     */
    public ChatSessionPageResponse listOwnedSessions(
            Long accountId,
            String keyword,
            String cursor,
            Integer requestedLimit
    ) {
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "limit 必须在 1 到 100 之间");
        }
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        CursorValue cursorValue = decodeCursor(cursor);

        LambdaQueryWrapper<ChatSessionDO> query = new LambdaQueryWrapper<ChatSessionDO>()
                .eq(ChatSessionDO::getUserId, accountId)
                .like(StringUtils.hasText(normalizedKeyword), ChatSessionDO::getTitle, normalizedKeyword)
                .orderByDesc(ChatSessionDO::getUpdateTime)
                .orderByDesc(ChatSessionDO::getId)
                .last("LIMIT " + (limit + 1));
        if (cursorValue != null) {
            query.and(wrapper -> wrapper
                    .lt(ChatSessionDO::getUpdateTime, cursorValue.updateTime())
                    .or(nested -> nested
                            .eq(ChatSessionDO::getUpdateTime, cursorValue.updateTime())
                            .lt(ChatSessionDO::getId, cursorValue.id()))
            );
        }

        List<ChatSessionDO> rows = chatSessionMapper.selectList(query);
        boolean hasMore = rows.size() > limit;
        List<ChatSessionDO> pageRows = hasMore ? rows.subList(0, limit) : rows;
        List<ChatSessionResponse> items = pageRows.stream().map(ChatSessionResponse::from).toList();
        String nextCursor = hasMore && !pageRows.isEmpty()
                ? encodeCursor(pageRows.get(pageRows.size() - 1))
                : null;
        return new ChatSessionPageResponse(items, nextCursor, hasMore);
    }

    /**
     * 校验会话归属后按数据库主键顺序返回完整历史消息，并把持久化 JSON 转换为结构化响应。
     */
    public List<ChatMessageResponse> listOwnedMessages(Long accountId, String sessionId) {
        requireOwnedSession(accountId, sessionId);
        List<ChatMessageResponse> messages = chatMemoryMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMemoryMessageDO>()
                        .eq(ChatMemoryMessageDO::getConversationId, sessionId)
                        .orderByAsc(ChatMemoryMessageDO::getId)
        ).stream().map(message -> {
            try {
                // 数据库存储仍由 Spring AI 使用 Jackson 2 生成；接口层转换为标准集合后，
                // Spring Boot 4 的 Jackson 3 转换器能够输出原始 JSON 字段而不会暴露节点属性。
                return new ChatMessageResponse(
                        message.getId(),
                        objectMapper.readValue(
                                message.getPayloadJson(),
                                new TypeReference<Map<String, Object>>() { }
                        ),
                        message.getCreateTime()
                );
            } catch (JsonProcessingException e) {
                log.error("聊天历史消息 JSON 损坏，messageId={}，sessionId={}", message.getId(), sessionId, e);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "聊天历史消息读取失败", e);
            }
        }).toList();
        log.info("当前账号聊天历史读取成功，accountId={}，sessionId={}，messageCount={}",
                accountId, sessionId, messages.size());
        return messages;
    }

    /**
     * 消息发送完成或失败后更新会话最后活跃时间。更新条件同时包含账号和会话，避免错误请求
     * 触碰其他账号的会话排序状态。
     */
    @Transactional(transactionManager = "mysqlTransactionManager")
    public void touchOwnedSession(Long accountId, String sessionId) {
        int updated = chatSessionMapper.update(
                null,
                new LambdaUpdateWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getUserId, accountId)
                        .eq(ChatSessionDO::getSessionId, sessionId)
                        .set(ChatSessionDO::getUpdateTime, LocalDateTime.now())
        );
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CHAT_SESSION_NOT_FOUND);
        }
    }

    /**
     * 创建服务端生成 UUID、绑定账号和应用类型的正式会话记录。
     *
     * @param accountId 会话所属账号主键
     * @param appCode 会话所属应用
     * @param title 已按业务规则生成的标题
     * @return 已持久化会话
     */
    private ChatSessionDO createSession(Long accountId, ChatAppCode appCode, String title) {
        if (accountId == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        LocalDateTime now = LocalDateTime.now();
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUserId(accountId);
        session.setAppCode(appCode);
        session.setTitle(title);
        session.setCreateTime(now);
        session.setUpdateTime(now);
        session.setVersion(0L);
        chatSessionMapper.insert(session);
        log.info("服务端聊天会话创建成功，accountId={}，sessionId={}，appCode={}",
                accountId, session.getSessionId(), appCode);
        return session;
    }

    /**
     * 将连续空白压缩为单个空格并去除首尾空白，供空消息校验和标题生成共用。
     *
     * @param message 原始消息
     * @return 规范化消息；输入为 {@code null} 时仍返回 {@code null}
     */
    private String normalizeMessage(String message) {
        return message == null ? null : message.replaceAll("\\s+", " ").trim();
    }

    /**
     * 按 Unicode 码点截取会话标题，避免在 emoji 或代理对中间截断字符串。
     *
     * @param normalizedMessage 已规范化的首条消息
     * @return 最长 30 个码点、超长时以省略号结尾的标题
     */
    private String buildTitle(String normalizedMessage) {
        int codePointCount = normalizedMessage.codePointCount(0, normalizedMessage.length());
        if (codePointCount <= MAX_TITLE_CODE_POINTS) {
            return normalizedMessage;
        }
        int endIndex = normalizedMessage.offsetByCodePoints(0, MAX_TITLE_CODE_POINTS - 1);
        return normalizedMessage.substring(0, endIndex) + "…";
    }

    /**
     * 将最后一条记录的更新时间和主键编码为 URL 安全游标。
     *
     * @param session 当前页最后一条会话
     * @return 不带填充字符的 Base64URL 游标
     */
    private String encodeCursor(ChatSessionDO session) {
        String value = CURSOR_TIME_FORMATTER.format(session.getUpdateTime()) + "|" + session.getId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码并严格校验分页游标，拒绝结构错误、非法时间和非正主键。
     *
     * @param cursor 客户端回传游标
     * @return 游标值；空白游标返回 {@code null}
     */
    private CursorValue decodeCursor(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf('|');
            if (separator <= 0 || separator == decoded.length() - 1) {
                throw new IllegalArgumentException("游标结构错误");
            }
            LocalDateTime updateTime = LocalDateTime.parse(
                    decoded.substring(0, separator),
                    CURSOR_TIME_FORMATTER
            );
            long id = Long.parseLong(decoded.substring(separator + 1));
            if (id <= 0) {
                throw new IllegalArgumentException("游标主键错误");
            }
            return new CursorValue(updateTime, id);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "会话分页游标无效");
        }
    }

    /**
     * 键集分页边界，更新时间与主键共同形成稳定的倒序游标。
     *
     * @param updateTime 边界记录更新时间
     * @param id 边界记录数据库主键
     */
    private record CursorValue(LocalDateTime updateTime, Long id) {
    }
}
