package com.kiwi.keweiaiagent.exception;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {

    private final Integer code;
    private final HttpStatus httpStatus;

    public BusinessException(String message) {
        this(ErrorCode.SYSTEM_ERROR, message);
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.httpStatus = code != null && code >= 50000
                ? HttpStatus.INTERNAL_SERVER_ERROR
                : HttpStatus.BAD_REQUEST;
    }

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.httpStatus = errorCode.getHttpStatus();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
        this.httpStatus = errorCode.getHttpStatus();
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
        this.httpStatus = errorCode.getHttpStatus();
    }

    public Integer getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
