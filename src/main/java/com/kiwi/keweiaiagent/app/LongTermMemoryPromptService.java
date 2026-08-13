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
 * 长期记忆系统提示词加载器。
 *
 * <p>该类的职责是生成一段“如何使用长期记忆”的系统提示词，它本身不负责回答用户问题，
 * 也不直接读写长期记忆文件。真正的记忆读写由 MemoryView、MemoryCreate 等工具完成。</p>
 *
 * <p>Spring Boot 启动时会因为 {@link Component} 注解创建该类的 Bean，同时注入提示词模板
 * 和长期记忆根目录。ManusSessionService 创建 KeweiManus 时会调用 {@link #buildPrompt()}，
 * 再将生成结果追加到 KeweiManus 的系统提示词中。</p>
 */
@Component
public class LongTermMemoryPromptService {

    /**
     * 长期记忆使用规则的模板文件。
     *
     * <p>该资源指向 classpath 中的 auto-memory-tools-system-prompt.md，模板内容会告诉大模型
     * 什么信息适合作为长期记忆，以及如何调用长期记忆工具。</p>
     */
    private final Resource promptResource;

    /**
     * 当前应用的长期记忆根目录。
     *
     * <p>该路径由 LongTermMemoryConfig 创建并以 longTermMemoriesRootPath 为名注册到 Spring 容器，
     * 目录中的 MEMORY.md 用作长期记忆索引。</p>
     */
    private final Path memoriesRootPath;

    /**
     * 初始化长期记忆提示词加载器。
     *
     * <p>该构造方法由 Spring 在应用启动时自动调用：{@link Value} 负责加载 classpath 中的
     * 提示词模板，{@link Qualifier} 负责精确选择长期记忆根目录 Bean。</p>
     *
     * @param promptResource 长期记忆使用规则的 classpath 模板资源
     * @param memoriesRootPath 长期记忆文件所在的根目录
     */
    public LongTermMemoryPromptService(
            @Value("classpath:prompts/auto-memory-tools-system-prompt.md") Resource promptResource,
            @Qualifier("longTermMemoriesRootPath") Path memoriesRootPath
    ) {
        this.promptResource = promptResource;
        this.memoriesRootPath = memoriesRootPath;
    }

    /**
     * 生成可直接拼入 KeweiManus 系统提示词的长期记忆使用说明。
     *
     * <p>例如用户询问“情侣冷战如何破冰”时，ManusSessionService 会先调用该方法生成长期记忆
     * 规则，再用这段规则创建 KeweiManus。如果记忆中存在与用户沟通偏好或关系背景相关的
     * 稳定信息，大模型可以按照该提示词调用记忆工具读取；如果没有相关记忆，则仅根据
     * 当前问题处理。该方法只生成使用规则，不会主动读取 MEMORY.md。</p>
     *
     * @return 已将记忆根目录填入模板的完整系统提示词
     * @throws UncheckedIOException 提示词模板无法读取时抛出
     */
    public String buildPrompt() {
        try {
            // 使用 UTF-8 读取完整模板，保证提示词中的中英文内容都能正确解析。
            String template = StreamUtils.copyToString(promptResource.getInputStream(), StandardCharsets.UTF_8);

            // 将模板占位符替换为当前运行环境的真实记忆目录，让大模型和记忆工具使用同一个根路径。
            return template.replace("{{MEMORIES_ROOT_DIRECTORY}}", memoriesRootPath.toString());
        } catch (IOException e) {
            // 模板是创建 KeweiManus 所必需的系统配置，读取失败时立即终止本次 Agent 创建。
            throw new UncheckedIOException("Unable to load long-term memory prompt", e);
        }
    }
}
