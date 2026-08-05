package com.kiwi.keweiaiagent.account.service;

import com.kiwi.keweiaiagent.exception.BusinessException;
import com.kiwi.keweiaiagent.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 账号与密码规则入口。注册、登录规范化和初始 admin 初始化复用同一组规则，避免不同入口
 * 对账号大小写、空格和密码强度产生不一致判断。
 */
public final class AccountRules {

    private static final Pattern ACCOUNT_PATTERN = Pattern.compile(
            "^[\\p{IsHan}A-Za-z0-9][\\p{IsHan}A-Za-z0-9_.-]{1,30}[\\p{IsHan}A-Za-z0-9]$"
    );
    private static final Pattern ENGLISH_LETTER_PATTERN = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT_PATTERN = Pattern.compile("[0-9]");

    private AccountRules() {
    }

    /**
     * 清理并校验注册或初始化账号，返回展示值和用于唯一查询的规范值。
     */
    public static AccountValue validateAccount(String rawAccount) {
        String displayAccount = rawAccount == null ? "" : rawAccount.strip();
        int characterCount = displayAccount.codePointCount(0, displayAccount.length());
        if (characterCount < 3 || characterCount > 32 || !ACCOUNT_PATTERN.matcher(displayAccount).matches()) {
            throw new BusinessException(
                    ErrorCode.INVALID_PARAM,
                    "账号需为 3～32 个字符，仅支持中文、英文字母、数字、下划线、短横线和英文句点，且首尾不能为符号"
            );
        }
        return new AccountValue(displayAccount, displayAccount.toLowerCase(Locale.ROOT));
    }

    /**
     * 登录查询只清理首尾空格和英文字母大小写，不返回具体格式错误，避免通过差异提示探测账号。
     */
    public static String normalizeForLogin(String rawAccount) {
        return rawAccount == null ? "" : rawAccount.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 校验公开注册密码必须不少于 6 个字符，并同时包含英文字母和数字。
     */
    public static void validateUserPassword(String password) {
        int characterCount = password == null ? 0 : password.codePointCount(0, password.length());
        int utf8ByteCount = password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
        if (characterCount < 6
                || utf8ByteCount > 72
                || !ENGLISH_LETTER_PATTERN.matcher(password).find()
                || !DIGIT_PATTERN.matcher(password).find()) {
            throw new BusinessException(
                    ErrorCode.INVALID_PARAM,
                    "密码至少 6 位、最多 72 个 UTF-8 字节，且必须同时包含英文字母和数字"
            );
        }
    }

    /**
     * 校验初始 admin 密码必须不少于 12 个字符，并同时包含英文字母、数字和非字母数字字符。
     */
    public static void validateBootstrapAdminPassword(String password) {
        int characterCount = password == null ? 0 : password.codePointCount(0, password.length());
        int utf8ByteCount = password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
        boolean hasSpecialCharacter = password != null
                && password.codePoints().anyMatch(codePoint -> !Character.isLetterOrDigit(codePoint));
        if (characterCount < 12
                || utf8ByteCount > 72
                || !ENGLISH_LETTER_PATTERN.matcher(password).find()
                || !DIGIT_PATTERN.matcher(password).find()
                || !hasSpecialCharacter) {
            throw new BusinessException(
                    ErrorCode.BOOTSTRAP_ADMIN_INVALID,
                    "初始 admin 密码至少 12 位、最多 72 个 UTF-8 字节，且必须同时包含英文字母、数字和特殊字符"
            );
        }
    }

    /**
     * 账号清理结果：displayAccount 用于页面展示，normalizedAccount 用于登录和唯一索引。
     */
    public record AccountValue(String displayAccount, String normalizedAccount) {
    }
}
