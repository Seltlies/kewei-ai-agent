package com.kiwi.keweiaiagent.tools;

import cn.hutool.core.util.StrUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 时间工具类
 */
@Component
public class TimeTool {

    /**
     * 返回指定 IANA 时区的日期、时间和 Epoch 毫秒值。
     *
     * @param zoneId IANA 时区标识；空白时使用服务器默认时区
     * @return 多行时间信息或时区错误提示
     */
    @Tool(description = "Get current date and time info, optional timezone ID. Example: Asia/Shanghai", returnDirect = false)
    public String getCurrentDateTime(
            @ToolParam(description = "Optional IANA timezone ID, default system timezone") String zoneId
    ) {
        try {
            ZoneId targetZone = StrUtil.isBlank(zoneId) ? ZoneId.systemDefault() : ZoneId.of(zoneId);
            ZonedDateTime now = ZonedDateTime.now(targetZone);
            LocalDate date = now.toLocalDate();
            LocalTime time = now.toLocalTime();
            LocalDateTime dateTime = now.toLocalDateTime();

            return String.join("\n",
                    "zone: " + targetZone.getId(),
                    "date: " + date,
                    "time: " + time,
                    "datetime: " + dateTime,
                    "epochMillis: " + Instant.now().toEpochMilli()
            );
        } catch (Exception e) {
            return "Error: invalid timezone. Please use a valid IANA timezone ID, e.g. Asia/Shanghai. details=" + e.getMessage();
        }
    }
}
