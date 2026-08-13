package com.kiwi.keweiaiagent.rag;


import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;


/**
 * 向量数据库配置类
 */
@Configuration
@Slf4j
public class LoveAppVectorStoreConfig {

    /**
     * 百炼 text-embedding-v4 同步接口允许的单次最大文本数量。
     */
    private static final int BAILIAN_EMBEDDING_MAX_BATCH_SIZE = 10;

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;

    /**
     * 创建百炼向量请求分批策略。先调用 Spring AI 的 {@link TokenCountBatchingStrategy}
     * 按 Token 上限拆分文档，再把每个结果批次切分为最多 10 条，确保 PgVector 调用
     * {@link EmbeddingModel#embed(List, org.springframework.ai.embedding.EmbeddingOptions, BatchingStrategy)}
     * 时同时满足模型 Token 上限和百炼同步接口的条数上限。
     *
     * @return 同时限制 Token 数和文档条数的向量请求分批策略
     */
    @Bean
    BatchingStrategy bailianEmbeddingBatchingStrategy() {
        TokenCountBatchingStrategy tokenCountBatchingStrategy = new TokenCountBatchingStrategy();
        return documents -> {
            List<List<Document>> batches = new ArrayList<>();
            for (List<Document> tokenBatch : tokenCountBatchingStrategy.batch(documents)) {
                for (int start = 0; start < tokenBatch.size(); start += BAILIAN_EMBEDDING_MAX_BATCH_SIZE) {
                    int end = Math.min(start + BAILIAN_EMBEDDING_MAX_BATCH_SIZE, tokenBatch.size());
                    batches.add(List.copyOf(tokenBatch.subList(start, end)));
                }
            }
            log.debug("百炼向量请求分批完成，文档数量={}，请求批次数={}", documents.size(), batches.size());
            return batches;
        };
    }

    /**
     * 使用百炼文本向量模型创建内存向量库，并通过 {@link LoveAppDocumentLoader} 加载
     * 恋爱知识库 Markdown 文档后调用 {@link SimpleVectorStore#add(List)} 完成向量化。
     *
     * @param embeddingModel 百炼 DashScope 文本向量模型
     * @return 已加载知识库文档的内存向量库
     */
    @Bean
    VectorStore loveAppVectorStore(EmbeddingModel embeddingModel) {
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(embeddingModel).build();
        List<Document> documents = loveAppDocumentLoader.loadMarkdown();
        log.info("开始使用百炼 EmbeddingModel 构建内存向量库，文档数量={}", documents.size());
        simpleVectorStore.add(documents);
        log.info("百炼 EmbeddingModel 内存向量库构建完成，文档数量={}", documents.size());
        return simpleVectorStore;
    }

}
