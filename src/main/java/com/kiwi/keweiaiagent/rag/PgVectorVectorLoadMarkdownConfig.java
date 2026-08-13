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

    private boolean existsByContentHash(String contentHash) {
        String safeTableName = sanitizeTableName(tableName);
        String sql = "SELECT EXISTS (SELECT 1 FROM " + safeTableName + " WHERE metadata::jsonb ->> 'content_hash' = ?)";
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, contentHash);
        return Boolean.TRUE.equals(exists);
    }

    private String sanitizeTableName(String configuredTableName) {
        if (!SAFE_TABLE_NAME.matcher(configuredTableName).matches()) {
            throw new IllegalArgumentException("Invalid pgvector table name: " + configuredTableName);
        }
        return configuredTableName;
    }

    private String extractDocumentText(Document document) {
        for (String methodName : List.of("getText", "getContent")) {
            try {
                Method method = document.getClass().getMethod(methodName);
                Object value = method.invoke(document);
                if (value != null) {
                    return String.valueOf(value);
                }
            } catch (Exception ignored) {
                // Try next accessor for compatibility with different Spring AI versions.
            }
        }

        return String.valueOf(document.getMetadata());
    }

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
