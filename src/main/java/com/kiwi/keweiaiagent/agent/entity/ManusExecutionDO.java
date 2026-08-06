package com.kiwi.keweiaiagent.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.kiwi.keweiaiagent.agent.model.ManusExecutionStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Manus 执行记录持久化对象。每次明确发起任务都会生成独立 executionId，执行状态和页面
 * 恢复所需的任务、Todo、补充问题均写入 MySQL，不依赖 JVM 内存长期保存。
 */
@Data
@TableName("ai_agent_execution")
public class ManusExecutionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("execution_id")
    private String executionId;

    @TableField("session_id")
    private String sessionId;

    @TableField("user_id")
    private Long userId;

    @TableField("status")
    private ManusExecutionStatus status;

    @TableField("task_payload")
    private String taskPayload;

    @TableField("todo_payload")
    private String todoPayload;

    @TableField("question_payload")
    private String questionPayload;

    @TableField("reason")
    private String reason;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @Version
    @TableField("version")
    private Long version;
}
