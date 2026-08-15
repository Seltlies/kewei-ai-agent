package com.kiwi.keweiaiagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 长期记忆目录配置，负责初始化根目录与 MEMORY.md 索引。
 */
@Configuration
public class LongTermMemoryConfig {

    /**
     * 创建长期记忆根目录及初始 MEMORY.md 索引，并作为共享 Path Bean 提供给所有记忆工具。
     *
     * @param memoriesRootDirectory 外部配置的记忆目录
     * @return 绝对、规范化后的记忆根路径
     */
    @Bean(name = "longTermMemoriesRootPath")
    public Path longTermMemoriesRootPath(
            @Value("${app.long-term-memory.dir:${user.dir}/tmp/agent-memory}") String memoriesRootDirectory
    ) {
        try {
            Path rootPath = Path.of(memoriesRootDirectory).toAbsolutePath().normalize();
            Files.createDirectories(rootPath);
            Path memoryIndexPath = rootPath.resolve("MEMORY.md");
            if (Files.notExists(memoryIndexPath)) {
                Files.writeString(memoryIndexPath, "# Memory Index" + System.lineSeparator());
            }
            return rootPath;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize long-term memory directory", e);
        }
    }
}
