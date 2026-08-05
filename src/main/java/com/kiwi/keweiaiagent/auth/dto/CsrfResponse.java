package com.kiwi.keweiaiagent.auth.dto;

/**
 * CSRF Token 响应。前端只在内存保存 token，并在修改请求的 X-CSRF-TOKEN 请求头中回传。
 */
public record CsrfResponse(String headerName, String parameterName, String token) {
}
