package com.kiwi.keweiaiagent.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kiwi.keweiaiagent.exception.ErrorCode;

/**
 * REST 接口统一响应信封。
 *
 * <p>业务成功时 {@code code=0}；失败时携带稳定错误码和可展示消息。
 * {@link JsonInclude} 会省略空数据字段，避免无返回值接口输出无意义的 {@code data:null}。</p>
 *
 * @param <T> 业务数据类型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BaseResponse<T> {

    private Integer code;
    private String message;
    private T data;

    /** 创建供序列化框架反射使用的空响应对象。 */
    public BaseResponse() {
    }

    /**
     * 创建完整响应。
     *
     * @param code 业务状态码
     * @param message 状态说明
     * @param data 业务数据
     */
    public BaseResponse(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 创建包含业务数据的成功响应。
     *
     * @param data 返回给调用方的数据
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(0, "success", data);
    }

    /**
     * 创建不包含业务数据的成功响应。
     *
     * @return 空数据成功响应
     */
    public static BaseResponse<Void> success() {
        return new BaseResponse<>(0, "success", null);
    }

    /**
     * 根据明确错误码和消息创建失败响应。
     *
     * @param code 业务错误码
     * @param message 错误说明
     * @param <T> 响应数据泛型
     * @return 失败响应
     */
    public static <T> BaseResponse<T> fail(Integer code, String message) {
        return new BaseResponse<>(code, message, null);
    }

    /**
     * 根据错误码枚举创建失败响应。
     *
     * @param errorCode 统一错误定义
     * @param <T> 响应数据泛型
     * @return 失败响应
     */
    public static <T> BaseResponse<T> fail(ErrorCode errorCode) {
        return new BaseResponse<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** @return 业务状态码 */
    public Integer getCode() {
        return code;
    }

    /** @param code 业务状态码 */
    public void setCode(Integer code) {
        this.code = code;
    }

    /** @return 状态说明 */
    public String getMessage() {
        return message;
    }

    /** @param message 状态说明 */
    public void setMessage(String message) {
        this.message = message;
    }

    /** @return 业务数据 */
    public T getData() {
        return data;
    }

    /** @param data 业务数据 */
    public void setData(T data) {
        this.data = data;
    }
}
