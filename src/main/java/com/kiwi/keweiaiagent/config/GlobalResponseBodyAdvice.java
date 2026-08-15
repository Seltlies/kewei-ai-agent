package com.kiwi.keweiaiagent.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kiwi.keweiaiagent.common.BaseResponse;
import org.reactivestreams.Publisher;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Controller 正常返回值的统一包装器。
 *
 * <p>普通对象转换为 {@code BaseResponse.success(body)}；已经包装的响应、SSE、响应式流和文件资源
 * 必须原样返回，否则会破坏流式传输或二进制下载。String 转换器需要先手工序列化，避免类型不匹配。</p>
 */
@RestControllerAdvice(basePackages = "com.kiwi.keweiaiagent.controller")
public class GlobalResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    /**
     * 对 Controller 包内的所有响应启用后置处理，具体排除逻辑在写出阶段判断。
     *
     * @param returnType 控制器方法返回类型
     * @param converterType 已选择的消息转换器类型
     * @return 始终为 {@code true}
     */
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    /**
     * 包装普通响应，同时保持 SSE、Publisher、Resource 与已经包装的响应协议不变。
     *
     * @param body 原始返回值
     * @param returnType 控制器方法信息
     * @param selectedContentType 协商后的媒体类型
     * @param selectedConverterType 实际消息转换器
     * @param request 当前请求
     * @param response 当前响应
     * @return 可交给消息转换器写出的最终响应体
     */
    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body instanceof SseEmitter
                || body instanceof Publisher<?>
                || body instanceof Resource
                || MediaType.TEXT_EVENT_STREAM.includes(selectedContentType)) {
            return body;
        }
        if (body instanceof BaseResponse<?>) {
            return body;
        }
        if (selectedConverterType == StringHttpMessageConverter.class) {
            try {
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                return OBJECT_MAPPER.writeValueAsString(BaseResponse.success(body));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("响应序列化失败", e);
            }
        }
        return BaseResponse.success(body);
    }
}
