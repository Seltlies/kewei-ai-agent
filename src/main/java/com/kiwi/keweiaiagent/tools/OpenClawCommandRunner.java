package com.kiwi.keweiaiagent.tools;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * OpenClaw 命令执行接口，抽象外部研究命令的运行方式。
 */
public interface OpenClawCommandRunner {

    /**
     * 执行参数化命令并在限定时间内收集标准输出与错误输出。
     *
     * @param command 已拆分的命令及参数，避免通过 shell 拼接执行
     * @param timeout 最大运行时间
     * @return 退出码及双输出流内容
     * @throws IOException 进程创建或输出读取失败时抛出
     * @throws InterruptedException 当前线程等待期间被中断时抛出
     */
    CommandResult run(List<String> command, Duration timeout) throws IOException, InterruptedException;

    /**
     * 命令执行结果对象，封装退出码、标准输出和错误输出。
     */
    record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
