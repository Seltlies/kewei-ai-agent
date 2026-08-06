package com.kiwi.keweiaiagent.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kiwi.keweiaiagent.chat.dto.ChatAttachmentResponse;
import com.kiwi.keweiaiagent.chat.entity.ChatAttachmentDO;
import com.kiwi.keweiaiagent.chat.entity.ChatSessionDO;
import com.kiwi.keweiaiagent.chat.mapper.ChatAttachmentMapper;
import com.kiwi.keweiaiagent.constant.FileConstant;
import com.kiwi.keweiaiagent.exception.BusinessException;
import com.kiwi.keweiaiagent.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 聊天附件服务，统一执行会话归属、文件大小、扩展名、声明 MIME 和真实内容四层校验，
 * 并把文件写入环境变量指定的持久化根目录。数据库只保存服务端生成的相对路径。
 */
@Service
@Slf4j
public class ChatAttachmentService {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final Map<String, String> EXTENSION_CONTENT_TYPES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp"
    );

    private final ChatSessionService chatSessionService;
    private final ChatAttachmentMapper chatAttachmentMapper;
    private final Path storageRoot;

    /**
     * ATTACHMENT_STORAGE_ROOT 必须由部署环境显式提供绝对目录。应用不会默认写入临时目录、
     * 编译目录或前端静态资源目录，避免重启、重新构建或静态资源发布时丢失用户附件。
     */
    public ChatAttachmentService(
            ChatSessionService chatSessionService,
            ChatAttachmentMapper chatAttachmentMapper,
            @Value("${ATTACHMENT_STORAGE_ROOT}") String configuredStorageRoot
    ) {
        if (!StringUtils.hasText(configuredStorageRoot)) {
            throw new IllegalStateException("ATTACHMENT_STORAGE_ROOT 不能为空");
        }
        Path configuredPath = Path.of(configuredStorageRoot);
        if (!configuredPath.isAbsolute()) {
            throw new IllegalStateException("ATTACHMENT_STORAGE_ROOT 必须是绝对路径");
        }
        this.chatSessionService = chatSessionService;
        this.chatAttachmentMapper = chatAttachmentMapper;
        this.storageRoot = configuredPath.normalize();
    }

    /**
     * 向当前账号已有会话上传附件。会话归属校验先于文件落盘，避免越权请求在服务器留下文件。
     */
    @Transactional(transactionManager = "mysqlTransactionManager")
    public ChatAttachmentResponse storeForExistingSession(
            Long accountId,
            String sessionId,
            MultipartFile file
    ) {
        ChatSessionDO session = chatSessionService.requireOwnedSession(accountId, sessionId);
        ValidatedImage image = validateImage(file);
        return ChatAttachmentResponse.from(storeValidatedImage(session, image));
    }

    /**
     * 为纯图片首发创建正式会话并保存附件。会话、附件元数据处于同一 MySQL 事务；事务回滚时
     * 通过 Spring 事务同步回调删除已落盘文件，防止数据库记录与物理文件产生孤儿数据。
     */
    @Transactional(transactionManager = "mysqlTransactionManager")
    public ChatAttachmentResponse createImageSessionAndStore(
            Long accountId,
            String appCode,
            MultipartFile file
    ) {
        ValidatedImage image = validateImage(file);
        ChatSessionDO session = chatSessionService.createImageSession(accountId, appCode);
        return ChatAttachmentResponse.from(storeValidatedImage(session, image));
    }

    /**
     * 登记 AI 工具生成的图片。生成文件必须位于后端工具专用目录且真实路径不能通过符号链接
     * 越界；校验通过后复制到统一持久化附件目录，原工具临时路径不会返回给客户端。
     */
    @Transactional(transactionManager = "mysqlTransactionManager")
    public ChatAttachmentResponse storeGeneratedImage(
            Long accountId,
            String sessionId,
            String generatedFilePath
    ) {
        ChatSessionDO session = chatSessionService.requireOwnedSession(accountId, sessionId);
        if (!StringUtils.hasText(generatedFilePath)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "AI 生成图片路径不能为空");
        }
        Path configuredGeneratedRoot = Path.of(FileConstant.File_SAVE_DIR).toAbsolutePath().normalize();
        Path candidate = Path.of(generatedFilePath);
        if (!candidate.isAbsolute()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "AI 生成图片必须使用工具返回的绝对路径");
        }

        try {
            Files.createDirectories(configuredGeneratedRoot);
            Path generatedRoot = configuredGeneratedRoot.toRealPath();
            Path realCandidate = candidate.normalize().toRealPath();
            if (!realCandidate.startsWith(generatedRoot) || !Files.isRegularFile(realCandidate)) {
                log.warn("AI 生成图片路径越界或不是普通文件，sessionId={}，accountId={}", sessionId, accountId);
                throw new BusinessException(ErrorCode.INVALID_PARAM, "AI 生成图片不在允许的工具目录内");
            }
            long size = Files.size(realCandidate);
            if (size <= 0 || size > MAX_FILE_SIZE) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "AI 生成图片大小必须在 1 字节到 10MB 之间");
            }
            String originalName = normalizeOriginalName(realCandidate.getFileName().toString());
            String extension = extractExtension(originalName);
            if (!ALLOWED_EXTENSIONS.contains(extension)) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "AI 生成文件不是支持的图片类型");
            }
            byte[] content = Files.readAllBytes(realCandidate);
            ValidatedImage image = validateImageData(
                    originalName,
                    extension,
                    EXTENSION_CONTENT_TYPES.get(extension),
                    content,
                    size
            );
            return ChatAttachmentResponse.from(storeValidatedImage(session, image));
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.error("AI 生成图片读取失败，sessionId={}，accountId={}", sessionId, accountId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 生成图片登记失败", e);
        }
    }

    /**
     * 根据当前账号、会话和附件三者联合校验后返回 AI 模型可读取的物理路径。调用方不能传入
     * 任意服务器路径，因此图片问答入口不会成为文件读取通道。
     */
    public Path requireOwnedAttachmentPath(Long accountId, String sessionId, String attachmentId) {
        chatSessionService.requireOwnedSession(accountId, sessionId);
        ChatAttachmentDO attachment = findOwnedAttachment(accountId, attachmentId, sessionId);
        Path path = resolveStoragePath(attachment.getStoragePath());
        if (!Files.isRegularFile(path)) {
            log.error("附件元数据对应的文件不存在，attachmentId={}，sessionId={}，accountId={}",
                    attachmentId, sessionId, accountId);
            throw new BusinessException(ErrorCode.CHAT_ATTACHMENT_NOT_FOUND);
        }
        return path;
    }

    /**
     * 返回当前账号拥有的附件资源及响应元数据，控制器据此输出受保护的图片内容。
     */
    public ChatAttachmentFile loadOwnedAttachment(Long accountId, String attachmentId) {
        ChatAttachmentDO attachment = findOwnedAttachment(accountId, attachmentId, null);
        chatSessionService.requireOwnedSession(accountId, attachment.getSessionId());
        Path path = resolveStoragePath(attachment.getStoragePath());
        if (!Files.isRegularFile(path)) {
            log.error("附件读取失败，物理文件不存在，attachmentId={}，accountId={}", attachmentId, accountId);
            throw new BusinessException(ErrorCode.CHAT_ATTACHMENT_NOT_FOUND);
        }
        return new ChatAttachmentFile(
                new FileSystemResource(path),
                attachment.getOriginalName(),
                attachment.getContentType(),
                attachment.getFileSize()
        );
    }

    private ChatAttachmentDO storeValidatedImage(ChatSessionDO session, ValidatedImage image) {
        String attachmentId = UUID.randomUUID().toString();
        String storageName = UUID.randomUUID().toString().replace("-", "") + "." + image.extension();
        Path relativePath = Path.of(String.valueOf(session.getUserId()), session.getSessionId(), storageName);
        Path targetPath = resolveStoragePath(relativePath.toString());

        try {
            Files.createDirectories(targetPath.getParent());
            Files.write(targetPath, image.content(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.error("聊天附件写入失败，attachmentId={}，sessionId={}，accountId={}",
                    attachmentId, session.getSessionId(), session.getUserId(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片保存失败", e);
        }

        registerRollbackCleanup(targetPath, attachmentId);
        ChatAttachmentDO attachment = new ChatAttachmentDO();
        attachment.setAttachmentId(attachmentId);
        attachment.setSessionId(session.getSessionId());
        attachment.setUserId(session.getUserId());
        attachment.setOriginalName(image.originalName());
        attachment.setStorageName(storageName);
        attachment.setStoragePath(relativePath.toString());
        attachment.setContentType(image.contentType());
        attachment.setFileSize((long) image.content().length);
        attachment.setCreateTime(LocalDateTime.now());
        chatAttachmentMapper.insert(attachment);
        log.info("聊天附件保存成功，attachmentId={}，sessionId={}，accountId={}，contentType={}，fileSize={}",
                attachmentId, session.getSessionId(), session.getUserId(), image.contentType(), image.content().length);
        return attachment;
    }

    private ValidatedImage validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "图片不能为空");
        }
        if (file.getSize() <= 0 || file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "图片大小必须在 1 字节到 10MB 之间");
        }

        String originalName = normalizeOriginalName(file.getOriginalFilename());
        String extension = extractExtension(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "仅支持 jpg、jpeg、png、webp 图片");
        }
        String declaredContentType = file.getContentType() == null
                ? null
                : file.getContentType().toLowerCase(Locale.ROOT);
        String expectedContentType = EXTENSION_CONTENT_TYPES.get(extension);
        if (!expectedContentType.equals(declaredContentType)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "图片扩展名与 MIME 类型不一致");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "图片内容读取失败", e);
        }
        return validateImageData(originalName, extension, expectedContentType, content, file.getSize());
    }

    private ValidatedImage validateImageData(
            String originalName,
            String extension,
            String contentType,
            byte[] content,
            long declaredSize
    ) {
        if (content.length <= 0 || content.length > MAX_FILE_SIZE || content.length != declaredSize) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "图片实际大小不符合要求");
        }
        validateRealContent(contentType, content);
        return new ValidatedImage(originalName, extension, contentType, content);
    }

    private String normalizeOriginalName(String originalFilename) {
        if (!StringUtils.hasText(originalFilename) || originalFilename.indexOf('\0') >= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "图片文件名无效");
        }
        String normalized = originalFilename.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (!StringUtils.hasText(normalized) || normalized.length() > 255) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "图片文件名长度必须在 1 到 255 之间");
        }
        return normalized;
    }

    private String extractExtension(String originalName) {
        int index = originalName.lastIndexOf('.');
        if (index <= 0 || index == originalName.length() - 1) {
            return "";
        }
        return originalName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private void validateRealContent(String contentType, byte[] content) {
        if ("image/webp".equals(contentType)) {
            validateWebp(content);
        } else {
            boolean signatureMatches = "image/png".equals(contentType)
                    ? hasPrefix(content, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})
                    : hasPrefix(content, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            if (!signatureMatches) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "图片真实内容与声明类型不一致");
            }
        }
        try {
            // ImageIO 会调用 TwelveMonkeys WebP 插件或 JDK 自带 JPEG/PNG 解码器读取完整像素数据。
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "图片内容无法解码");
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "图片内容无法解码", e);
        }
    }

    private void validateWebp(byte[] content) {
        if (content.length < 20
                || !matchesAscii(content, 0, "RIFF")
                || !matchesAscii(content, 8, "WEBP")) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "WebP 图片结构无效");
        }
        long riffSize = readUnsignedLittleEndianInt(content, 4);
        if (riffSize + 8 != content.length) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "WebP 图片长度与文件结构不一致");
        }
        String chunkType = new String(content, 12, 4, StandardCharsets.US_ASCII);
        long chunkSize = readUnsignedLittleEndianInt(content, 16);
        long paddedChunkEnd = 20L + chunkSize + (chunkSize & 1L);
        if (!("VP8 ".equals(chunkType) || "VP8L".equals(chunkType) || "VP8X".equals(chunkType))
                || chunkSize <= 0
                || paddedChunkEnd > content.length) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "WebP 图片数据块无效");
        }
        if (("VP8 ".equals(chunkType) && (chunkSize < 10
                || content[23] != (byte) 0x9D || content[24] != 0x01 || content[25] != 0x2A))
                || ("VP8L".equals(chunkType) && (chunkSize < 5 || content[20] != 0x2F))
                || ("VP8X".equals(chunkType) && chunkSize != 10)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "WebP 图片编码头无效");
        }
    }

    private boolean hasPrefix(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesAscii(byte[] content, int offset, String expected) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
        if (offset < 0 || content.length - offset < expectedBytes.length) {
            return false;
        }
        for (int index = 0; index < expectedBytes.length; index++) {
            if (content[offset + index] != expectedBytes[index]) {
                return false;
            }
        }
        return true;
    }

    private long readUnsignedLittleEndianInt(byte[] content, int offset) {
        return (content[offset] & 0xFFL)
                | ((content[offset + 1] & 0xFFL) << 8)
                | ((content[offset + 2] & 0xFFL) << 16)
                | ((content[offset + 3] & 0xFFL) << 24);
    }

    private ChatAttachmentDO findOwnedAttachment(Long accountId, String attachmentId, String sessionId) {
        if (accountId == null || !StringUtils.hasText(attachmentId)) {
            throw new BusinessException(ErrorCode.CHAT_ATTACHMENT_NOT_FOUND);
        }
        ChatAttachmentDO attachment = chatAttachmentMapper.selectOne(
                new LambdaQueryWrapper<ChatAttachmentDO>()
                        .eq(ChatAttachmentDO::getUserId, accountId)
                        .eq(ChatAttachmentDO::getAttachmentId, attachmentId)
                        .eq(StringUtils.hasText(sessionId), ChatAttachmentDO::getSessionId, sessionId)
                        .last("LIMIT 1")
        );
        if (attachment == null) {
            log.warn("附件归属校验失败，attachmentId={}，sessionId={}，accountId={}",
                    attachmentId, sessionId, accountId);
            throw new BusinessException(ErrorCode.CHAT_ATTACHMENT_NOT_FOUND);
        }
        return attachment;
    }

    private Path resolveStoragePath(String relativeStoragePath) {
        Path resolvedPath = storageRoot.resolve(relativeStoragePath).normalize();
        if (!resolvedPath.startsWith(storageRoot)) {
            log.error("附件存储路径越界，storagePath={}", relativeStoragePath);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "附件存储路径无效");
        }
        return resolvedPath;
    }

    private void registerRollbackCleanup(Path targetPath, String attachmentId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;
                }
                try {
                    Files.deleteIfExists(targetPath);
                    log.info("附件事务回滚，已删除物理文件，attachmentId={}", attachmentId);
                } catch (IOException e) {
                    log.error("附件事务回滚后删除物理文件失败，attachmentId={}", attachmentId, e);
                }
            }
        });
    }

    private record ValidatedImage(
            String originalName,
            String extension,
            String contentType,
            byte[] content
    ) {
    }

    /**
     * 受保护附件读取结果，Resource、原始文件名、已验证 MIME 和大小由同一条元数据记录提供。
     */
    public record ChatAttachmentFile(
            Resource resource,
            String originalName,
            String contentType,
            Long fileSize
    ) {
    }
}
