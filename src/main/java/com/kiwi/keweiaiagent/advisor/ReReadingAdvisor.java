package com.kiwi.keweiaiagent.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.util.Assert;

import java.util.Map;

/**
 * 提示词重读增强器，负责在调用前对用户问题进行二次强调。
 */
public class ReReadingAdvisor implements BaseAdvisor {

	private static final String DEFAULT_RE2_ADVISE_TEMPLATE = """
            {re2_input_query}
            Read the question again: {re2_input_query}
			""";

	private final String re2AdviseTemplate;

	private int order = 0;

	/** 使用默认 Re2 模板创建增强器。 */
	public ReReadingAdvisor() {
		this(DEFAULT_RE2_ADVISE_TEMPLATE);
	}

	/**
	 * @param re2AdviseTemplate 必须包含 {@code re2_input_query} 变量的提示模板
	 */
	public ReReadingAdvisor(String re2AdviseTemplate) {
		Assert.hasText(re2AdviseTemplate, "re2AdviseTemplate must not be blank");
		this.re2AdviseTemplate = re2AdviseTemplate;
	}

	/**
	 * 重复拼接原始问题并把原文放入 Advisor context，供后续日志 Advisor 对照。
	 */
	@Override
	public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
		String userText = chatClientRequest.prompt().getUserMessage() == null
			? ""
			: chatClientRequest.prompt().getUserMessage().getText();

		String augmentedUserText = PromptTemplate.builder()
			.template(this.re2AdviseTemplate)
			.variables(Map.of("re2_input_query", userText))
			.build()
			.render();

        ChatClientRequest ret = chatClientRequest.mutate()
                .prompt(chatClientRequest.prompt().augmentUserMessage(augmentedUserText))
                .build();

        // 存放原始用户输入，供后续advisor使用,在 Advisor 链中共享状态
        ret.context().put("userText", userText);
        return ret;

    }

	/** 响应阶段不做变换，原样交给后续 Advisor。 */
	@Override
	public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
		return chatClientResponse;
	}

	/** @return 当前 Advisor 链顺序 */
	@Override
	public int getOrder() {
		return this.order;
	}

	/**
	 * 设置 Advisor 顺序并返回自身，便于构建客户端时链式配置。
	 *
	 * @param order 链顺序，值越小越先执行
	 * @return 当前增强器
	 */
	public ReReadingAdvisor withOrder(int order) {
		this.order = order;
		return this;
	}
}
