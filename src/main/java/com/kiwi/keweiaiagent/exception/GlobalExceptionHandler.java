package com.kiwi.keweiaiagent.exception;

import com.kiwi.keweiaiagent.common.BaseResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Controller 层全局异常到统一 REST 协议的转换器。
 *
 * <p>处理器按异常具体程度匹配：业务异常保留其错误语义，参数和上传错误转换为 4xx，
 * 最后的通用处理器记录堆栈并返回不泄露内部细节的 500 响应。</p>
 */
@RestControllerAdvice(basePackages = "com.kiwi.keweiaiagent.controller")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 保留业务异常预先定义的 HTTP 状态、错误码和动态消息。
     *
     * @param e 可预期的业务异常
     * @return 统一失败响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<BaseResponse<Void>> handleBusinessException(BusinessException e) {
        return ResponseEntity.status(e.getHttpStatus())
                .body(BaseResponse.fail(e.getCode(), e.getMessage()));
    }

    /**
     * 处理 {@code @Valid} 请求体校验失败，优先返回首个字段错误。
     *
     * @param e 方法参数校验异常
     * @return 400 参数错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<BaseResponse<Void>> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldError() != null
                ? e.getBindingResult().getFieldError().getDefaultMessage()
                : ErrorCode.INVALID_PARAM.getMessage();
        return ResponseEntity.badRequest()
                .body(BaseResponse.fail(ErrorCode.INVALID_PARAM.getCode(), message));
    }

    /**
     * 处理查询对象或表单对象的数据绑定失败。
     *
     * @param e Spring 数据绑定异常
     * @return 400 参数绑定错误响应
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<BaseResponse<Void>> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldError() != null
                ? e.getBindingResult().getFieldError().getDefaultMessage()
                : ErrorCode.PARAM_BIND_ERROR.getMessage();
        return ResponseEntity.badRequest()
                .body(BaseResponse.fail(ErrorCode.PARAM_BIND_ERROR.getCode(), message));
    }

    /**
     * 处理 JSON 缺失、类型不兼容或语法错误导致的请求体反序列化失败。
     *
     * @param e 请求体读取异常
     * @return 400 请求体格式错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<BaseResponse<Void>> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(BaseResponse.fail(ErrorCode.REQUEST_BODY_FORMAT_ERROR));
    }

    /**
     * 路径参数或独立查询参数类型错误时返回稳定的 400 响应，避免非法账号 ID 等输入穿透为 500。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<BaseResponse<Void>> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException e
    ) {
        String requiredType = e.getRequiredType() == null ? "unknown" : e.getRequiredType().getSimpleName();
        log.warn("请求参数类型绑定失败，parameter={}，requiredType={}", e.getName(), requiredType);
        return ResponseEntity.badRequest()
                .body(BaseResponse.fail(ErrorCode.PARAM_BIND_ERROR));
    }

    /**
     * Multipart 文件在进入 Controller 参数绑定前由 Servlet 容器检查大小。这里单独处理上传超限异常，
     * 返回稳定的 413 响应和明确提示，避免被通用异常处理器包装成无法定位原因的系统异常。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<BaseResponse<Void>> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException e
    ) {
        log.warn("聊天图片上传超过 10 MB 限制，maxUploadSize={}", e.getMaxUploadSize());
        return ResponseEntity.status(ErrorCode.UPLOAD_TOO_LARGE.getHttpStatus())
                .body(BaseResponse.fail(ErrorCode.UPLOAD_TOO_LARGE));
    }

    /**
     * 记录未被具体规则覆盖的程序或外部依赖异常，并隐藏内部堆栈细节。
     *
     * @param e 未处理异常
     * @return 500 系统错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<Void>> handleException(Exception e) {
        log.error("未处理的系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(BaseResponse.fail(ErrorCode.SYSTEM_ERROR));
    }

    /**
     * 处理浏览器关闭连接引发的 SSE 写入终止，该情况属于客户端生命周期事件而非业务失败。
     *
     * @param e 异步响应已无法写入的异常
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsableException(AsyncRequestNotUsableException e) {
        // SSE 客户端主动断开时（浏览器关闭、前端主动 close）属于预期行为，不再包装成 JSON 响应
        log.info("SSE client disconnected: {}", e.getMessage());
    }
}
