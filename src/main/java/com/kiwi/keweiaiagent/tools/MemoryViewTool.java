package com.kiwi.keweiaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 查看长期记忆目录或具体记忆文件。
 */
@Component
public class MemoryViewTool {

    private final MemoryToolSupport support;

    /** @param memoriesRootPath 长期记忆沙箱根目录 */
    public MemoryViewTool(@Qualifier("longTermMemoriesRootPath") Path memoriesRootPath) {
        this.support = new MemoryToolSupport(memoriesRootPath);
    }

    /**
     * 对目录执行两层列表，对文件返回带行号内容。
     *
     * @param relativePath 相对文件或目录；空白表示根目录
     * @return 目录列表、文件内容或错误文本
     */
    @Tool(name = "MemoryView", description = "Read a memory file with line numbers, or list the memory directory up to two levels deep", returnDirect = false)
    public String memoryView(
            @ToolParam(description = "Relative memory file or directory path. Use '.' or blank to inspect the memory root.") String relativePath
    ) {
        try {
            Path path = support.resolve(relativePath);
            if (Files.isDirectory(path)) {
                return support.listDirectory(path);
            }
            return support.renderFileWithLineNumbers(path);
        } catch (Exception e) {
            return "Error viewing memory: " + e.getMessage();
        }
    }
}
