package com.kiwi.keweiaiagent.app;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 负责加载长期记忆系统提示词，并注入当前 memory 根目录。
 */
@Component
public class LongTermMemoryPromptService {

    private final Resource promptResource;
    private final Path memoriesRootPath;

    /**
     * @param promptResource classpath 中的长期记忆系统提示模板
     * @param memoriesRootPath 工具实际使用的记忆根目录
     */
    public LongTermMemoryPromptService(
            @Value("classpath:prompts/auto-memory-tools-system-prompt.md") Resource promptResource,
            @Qualifier("longTermMemoriesRootPath") Path memoriesRootPath
    ) {
        this.promptResource = promptResource;
        this.memoriesRootPath = memoriesRootPath;
    }

    /**
     * 读取 UTF-8 模板并替换记忆目录占位符。
     *
     * @return 可直接附加到模型系统消息的长期记忆提示词
     */
    public String buildPrompt() {
        try {
            String template = StreamUtils.copyToString(promptResource.getInputStream(), StandardCharsets.UTF_8);
            return template.replace("{{MEMORIES_ROOT_DIRECTORY}}", memoriesRootPath.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to load long-term memory prompt", e);
        }
    }
}
