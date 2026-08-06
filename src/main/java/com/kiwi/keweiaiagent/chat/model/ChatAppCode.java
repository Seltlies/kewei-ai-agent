package com.kiwi.keweiaiagent.chat.model;

import com.kiwi.keweiaiagent.exception.BusinessException;
import com.kiwi.keweiaiagent.exception.ErrorCode;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 当前支持的聊天应用编码。会话创建时由服务端校验编码，避免客户端写入任意值后绕过
 * 各应用入口的会话类型检查。
 */
public enum ChatAppCode {
    LOVE_APP,
    MANUS,
    TODO_DEMO;

    /**
     * 将请求编码规范化为大写枚举值。仅接受本期明确支持的应用，不对未知编码进行降级处理。
     */
    public static ChatAppCode fromRequest(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "应用编码不能为空");
        }
        try {
            return ChatAppCode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "不支持的应用编码");
        }
    }
}
