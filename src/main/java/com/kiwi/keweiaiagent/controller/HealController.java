package com.kiwi.keweiaiagent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用存活探针接口，用于人工检查或部署平台确认 Web 服务能够响应请求。
 */
@RestController
@RequestMapping("/health")
public class HealController {

    /**
     * 返回无需访问数据库和外部模型的轻量存活结果。
     *
     * @return 固定字符串 {@code OK}
     */
    @GetMapping
    public String healthCheck() {
        return "OK";
    }
}
