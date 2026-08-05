package com.kiwi.keweiaiagent.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    SUCCESS(0, "success", HttpStatus.OK),

    INVALID_PARAM(40001, "请求参数错误", HttpStatus.BAD_REQUEST),
    PARAM_BIND_ERROR(40002, "请求参数绑定失败", HttpStatus.BAD_REQUEST),
    REQUEST_BODY_FORMAT_ERROR(40003, "请求体格式错误", HttpStatus.BAD_REQUEST),
    AGENT_BUSY(40010, "Agent当前非空闲状态，无法重复执行", HttpStatus.CONFLICT),
    UNAUTHENTICATED(40100, "请先登录后访问", HttpStatus.UNAUTHORIZED),
    ACCOUNT_OR_PASSWORD_ERROR(40101, "账号或密码错误", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(40300, "当前账号无权执行该操作", HttpStatus.FORBIDDEN),
    ACCOUNT_DISABLED(40301, "账号已停用，请联系管理员", HttpStatus.FORBIDDEN),
    ACCOUNT_EXISTS(40901, "该账号已存在，请更换账号", HttpStatus.CONFLICT),
    BOOTSTRAP_ADMIN_INVALID(40902, "初始 admin 配置不符合要求", HttpStatus.CONFLICT),

    AGENT_RUN_FAILED(50010, "Agent执行失败", HttpStatus.INTERNAL_SERVER_ERROR),
    SYSTEM_ERROR(50000, "系统异常", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
