package com.kiwi.keweiaiagent.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kiwi.keweiaiagent.chat.entity.ChatAttachmentDO;

/**
 * 聊天附件 MyBatis Plus 数据访问接口，附件元数据与业务会话共用 MySQL 事务。
 */
public interface ChatAttachmentMapper extends BaseMapper<ChatAttachmentDO> {
}
