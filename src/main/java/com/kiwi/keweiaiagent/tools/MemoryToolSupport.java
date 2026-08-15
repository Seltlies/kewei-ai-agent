package com.kiwi.keweiaiagent.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 长期记忆工具的共享文件操作与沙箱校验能力。
 */
final class MemoryToolSupport {

    private final Path memoriesRootPath;

    /**
     * 固定并创建长期记忆根目录，后续所有相对路径都以该目录为边界。
     *
     * @param memoriesRootPath 外部配置的记忆根目录
     */
    MemoryToolSupport(Path memoriesRootPath) {
        this.memoriesRootPath = memoriesRootPath.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.memoriesRootPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to initialize memories root directory", e);
        }
    }

    /**
     * 将相对路径解析为规范化物理路径，并拒绝逃逸根目录的路径穿越。
     *
     * @param relativePath 模型提供的相对路径
     * @return 根目录内的绝对规范化路径
     */
    Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank() || ".".equals(relativePath.trim())) {
            return memoriesRootPath;
        }
        Path resolved = memoriesRootPath.resolve(relativePath.trim()).normalize();
        if (!resolved.startsWith(memoriesRootPath)) {
            throw new IllegalArgumentException("Path '" + relativePath + "' is outside memory root");
        }
        return resolved;
    }

    /**
     * 将物理路径转换为不暴露服务器根路径的相对展示文本。
     *
     * @param path 根目录内路径
     * @return 使用正斜杠的相对路径
     */
    String display(Path path) {
        if (path.equals(memoriesRootPath)) {
            return ".";
        }
        return memoriesRootPath.relativize(path).toString().replace('\\', '/');
    }

    /**
     * 最多向下两层列出目录内容，并为子目录附加斜杠。
     *
     * @param directory 已通过沙箱校验的目录
     * @return 按路径排序的目录文本
     * @throws IOException 文件系统遍历失败时抛出
     */
    String listDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            throw new IllegalArgumentException("Memory path does not exist: " + display(directory));
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Memory path is not a directory: " + display(directory));
        }
        try (Stream<Path> stream = Files.walk(directory, 2)) {
            List<String> entries = stream
                    .filter(path -> !path.equals(directory))
                    .sorted()
                    .map(path -> display(path) + (Files.isDirectory(path) ? "/" : ""))
                    .toList();
            if (entries.isEmpty()) {
                return "Memory directory is empty: " + display(directory);
            }
            return entries.stream().collect(Collectors.joining(System.lineSeparator()));
        }
    }

    /**
     * 读取文本文件并添加从 1 开始的行号，便于模型执行精确插入与替换。
     *
     * @param filePath 已通过沙箱校验的文件
     * @return 带行号内容
     * @throws IOException 文件读取失败时抛出
     */
    String renderFileWithLineNumbers(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("Memory path does not exist: " + display(filePath));
        }
        if (Files.isDirectory(filePath)) {
            throw new IllegalArgumentException("Memory path is a directory: " + display(filePath));
        }
        List<String> lines = Files.readAllLines(filePath);
        if (lines.isEmpty()) {
            return "1: ";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                builder.append(System.lineSeparator());
            }
            builder.append(i + 1).append(": ").append(lines.get(i));
        }
        return builder.toString();
    }

    /** 确保目标路径的父目录存在。 */
    void ensureParentDirectory(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /** 将文本写入记忆文件，空内容按空字符串处理。 */
    void writeFile(Path filePath, String content) throws IOException {
        ensureParentDirectory(filePath);
        Files.writeString(filePath, content == null ? "" : content);
    }

    /** 读取已存在的记忆文件为行列表，并拒绝目录。 */
    List<String> readLines(Path filePath) throws IOException {
        if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
            throw new IllegalArgumentException("Memory file does not exist: " + display(filePath));
        }
        return Files.readAllLines(filePath);
    }

    /** 使用系统换行符把行列表写回记忆文件。 */
    void writeLines(Path filePath, List<String> lines) throws IOException {
        ensureParentDirectory(filePath);
        Files.writeString(filePath, String.join(System.lineSeparator(), lines));
    }

    /**
     * 统计目标文本的非重叠出现次数，供“唯一精确替换”规则校验。
     *
     * @param content 完整文件内容
     * @param target 目标文本
     * @return 非重叠出现次数
     */
    int countOccurrences(String content, String target) {
        int count = 0;
        int fromIndex = 0;
        while (true) {
            int found = content.indexOf(target, fromIndex);
            if (found < 0) {
                return count;
            }
            count++;
            fromIndex = found + target.length();
        }
    }

    /**
     * 按子节点在前的顺序递归删除文件或目录。
     *
     * @param path 已通过沙箱校验的目标路径
     * @throws IOException 删除失败时抛出
     */
    void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Memory path does not exist: " + display(path));
        }
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path current : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(current);
            }
        }
    }

    /**
     * 在长期记忆根目录内移动或重命名路径，目标存在时进行替换。
     *
     * @param source 已存在源路径
     * @param target 目标路径
     * @throws IOException 移动失败时抛出
     */
    void move(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            throw new IllegalArgumentException("Memory path does not exist: " + display(source));
        }
        ensureParentDirectory(target);
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
