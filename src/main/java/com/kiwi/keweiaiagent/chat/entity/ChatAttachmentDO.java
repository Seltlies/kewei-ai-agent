package com.kiwi.keweiaiagent.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天附件持久化对象。对外只暴露随机 attachmentId，实际存储文件名和相对路径完全由
 * 服务端生成，避免客户端提供路径或文件名影响服务器文件系统。
 */
@Data
@TableName("ai_chat_attachment")
public class ChatAttachmentDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("attachment_id")
    private String attachmentId;

    @TableField("session_id")
    private String sessionId;

    @TableField("user_id")
    private Long userId;

    @TableField("original_name")
    private String originalName;

    @TableField("storage_name")
    private String storageName;

    @TableField("storage_path")
    private String storagePath;

    @TableField("content_type")
    private String contentType;

    @TableField("file_size")
    private Long fileSize;

    @TableField("create_time")
    private LocalDateTime createTime;
}
