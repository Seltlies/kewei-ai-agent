package com.kiwi.keweiaiagent.rag;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 恋爱知识库 Markdown 到 PgVector 的启动期增量加载配置。
 *
 * <p>正文 SHA-256 写入 metadata 作为幂等键，避免应用每次启动重复向量化相同内容。</p>
 */
@Configuration
@Slf4j
public class PgVectorVectorLoadMarkdownConfig {

    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("^[a-zA-Z0-9_]+$");

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    @Qualifier("vectorStore")
    private PgVectorStore pgVectorStore;

    @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}")
    private String tableName;

    @Resource
    private MyKeywordEnricher myKeywordEnricher;

    /**
     * 创建应用启动后的 PgVector 增量加载任务。任务通过 {@link LoveAppDocumentLoader}
     * 读取知识库文档，为正文计算 SHA-256，并调用 {@code existsByContentHash} 执行
     * 参数化 SELECT 去重；仅对新增文档调用 {@link MyKeywordEnricher#enrichDocument(List)}
     * 和 {@link PgVectorStore#add(List)}，完成百炼关键词增强、向量化及 PgVector 写入。
     *
     * @return 应用启动后执行一次的 PgVector 增量加载器
     */
    @Bean
    public ApplicationRunner pgVectorVectorStoreConfig() {
        return new ApplicationRunner() {
            /**
             * 加载知识库、按内容摘要筛选新增文档，再执行关键词增强和向量写入。
             *
             * @param args Spring Boot 启动参数
             */
            @Override
            public void run(ApplicationArguments args) {
                List<Document> loadedDocuments = loveAppDocumentLoader.loadMarkdown();
                List<Document> newDocuments = new ArrayList<>();
                log.info("开始检查 PgVector 知识库增量文档，数据表={}，加载文档数量={}", tableName, loadedDocuments.size());

                for (Document document : loadedDocuments) {
                    String contentHash = sha256Hex(extractDocumentText(document));
                    Map<String, Object> metadata = document.getMetadata();
                    metadata.put("content_hash", contentHash);

                    if (!existsByContentHash(contentHash)) {
                        newDocuments.add(document);
                    }
                }

                if (!newDocuments.isEmpty()) {
                    log.info("开始使用百炼模型增强并写入 PgVector，新增文档数量={}", newDocuments.size());
                    List<Document> enrichedDocument = myKeywordEnricher.enrichDocument(newDocuments);
                    pgVectorStore.add(enrichedDocument);
                    log.info("百炼模型向量化及 PgVector 写入完成，新增文档数量={}", enrichedDocument.size());
                } else {
                    log.info("PgVector 知识库没有新增文档，无需调用百炼模型写入向量");
                }
            }
        };
    }

    /**
     * 使用参数化查询判断内容摘要是否已存在；表名先通过白名单校验后再拼接。
     *
     * @param contentHash 文档正文 SHA-256
     * @return 已存在相同内容时返回 {@code true}
     */
    private boolean existsByContentHash(String contentHash) {
        String safeTableName = sanitizeTableName(tableName);
        String sql = "SELECT EXISTS (SELECT 1 FROM " + safeTableName + " WHERE metadata::jsonb ->> 'content_hash' = ?)";
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, contentHash);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 将配置表名限制为字母、数字和下划线，防止动态标识符形成 SQL 注入。
     *
     * @param configuredTableName 外部配置表名
     * @return 校验通过的原表名
     */
    private String sanitizeTableName(String configuredTableName) {
        if (!SAFE_TABLE_NAME.matcher(configuredTableName).matches()) {
            throw new IllegalArgumentException("Invalid pgvector table name: " + configuredTableName);
        }
        return configuredTableName;
    }

    /**
     * 兼容不同 Spring AI 版本的正文访问器，从 Document 中读取参与去重的文本。
     *
     * @param document Spring AI 文档
     * @return 文档正文；无可用正文访问器时返回元数据文本
     */
    private String extractDocumentText(Document document) {
        for (String methodName : List.of("getText", "getContent")) {
            try {
                Method method = document.getClass().getMethod(methodName);
                Object value = method.invoke(document);
                if (value != null) {
                    return String.valueOf(value);
                }
            } catch (Exception ignored) {
                // 当前 Spring AI 版本没有该访问器时继续尝试另一个公开方法。
            }
        }

        return String.valueOf(document.getMetadata());
    }

    /**
     * 计算 UTF-8 文本的 SHA-256 十六进制摘要。
     *
     * @param value 文档正文
     * @return 64 位小写十六进制摘要
     */
    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
