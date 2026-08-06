package com.kiwi.keweiaiagent.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.kiwi.keweiaiagent.chat.model.ChatAppCode;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天会话持久化对象。sessionId 是对外标识，userId 是可信账号归属；所有消息、附件和
 * Manus 执行进入业务逻辑前都必须通过二者的联合条件读取该对象。
 */
@Data
@TableName("ai_chat_session")
public class ChatSessionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("user_id")
    private Long userId;

    @TableField("app_code")
    private ChatAppCode appCode;

    @TableField("title")
    private String title;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @Version
    @TableField("version")
    private Long version;
}
