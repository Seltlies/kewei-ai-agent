package com.kiwi.keweiaiagent.chatmemory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天记忆消息表映射对象。
 *
 * <p>payloadJson 保存完整 Spring AI 消息结构，包括角色、文本、元数据和工具调用信息；
 * conversationId 与服务端会话 UUID 关联。</p>
 */
@Data
@TableName("ai_chat_memory_message")
public class ChatMemoryMessageDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("payload_json")
    private String payloadJson;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
