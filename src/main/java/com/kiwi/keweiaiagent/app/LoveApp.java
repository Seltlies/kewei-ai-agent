package com.kiwi.keweiaiagent.app;
import com.kiwi.keweiaiagent.advisor.MyLoggerAdvisor;
import com.kiwi.keweiaiagent.advisor.ReReadingAdvisor;
import com.kiwi.keweiaiagent.query.QueryPreprocessor;
import com.kiwi.keweiaiagent.rag.factory.loveapp.LoveAppRetrievalAugmentationAdvisorFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ai.rag.Query;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * 恋爱咨询应用编排入口。
 *
 * <p>该类展示 Spring AI 的多种调用模式：同步/流式聊天、会话记忆、多模态图片、结构化输出、
 * PgVector RAG、普通工具、Skills 和 MCP。Controller 负责权限与会话归属，本类聚焦模型调用和结果转换。</p>
 */
@Component
@Slf4j
public class LoveApp {

    /** 技能调用结果类型，用于区分普通文本、待确认问题和生成文件。 */
    public enum SkillChatResultType {
        TEXT,
        QUESTION,
        FILE
    }

    /**
     * 技能模型结果的受控业务模型，避免 Controller 直接解释模型原始字符串。
     *
     * @param type 结果类型
     * @param content 普通文本或安全完成文案
     * @param filePath 待业务层登记的服务器文件路径，不直接返回浏览器
     * @param question 需要用户补充的信息
     * @param attachmentId 已登记附件标识
     * @param contentUrl 受权限保护的附件访问地址
     */
    public record SkillChatResult(
            SkillChatResultType type,
            String content,
            String filePath,
            String question,
            String attachmentId,
            String contentUrl
    ) {
        /** @return 普通文本结果 */
        public static SkillChatResult text(String content) {
            return new SkillChatResult(SkillChatResultType.TEXT, content, null, null, null, null);
        }

        /** @return 等待用户回答的问题结果 */
        public static SkillChatResult question(String question) {
            return new SkillChatResult(SkillChatResultType.QUESTION, null, null, question, null, null);
        }

        /** @return 等待业务层进行安全登记的文件结果 */
        public static SkillChatResult file(String filePath) {
            return new SkillChatResult(SkillChatResultType.FILE, null, filePath, null, null, null);
        }

        /**
         * 将暂未提供受控下载能力的非图片文件转换为无服务器路径的完成结果。
         */
        public static SkillChatResult securedFile() {
            return new SkillChatResult(SkillChatResultType.FILE, "文件已生成", null, null, null, null);
        }

        /**
         * 将 AI 生成图片转换为受控附件响应，不再向浏览器返回服务器本地路径。
         */
        public static SkillChatResult attachment(String attachmentId, String contentUrl) {
            return new SkillChatResult(
                    SkillChatResultType.FILE, null, null, null, attachmentId, contentUrl);
        }
    }

    private final ChatClient chatClient;
    /**
     * 技能调用专用客户端不自动写入 ChatMemory。模型可能返回服务器本地文件路径，必须由
     * 控制器完成附件登记后再显式保存受控结果，避免原始路径短暂或永久进入历史消息。
     */
    private final ChatClient skillChatClient;
    private final ChatMemory chatMemory;
    private final QueryPreprocessor queryPreprocessor;
    /**
     * 长期记忆工具集合，用于在普通对话中启用跨会话记忆。
     */
    private final ToolCallback[] memoryTools;


    private static final String SYSTEM_PROMPT = "扮演深耕恋爱心理领域的专家。开场向用户表明身份，告知用户可倾诉恋爱难题。" +
            "围绕单身、恋爱、已婚三种状态提问：单身状态询问社交圈拓展及追求心仪对象的困扰；" +
            "恋爱状态询问沟通、习惯差异引发的矛盾；已婚状态询问家庭责任与亲属关系处理的问题。" +
            "引导用户详述事情经过、对方反应及自身想法，以便给出专属解决方案。";

