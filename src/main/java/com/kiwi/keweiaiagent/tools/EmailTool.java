package com.kiwi.keweiaiagent.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.lang.Validator;
import cn.hutool.extra.mail.MailAccount;
import cn.hutool.extra.mail.MailUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SMTP 邮件发送工具，从 Spring 配置、JVM 属性或环境变量读取连接参数并通过 Hutool 发信。
 */
@Component
public class EmailTool {

    @Value("${mail.smtp.host:}")
    private String configuredHost;
    @Value("${mail.smtp.port:}")
    private String configuredPort;
    @Value("${mail.smtp.username:}")
    private String configuredUsername;
    @Value("${mail.smtp.password:}")
    private String configuredPassword;
    @Value("${mail.smtp.from:}")
    private String configuredFrom;
    @Value("${mail.smtp.ssl:}")
    private String configuredSsl;

    /**
     * 校验收件人和正文，加载 SMTP 参数后发送文本或 HTML 邮件。
     *
     * @param to 收件人邮箱
     * @param subject 邮件主题
     * @param content 邮件正文
     * @param html 是否按 HTML 正文发送
     * @return 消息标识或不包含密码的错误信息
     */
    @Tool(description = "Send an email to a user via SMTP config from Spring properties or env/system properties", returnDirect = false)
    public String sendEmail(
            @ToolParam(description = "Receiver email address") String to,
            @ToolParam(description = "Email subject") String subject,
            @ToolParam(description = "Email body content") String content,
            @ToolParam(description = "Whether body is HTML, default false") Boolean html
    ) {
        if (StrUtil.isBlank(to)) {
            return "Error: receiver email 'to' is required.";
        }
        if (!Validator.isEmail(to)) {
            return "Error: receiver email format is invalid.";
        }
        if (StrUtil.isBlank(subject)) {
            return "Error: email subject is required.";
        }
        if (StrUtil.isBlank(content)) {
            return "Error: email content is required.";
        }

        String host = readConfig(configuredHost, "mail.smtp.host", "MAIL_SMTP_HOST", "SMTP_HOST");
        String portText = readConfig(configuredPort, "mail.smtp.port", "MAIL_SMTP_PORT", "SMTP_PORT");
        String username = readConfig(configuredUsername, "mail.smtp.username", "MAIL_SMTP_USERNAME", "SMTP_USERNAME");
        String password = readConfig(configuredPassword, "mail.smtp.password", "MAIL_SMTP_PASSWORD", "SMTP_PASSWORD");
        String from = readConfig(configuredFrom, "mail.smtp.from", "MAIL_SMTP_FROM", "SMTP_FROM");
        String sslText = readConfig(configuredSsl, "mail.smtp.ssl", "MAIL_SMTP_SSL", "SMTP_SSL");

        if (StrUtil.hasBlank(host, username, password, from)) {
            return "Error: missing SMTP config. Required: mail.smtp.host, mail.smtp.username, mail.smtp.password, mail.smtp.from"
                    + " (or env MAIL_SMTP_HOST/MAIL_SMTP_USERNAME/MAIL_SMTP_PASSWORD/MAIL_SMTP_FROM)."
                    + " Current loaded status => host=" + maskPresent(host)
                    + ", username=" + maskPresent(username)
                    + ", passwordSet=" + maskPresent(password)
                    + ", from=" + maskPresent(from);
        }

        int port = parsePortOrDefault(portText, 465);
        boolean ssl = parseBooleanOrDefault(sslText, true);

        MailAccount account = new MailAccount();
        account.setHost(host);
        account.setPort(port);
        account.setAuth(true);
        account.setUser(username);
        account.setPass(password);
        account.setFrom(from);
        account.setSslEnable(ssl);

        try {
            String messageId = MailUtil.send(account, to, subject, content, Boolean.TRUE.equals(html));
            return "Email sent successfully. to=" + to + ", messageId=" + messageId;
        } catch (Exception e) {
            return "Error sending email: " + e.getMessage();
        }
    }

    /**
     * 按 Spring 注入值、JVM 系统属性、环境变量的顺序读取配置。
     *
     * @param primaryValue Spring 已解析配置值
     * @param propertyKey JVM 系统属性名
     * @param envKeys 可兼容的环境变量名
     * @return 首个非空配置；均未设置时返回 {@code null}
     */
    private String readConfig(String primaryValue, String propertyKey, String... envKeys) {
        if (StrUtil.isNotBlank(primaryValue)) {
            return primaryValue;
        }
        String value = System.getProperty(propertyKey);
        if (StrUtil.isNotBlank(value)) {
            return value;
        }
        for (String envKey : envKeys) {
            value = System.getenv(envKey);
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 解析 SMTP 端口，空白或非数字配置使用调用方给定默认值。
     */
    private int parsePortOrDefault(String portText, int defaultPort) {
        if (StrUtil.isBlank(portText)) {
            return defaultPort;
        }
        try {
            return Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            return defaultPort;
        }
    }

    /**
     * 解析 true、1、yes 三种启用写法，空白时使用调用方默认值。
     */
    private boolean parseBooleanOrDefault(String value, boolean defaultValue) {
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    /**
     * 只输出配置是否存在，避免诊断文本泄露用户名、密码等敏感值。
     */
    private String maskPresent(String value) {
        return StrUtil.isBlank(value) ? "missing" : "set";
    }
}
