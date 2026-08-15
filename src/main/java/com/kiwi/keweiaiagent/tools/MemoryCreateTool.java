package com.kiwi.keweiaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 创建新的长期记忆文件。
 */
@Component
public class MemoryCreateTool {

    private final MemoryToolSupport support;

    /** @param memoriesRootPath 长期记忆沙箱根目录 */
    public MemoryCreateTool(@Qualifier("longTermMemoriesRootPath") Path memoriesRootPath) {
        this.support = new MemoryToolSupport(memoriesRootPath);
    }

    /**
     * 在沙箱内创建新的记忆文件，已存在的路径不会被覆盖。
     *
     * @param relativePath 相对记忆路径
     * @param content 初始内容
     * @return 创建结果或错误文本
     */
    @Tool(name = "MemoryCreate", description = "Create a new memory file inside the sandboxed memories directory", returnDirect = false)
    public String memoryCreate(
            @ToolParam(description = "Relative path of the memory file to create") String relativePath,
            @ToolParam(description = "Content to write into the new memory file") String content
    ) {
        try {
            Path path = support.resolve(relativePath);
            if (Files.exists(path)) {
                return "Error creating memory: memory path already exists: " + support.display(path);
            }
            support.writeFile(path, content);
            return "Created memory file: " + support.display(path);
        } catch (Exception e) {
            return "Error creating memory: " + e.getMessage();
        }
    }
}
