package com.kiwi.keweiaiagent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 基于 ProcessBuilder 的 OpenClaw 命令执行器，并行读取 stdout/stderr 以防进程管道阻塞。
 */
@Component
@Slf4j
public class ShellOpenClawCommandRunner implements OpenClawCommandRunner {

    private final File workingDirectory;

    /** 使用应用工作目录创建命令执行器。 */
    public ShellOpenClawCommandRunner() {
        this(new File(System.getProperty("user.dir")));
    }

    /** @param workingDirectory 外部命令工作目录 */
    ShellOpenClawCommandRunner(File workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /**
     * 启动外部进程、并行读取双输出流，并在超时时强制终止进程。
     */
    @Override
    public CommandResult run(List<String> command, Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDirectory);
        long startNanos = System.nanoTime();
        log.info("Starting OpenClaw command. cwd={}, timeoutSeconds={}, command={}",
                workingDirectory.getAbsolutePath(), timeout.toSeconds(), command);
        Process process = processBuilder.start();
        CompletableFuture<String> stdoutFuture = readStreamAsync(process.getInputStream());
        CompletableFuture<String> stderrFuture = readStreamAsync(process.getErrorStream());
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            String stdout = awaitStream(stdoutFuture);
            String stderr = awaitStream(stderrFuture);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            log.warn("OpenClaw command timed out after {} ms. command={}, stdoutPreview={}, stderrPreview={}",
                    elapsedMs, command, preview(stdout), preview(stderr));
            return new CommandResult(124, stdout, firstNonBlank(stderr, "Timed out after " + timeout.toSeconds() + " seconds"));
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        String stdout = awaitStream(stdoutFuture);
        String stderr = awaitStream(stderrFuture);
        log.info("OpenClaw command finished. exitCode={}, elapsedMs={}, stdoutPreview={}, stderrPreview={}",
                process.exitValue(),
                elapsedMs,
                preview(stdout),
                preview(stderr));
        return new CommandResult(process.exitValue(), stdout, stderr);
    }

    /**
     * 异步完整读取进程输出流，避免一个管道写满导致另一个管道无法继续。
     */
    private CompletableFuture<String> readStreamAsync(InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                in.transferTo(out);
                return out.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 最多等待五秒取得输出，并还原异步任务中的 IOException。
     */
    private String awaitStream(CompletableFuture<String> future) throws IOException, InterruptedException {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException && runtimeException.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to read process output", cause);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("Timed out while reading process output", e);
        }
    }

    /** 从候选文本中返回首个非空值。 */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    /** 将外部命令输出压缩为最多 400 字符的日志预览。 */
    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 400 ? normalized : normalized.substring(0, 400) + "...";
    }
}
