package com.kiwi.keweiaiagent.rag;


import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于关键词的文档增强器，使用百炼 ChatModel 提取关键词并写入文档元数据。
 */
@Component
@Slf4j
public class MyKeywordEnricher {

    @Resource
    private ChatModel chatModel;

    /**
     * 调用 Spring AI 的 {@link KeywordMetadataEnricher}，通过百炼聊天模型为每份文档提取
     * 两个关键词。增强结果沿用原文档集合，供后续 PgVector 入库流程使用。
     *
     * @param documents 等待增强的文档集合
     * @return 已写入关键词元数据的文档集合
     */
    public List<Document> enrichDocument(List<Document> documents) {
        log.info("开始使用百炼 ChatModel 生成文档关键词，文档数量={}", documents.size());
        KeywordMetadataEnricher keywordMetadataEnricher = new KeywordMetadataEnricher(chatModel, 2);
        List<Document> enrichedDocuments = keywordMetadataEnricher.apply(documents);
        log.info("百炼 ChatModel 文档关键词生成完成，文档数量={}", enrichedDocuments.size());
        return enrichedDocuments;
    }
}
