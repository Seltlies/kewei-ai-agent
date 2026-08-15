package com.kiwi.keweiaiagent.tools;

import cn.hutool.core.util.StrUtil;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 网页正文抓取工具，使用 Jsoup 提取标题、描述、正文以及可选的前十条链接。
 */
@Component
public class WebScrapingTool {

    private static final int DEFAULT_TIMEOUT_MS = 20_000;
    private static final int DEFAULT_MAX_TEXT_LENGTH = 4000;

    /**
     * 请求 HTTP/HTTPS 页面并整理为适合模型消费的定长纯文本。
     *
     * @param url 网页地址
     * @param maxTextLength 正文最大字符数
     * @param includeLinks 是否附带前十条链接
     * @return 网页摘要或抓取失败文本
     */
    @Tool(description = "Scrape a webpage and return title, summary text and optional links",returnDirect = false)
    public String scrapeWebsite(
            @ToolParam(description = "The webpage URL to scrape. Must start with http:// or https://") String url,
            @ToolParam(description = "Max plain-text length for main content, default 4000") Integer maxTextLength,
            @ToolParam(description = "Whether to include top links from the page, default false") Boolean includeLinks
    ) {
        if (StrUtil.isBlank(url)) {
            return "Error: url is required.";
        }
        if (!isSupportedUrl(url)) {
            return "Error: only http/https URLs are supported.";
        }

        int finalMaxTextLength = (maxTextLength == null || maxTextLength <= 0) ? DEFAULT_MAX_TEXT_LENGTH : maxTextLength;
        boolean finalIncludeLinks = Boolean.TRUE.equals(includeLinks);

        try {
            Connection connection = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; KeweiAiAgent/1.0)")
                    .timeout(DEFAULT_TIMEOUT_MS)
                    .followRedirects(true);

            Document doc = connection.get();
            return buildScrapeResult(url, doc, finalMaxTextLength, finalIncludeLinks);
        } catch (Exception e) {
            return "Web scraping failed: " + e.getMessage();
        }
    }

    /**
     * 解析 URL 并限定协议为 HTTP 或 HTTPS。
     *
     * @param url 待校验地址
     * @return 协议受支持且语法有效时返回 {@code true}
     */
    private boolean isSupportedUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 将 Jsoup 文档压缩为有固定字段顺序的模型上下文文本。
     *
     * @param url 最终展示的来源地址
     * @param doc 已解析文档
     * @param maxTextLength 正文最大长度
     * @param includeLinks 是否输出链接
     * @return 多行抓取结果
     */
    String buildScrapeResult(String url, Document doc, int maxTextLength, boolean includeLinks) {
        List<String> lines = new ArrayList<>();
        lines.add("url: " + url);

        String title = doc.title();
        if (StrUtil.isNotBlank(title)) {
            lines.add("title: " + title);
        }

        String description = "";
        Element metaDescription = doc.selectFirst("meta[name=description]");
        if (metaDescription != null) {
            description = metaDescription.attr("content");
        }
        if (StrUtil.isNotBlank(description)) {
            lines.add("description: " + description);
        }

        String text = doc.body() == null ? "" : doc.body().text();
        text = StrUtil.trim(text);
        if (StrUtil.isBlank(text)) {
            lines.add("content: (empty)");
        } else {
            String trimmed = text.length() > maxTextLength ? text.substring(0, maxTextLength) + "..." : text;
            lines.add("content:");
            lines.add(trimmed);
        }

        if (includeLinks) {
            Elements anchors = doc.select("a[href]");
            int maxLinks = Math.min(10, anchors.size());
            lines.add("links:");
            for (int i = 0; i < maxLinks; i++) {
                Element a = anchors.get(i);
                String href = a.absUrl("href");
                if (StrUtil.isBlank(href)) {
                    href = a.attr("href");
                }
                String textLabel = StrUtil.blankToDefault(StrUtil.trim(a.text()), "(no text)");
                lines.add((i + 1) + ". " + textLabel + " -> " + href);
            }
            if (maxLinks == 0) {
                lines.add("(none)");
            }
        }

        return String.join("\n", lines);
    }
}
