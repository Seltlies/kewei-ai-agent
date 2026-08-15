package com.kiwi.keweiaiagent.rag;


import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 恋爱大师文档加载器
 */
@Component
class LoveAppDocumentLoader {

    private final Resource[] resources;

    /**
     * @param resources classpath 中匹配 documents/*.md 的知识库资源
     */
    LoveAppDocumentLoader(@Value("classpath:documents/*.md") Resource[] resources) {
        this.resources = resources;
    }

    /**
     * 按 Markdown 分隔规则读取全部知识库文件，并补充文件名、用户状态和来源元数据。
     *
     * @return 可交给关键词增强和向量存储的文档片段
     */
    List<Document> loadMarkdown() {
        List<Document> allDocs = new ArrayList<>();

        for (Resource resource : resources) {
            String filename = resource.getFilename();
            assert filename != null;
            String status = filename.substring(filename.length() - 6, filename.length() - 4);
            MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                    .withHorizontalRuleCreateDocument(true)
                    .withIncludeCodeBlock(false)
                    .withIncludeBlockquote(false)
                    .withAdditionalMetadata("filename", filename)
                    .withAdditionalMetadata("status", status)
                    .build();

            MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
            List<Document> docs = reader.get();

            for (Document doc : docs) {
                doc.getMetadata().put("source", resource.getDescription());
            }

            allDocs.addAll(docs);
        }

        return allDocs;
    }
}
