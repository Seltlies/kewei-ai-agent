package com.kiwi.keweiaiagent.demo.invoke;// 建议dashscope SDK的版本 >= 2.12.0
import java.util.Arrays;
import java.lang.System;
import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.JsonUtils;

/**
 * 阿里云sdk调用
 */
public class SdkAiInvoke{
    /**
     * 使用 DashScope 原生 SDK 调用 qwen-plus，演示最小系统消息和用户消息请求。
     *
     * @return DashScope 原始生成结果
     */
    public static GenerationResult callWithMessage() throws ApiException, NoApiKeyException, InputRequiredException {
        Generation gen = new Generation();
        Message systemMsg = Message.builder()
                .role(Role.SYSTEM.getValue())
                .content("You are a helpful assistant.")
                .build();
        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content("你是谁？")
                .build();
        GenerationParam param = GenerationParam.builder()
                // 若没有配置环境变量，请用百炼API Key将下行替换为：.apiKey("sk-xxx")
                .apiKey(resolveApiKey())
                // 此处以qwen-plus为例，可按需更换模型名称。模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models
                .model("qwen-plus")
                .messages(Arrays.asList(systemMsg, userMsg))
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .build();
        return gen.call(param);
    }

    /**
     * 优先读取 JVM 属性，其次读取 DASHSCOPE_API_KEY 环境变量。
     *
     * @return 百炼 API Key
     */
    private static String resolveApiKey() {
        String apiKey = System.getProperty("dashscope.api-key");
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        return System.getenv("DASHSCOPE_API_KEY");
    }

    /**
     * 独立运行原生 SDK 示例并输出 JSON 结果。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        try {
            GenerationResult result = callWithMessage();
            System.out.println(JsonUtils.toJson(result));
        } catch (ApiException | NoApiKeyException | InputRequiredException e) {
            // 使用日志框架记录异常信息
            System.err.println("An error occurred while calling the generation service: " + e.getMessage());
        }
        System.exit(0);
    }
}