    /**
     * 使用 Spring 容器提供的百炼聊天模型创建应用级 {@link ChatClient}，并装配会话记忆、
     * 查询预处理、长期记忆提示词和记忆工具。聊天模型的具体端点与模型名称统一由
     * {@code spring.ai.dashscope} 配置管理，业务代码不直接持有 API Key。
     *
     * @param chatModel 百炼 DashScope 聊天模型
     * @param chatMemory 会话记忆组件
     * @param queryPreprocessor RAG 查询预处理组件
     * @param longTermMemoryPromptService 长期记忆提示词服务
     * @param memoryTools 长期记忆工具集合
     */
    @Autowired
    public LoveApp(ChatModel chatModel,
                   ChatMemory chatMemory,
                   QueryPreprocessor queryPreprocessor,
                   LongTermMemoryPromptService longTermMemoryPromptService,
                   @Qualifier("memoryTools") ToolCallback[] memoryTools){
//        chatMemory = MessageWindowChatMemory.builder()
//                .chatMemoryRepository(new InMemoryChatMemoryRepository())
//                .maxMessages(10)
//                .build();
        this.chatMemory = chatMemory;
        this.queryPreprocessor = queryPreprocessor;
        this.memoryTools = memoryTools;

        String systemPrompt = SYSTEM_PROMPT + "\n\n" + longTermMemoryPromptService.buildPrompt();
        chatClient = ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(this.chatMemory).build(),
                        new MyLoggerAdvisor(),
                        new ReReadingAdvisor()
                )
                .build();
        skillChatClient = ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                // 技能模型可能返回服务器文件路径，此客户端不能使用会记录完整响应的日志 Advisor。
                .defaultAdvisors(new ReReadingAdvisor())
                .build();
        log.info("已使用百炼 DashScope ChatModel 初始化 LoveApp 聊天客户端");
    }

    /**
     * 使用预构建客户端创建最小实例，供不需要 RAG、记忆和工具的隔离调用场景使用。
     *
     * @param chatClient 已配置的 Spring AI 客户端
     */
    LoveApp(ChatClient chatClient) {
        this.chatClient = chatClient;
        this.skillChatClient = chatClient;
        this.chatMemory = null;
        this.queryPreprocessor = null;
        this.memoryTools = new ToolCallback[0];
    }

    /**
     * AI 基础聊天接口，输入用户消息和会话ID，输出AI回复内容
     * @param message
     * @param chatId
     * @return
     */
    public String doChat(String message, String chatId){
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(a -> a.param(CONVERSATION_ID, chatId))
                .toolCallbacks(memoryTools)
                .call()
                .chatResponse();
        assert chatResponse != null;
        String content = chatResponse.getResult().getOutput().getText();
        return content;
    }

    /**
     * AI 基础聊天接口，输入用户消息和会话ID，输出AI回复内容,流式输出版本
     * @param message
     * @param chatId
     * @return
     */
    public Flux<String> doChatWithStream(String message, String chatId){
        return chatClient
                .prompt()
                .user(message)
                .advisors(a -> a.param(CONVERSATION_ID, chatId))
                .toolCallbacks(memoryTools)
                .stream()
                .content();
    }

    /**
     * AI 基础聊天接口，输入用户消息和会话ID，输出AI回复内容
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithImage(String message, String chatId, String attachmentId, String imagePath){
        FileSystemResource imageResource = new FileSystemResource(imagePath);
        MediaType mediaType = MediaTypeFactory.getMediaType(imageResource)
                .orElse(MediaType.IMAGE_PNG);
        ChatResponse chatResponse = chatClient
                .prompt()
                // 记忆中只保存受控附件标识和访问地址，不保存服务器物理文件路径。
                .user(u -> u.text(message)
                        .metadata(Map.of(
                                "attachmentId", attachmentId,
                                "contentUrl", "/chat/attachments/" + attachmentId
                        ))
                        .media(mediaType, imageResource))
                .advisors(a -> a.param(CONVERSATION_ID, chatId))
                .toolCallbacks(memoryTools)
                .call()
                .chatResponse();
        assert chatResponse != null;
        String content = chatResponse.getResult().getOutput().getText();
        return content;
    }

    /**
     * 发送带图片的用户消息并以 Flux 流式返回模型文本。
     *
     * <p>聊天记忆只写入附件标识和受控 URL，物理路径仅用于本次读取图片内容。</p>
     *
     * @param message 用户问题
     * @param chatId 会话标识
     * @param attachmentId 已完成归属校验的附件标识
     * @param imagePath 服务器图片物理路径
     * @return 模型增量文本流
     */
    public Flux<String> doChatWithImageStream(
            String message,
            String chatId,
            String attachmentId,
            String imagePath
    ) {
        FileSystemResource imageResource = new FileSystemResource(imagePath);
        MediaType mediaType = MediaTypeFactory.getMediaType(imageResource)
                .orElse(MediaType.IMAGE_PNG);
        return chatClient
                .prompt()
                // 流式图片消息与同步入口使用相同的持久化元数据结构，页面可据此恢复图片。
                .user(u -> u.text(message)
                        .metadata(Map.of(
                                "attachmentId", attachmentId,
                                "contentUrl", "/chat/attachments/" + attachmentId
                        ))
                        .media(mediaType, imageResource))
                .advisors(a -> a.param(CONVERSATION_ID, chatId))
                .toolCallbacks(memoryTools)
                .stream()
                .content();
    }

    /**
     * 结构化输出转换器
     * 示例：根据演员名字，生成5部电影
     * @param actor
     * @param movies
     */
    public record ActorsFilms(String actor, List<String> movies){}
    /**
     * 演示把模型输出直接映射为 Java record 的结构化输出能力。
     *
     * @param actor 演员名称
     * @param chatId 会话标识
     * @return 演员及模型生成的电影列表
     */
    public ActorsFilms getActorsFilms(String actor, String chatId){
        return chatClient
                .prompt()
                .user(u -> u
                        .text("Generate 5 movies for {actor}. Return actor and movies.")
                        .param("actor", actor))
                .advisors(a -> a.param(CONVERSATION_ID, chatId))
                .toolCallbacks(memoryTools)
                .call()
                .entity(ActorsFilms.class);
    }

    /**
     * 恋爱咨询结构化报告。
     *
     * @param title 报告标题
     * @param suggestions 建议列表
     */
    public record LoveReport(String title, List<String> suggestions){}
    /**
     * AI 聊天接口，在基础聊天的基础上增加了对AI回复内容的分析和总结，输出最终结果(实战结构化输出）
     * @param message
     * @param chatId
     * @return
     */
    public LoveReport doChatWithReport(String message, String chatId){
        LoveReport  loveReport = chatClient
                .prompt()
                .system(SYSTEM_PROMPT + "每次对话后都要生成恋爱结果，标题为{用户名}的恋爱报告，内容为建议列表")
                .user(message)
                .advisors(a -> a.param(CONVERSATION_ID, chatId))
                .toolCallbacks(memoryTools)
                .call()
                .entity(LoveReport.class);
        assert loveReport != null;
        log.info("loveReport: {}", loveReport);
        return loveReport;
    }


    /**
     * 恋爱知识库问答功能
     */

    @Autowired
    @Qualifier("vectorStore")
    private VectorStore vectorStore;

    /**
     * 使用 PgVector 检索增强生成回答，可在检索前执行查询改写。
     *
     * @param query 用户问题
     * @param chatId 会话标识
     * @param withQueryReform 是否先用 QueryPreprocessor 改写检索问题
     * @return 结合知识库上下文生成的回答
     */
    public String doChatWithRag(String query, String chatId,boolean withQueryReform){
        Query rewrittenQuery = withQueryReform ? queryPreprocessor.rewriteQueryTransform(new Query(query)) : new Query(query);
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(rewrittenQuery.text())
                .advisors(a -> a.param(CONVERSATION_ID, chatId))
                .toolCallbacks(memoryTools)
                // 应用 RAG 内存知识库问答
//                .advisors(QuestionAnswerAdvisor.builder(loveAppVectorStore).build())
                // 应用 rag 检索增强服务（基于 PgVector 向量存储）
                .advisors(QuestionAnswerAdvisor.builder(vectorStore).build())
                // 应用自定义的 RAG 检索增强服务，基于 PgVector 向量存储，并根据用户状态过滤知识库内容
//                .advisors(
//                        LoveAppRetrievalAugmentationAdvisorFactory.createLoveAppRagCustomAdvisor(
//                                vectorStore,"已婚")
//                )
                .call()
                .chatResponse();
        assert chatResponse != null;
        return chatResponse.getResult().getOutput().getText();
    }

    // 工具调用功能
    @Resource
    private ToolCallback[] allTools;

    /**
     * 调用 Skills 工具并把模型文本解析为明确的文本、问题或文件结果。
     *
     * <p>技能客户端不自动保存助手回复，调用方必须先把文件结果转换为受控附件再显式保存。</p>
     *
     * @param message 用户任务
     * @param chatId 会话标识
     * @return 尚未写入助手历史的技能结果
     */
    public SkillChatResult callWithSkills(String message, String chatId) {
        // 先读取旧历史、再持久化本轮用户消息。模型异常或图片登记失败时仍保留用户输入，
        // 但助手原始文件路径永远不会被 MessageChatMemoryAdvisor 自动写入数据库。
        List<Message> history = chatMemory.get(chatId);
        chatMemory.add(chatId, new UserMessage(message));
        ChatResponse chatResponse = skillChatClient
                .prompt()
                .messages(history)
                .user(message)
                .toolCallbacks(allTools)
                .toolCallbacks(SkillsTool.builder()
                        .addSkillsDirectory(resolveSkillsDirectory())
                .build())
                .call()
                .chatResponse();
        assert chatResponse != null;
        SkillChatResult result = toSkillChatResult(chatResponse.getResult().getOutput().getText());
        // 日志只记录结果类型，不输出模型原文，避免生成文件的服务器路径进入日志系统。
        log.info("技能模型调用完成，chatId={}，resultType={}", chatId, result.type());
        return result;
    }

    /**
     * 以 Flux 形式暴露技能结果，保持与 Controller 的 SSE 调用协议一致。
     *
     * @param message 用户任务
     * @param chatId 会话标识
     * @return 单个结构化技能结果流
     */
    public Flux<SkillChatResult> streamWithSkills(String message, String chatId) {
        return Flux.defer(() -> Flux.just(callWithSkills(message, chatId)));
    }

    /**
     * 执行 Skills 并保存经过安全处理的助手结果，最终转换为兼容旧接口的字符串。
     *
     * @param message 用户任务
     * @param chatId 会话标识
     * @return 文本、问题或受控附件 URL
     */
    public String doChatWithTools(String message, String chatId){
        SkillChatResult result = callWithSkills(message, chatId);
        saveSkillAssistantResult(chatId, result);
        if (result.type() == SkillChatResultType.FILE) {
            return result.contentUrl() == null ? "文件已生成" : result.contentUrl();
        }
        if (result.type() == SkillChatResultType.QUESTION) {
            return result.question();
        }
        return result.content();
    }

    /**
     * 在技能结果经过业务层安全处理后显式保存助手消息。图片只保存附件标识和受保护访问地址；
     * 其他文件只记录通用完成文案，防止任何服务器本地路径进入可查询的聊天历史。
     *
     * @param chatId 服务端会话标识
     * @param result 已完成安全处理的技能结果
     */
    public void saveSkillAssistantResult(String chatId, SkillChatResult result) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("messageKind", "SKILL_RESULT");
        String content;
        if (result.type() == SkillChatResultType.QUESTION) {
            content = result.question() == null ? "" : result.question();
            metadata.put("question", content);
        } else if (result.type() == SkillChatResultType.FILE) {
            if (result.attachmentId() != null && result.contentUrl() != null) {
                content = result.contentUrl();
                metadata.put("attachmentId", result.attachmentId());
                metadata.put("contentUrl", result.contentUrl());
            } else {
                content = "文件已生成";
            }
        } else {
            content = result.content() == null ? "" : result.content();
        }
        chatMemory.add(chatId, AssistantMessage.builder()
                .content(content)
                .properties(metadata)
                .build());
        log.info("技能助手结果已安全写入聊天历史，chatId={}，resultType={}，hasAttachment={}",
                chatId, result.type(), result.attachmentId() != null);
    }

    // mcp 协议注入
    @Resource
    private ToolCallbackProvider toolCallbackProvider;

    /**
     * 将 MCP Provider 暴露的动态工具与项目记忆工具一起交给模型调用。
     *
     * @param message 用户问题
     * @param chatId 会话标识
     * @return MCP 工具增强后的模型回答
     */
    public String doChatWithMCP(String message, String chatId){
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(a -> a.param(CONVERSATION_ID, chatId))
                .toolCallbacks(memoryTools)
                .toolCallbacks(toolCallbackProvider)
                .call()
                .chatResponse();
        log.info("chatResponse: {}", chatResponse);
        assert chatResponse != null;
        return chatResponse.getResult().getOutput().getText();
    }

    /**
     * 把技能模型约定文本解析为受控业务类型，优先识别显式 FILE/QUESTION 前缀。
     *
     * @param text 模型原始文本
     * @return 结构化技能结果
     */
    private SkillChatResult toSkillChatResult(String text) {
        if (text == null || text.isBlank()) {
            return SkillChatResult.text("");
        }
        if (text.startsWith("FILE:")) {
            return SkillChatResult.file(text.substring("FILE:".length()).trim());
        }
        if (text.startsWith("QUESTION:")) {
            return SkillChatResult.question(text.substring("QUESTION:".length()).trim());
        }
        if (looksLikeFilePath(text)) {
            return SkillChatResult.file(text.trim());
        }
        if (looksLikeQuestion(text)) {
            return SkillChatResult.question(text.trim());
        }
        return SkillChatResult.text(text);
    }

    /**
     * 识别未带 FILE 前缀但形态明确的文件路径。
     *
     * @param text 模型文本
     * @return 文本形似支持的本地文件路径时返回 {@code true}
     */
    private boolean looksLikeFilePath(String text) {
        String trimmed = text.trim();
        return trimmed.endsWith(".pptx") || trimmed.endsWith(".pdf") || trimmed.endsWith(".png")
                || trimmed.startsWith("/") || trimmed.startsWith("tmp/");
    }

    /**
     * 根据问句标识判断技能是否需要用户继续补充信息。
     *
     * @param text 模型文本
     * @return 文本形似问题时返回 {@code true}
     */
    private boolean looksLikeQuestion(String text) {
        return text.contains("请问") || text.endsWith("？") || text.endsWith("?");
    }

    /**
     * 将 classpath 中的 Skills 目录解析为 SkillsTool 要求的文件系统路径。
     *
     * @return Skills 根目录绝对路径
     * @throws UncheckedIOException 资源无法解析为文件时抛出
     */
    private String resolveSkillsDirectory() {
        try {
            return new ClassPathResource(".claude/skills").getFile().getAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to resolve skills directory from classpath", e);
        }
    }

}
