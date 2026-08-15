package com.kiwi.keweiaiagent.exception;

import org.springframework.http.HttpStatus;

/**
 * 可预期业务失败的统一异常。
 *
 * <p>异常同时携带业务错误码和 HTTP 状态，上抛到
 * {@link GlobalExceptionHandler} 后会转换为统一的 BaseResponse，不应把它用于程序缺陷。</p>
 */
public class BusinessException extends RuntimeException {

    private final Integer code;
    private final HttpStatus httpStatus;

    /**
     * 使用系统错误码创建异常，适用于只有动态错误消息的旧调用点。
     *
     * @param message 错误说明
     */
    public BusinessException(String message) {
        this(ErrorCode.SYSTEM_ERROR, message);
    }

    /**
     * 根据数值错误码创建异常；50000 以上映射为 500，其余映射为 400。
     *
     * @param code 业务错误码
     * @param message 错误说明
     */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.httpStatus = code != null && code >= 50000
                ? HttpStatus.INTERNAL_SERVER_ERROR
                : HttpStatus.BAD_REQUEST;
    }

    /**
     * 使用统一错误定义创建异常。
     *
     * @param errorCode 错误码、默认消息及 HTTP 状态定义
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.httpStatus = errorCode.getHttpStatus();
    }

    /**
     * 使用统一错误定义但覆盖默认展示消息。
     *
     * @param errorCode 错误定义
     * @param message 更具体的错误说明
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
        this.httpStatus = errorCode.getHttpStatus();
    }

    /**
     * 创建保留根因的业务异常，便于日志追踪外部依赖故障。
     *
     * @param errorCode 错误定义
     * @param message 更具体的错误说明
     * @param cause 原始异常
     */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
        this.httpStatus = errorCode.getHttpStatus();
    }

    /** @return 稳定的业务错误码 */
    public Integer getCode() {
        return code;
    }

    /** @return 接口应返回的 HTTP 状态 */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
