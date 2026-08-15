package com.kiwi.keweiaiagent.agent;

import org.springaicommunity.agent.tools.AskUserQuestionTool;

import java.util.List;

/**
 * Agent 调用提问工具后主动中断当前推理轮次的控制流异常。
 *
 * <p>该异常不是系统故障。上层捕获后会保存待回答问题并把执行状态切换为等待用户输入，
 * 用户回答后再从同一会话恢复执行。</p>
 */
public class PendingUserQuestionException extends RuntimeException {

    private final List<AskUserQuestionTool.Question> questions;

    /**
     * 创建携带结构化问题列表的暂停信号。
     *
     * @param questions AskUserQuestionTool 生成、需要展示给用户的问题
     */
    public PendingUserQuestionException(List<AskUserQuestionTool.Question> questions) {
        super("Pending user input");
        this.questions = questions;
    }

    /**
     * 获取导致本轮执行暂停的问题列表。
     *
     * @return 待用户回答的问题
     */
    public List<AskUserQuestionTool.Question> getQuestions() {
        return questions;
    }
}
