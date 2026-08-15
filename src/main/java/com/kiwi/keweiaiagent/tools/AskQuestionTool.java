package com.kiwi.keweiaiagent.tools;

import com.kiwi.keweiaiagent.agent.ManusSessionStore;
import com.kiwi.keweiaiagent.agent.PendingUserQuestionException;
import jakarta.annotation.Resource;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 提问工具，负责将需要用户补充的信息转成结构化问题。
 */
@Component
public class AskQuestionTool {

    @Resource
    private ManusSessionStore manusSessionStore;

    /**
     * 创建社区 AskUserQuestionTool，并把问题处理回调接入当前 Manus 会话存储。
     *
     * @return 可注册给 Spring AI 的提问工具
     */
    @Bean
    public AskUserQuestionTool askUserQuestionTool() {
        return AskUserQuestionTool.builder()
                .questionHandler(this::handleQuestions)
                .build();
    }

    /**
     * 有已提交答案时消费并返回；否则保存问题并通过控制流异常暂停 Agent。
     *
     * @param questions 模型生成的结构化问题
     * @return 问题文本到答案的映射
     */
    private Map<String, String> handleQuestions(List<AskUserQuestionTool.Question> questions) {
        String sessionId = manusSessionStore.currentSessionId();
        if (sessionId == null) {
            throw new IllegalStateException("AskUserQuestionTool called without active Manus session");
        }
        Map<String, String> answers = manusSessionStore.consumeAnswers(sessionId);
        if (answers != null && !answers.isEmpty()) {
            manusSessionStore.clearPendingQuestions(sessionId);
            return new HashMap<>(answers);
        }
        manusSessionStore.savePendingQuestions(sessionId, questions);
        throw new PendingUserQuestionException(questions);
    }
}
