package com.kiwi.keweiaiagent.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Set;

/**
 * SearchAPI Google 搜索工具，负责参数规范化、凭据读取和搜索结果压缩。
 */
@Component
@Slf4j
public class WebSearchTool {

    private static final String SEARCH_API_URL = "https://www.searchapi.io/api/v1/search";
    private static final Set<String> SUPPORTED_TIME_PERIODS = Set.of(
            "last_hour", "last_day", "last_week", "last_month", "last_year"
    );
    @Value("${search-api.api-key:${searchapi.key:}}")
    private String configuredApiKey;
    @Autowired(required = false)
    private Environment environment;

    /**
     * 启动时只记录 API Key 是否存在及长度，不输出密钥正文。
     */
    @PostConstruct
    public void logKeyStatusAtStartup() {
        String key = resolveApiKey();
        String activeProfiles = environment == null ? "" : String.join(",", environment.getActiveProfiles());
        if (StrUtil.isBlank(key)) {
            log.warn("WebSearchTool init: SearchAPI key is missing. activeProfiles={}", activeProfiles);
            return;
        }
        log.info("WebSearchTool init: SearchAPI key loaded. length={}, activeProfiles={}", key.length(), activeProfiles);
    }

    /**
     * 调用 SearchAPI Google 引擎并返回最多五条精简自然搜索结果。
     *
     * @param q 搜索关键词
     * @param location 标准位置名称
     * @param gl 国家代码
     * @param hl 语言代码
     * @param page 页码
     * @param timePeriod 时间范围过滤器
     * @return 精简结果或可供 Agent 处理的错误文本
     */
    @Tool(description = "Search websites by Google engine and return concise top web results",returnDirect = false)
    public String searchWebsite(
            @ToolParam(description = "Search query keywords") String q,
            @ToolParam(description = "Optional canonical location. Example: New York,United States") String location,
            @ToolParam(description = "Optional country code, default us") String gl,
            @ToolParam(description = "Optional language code, default en") String hl,
            @ToolParam(description = "Optional page number, default 1") Integer page,
            @ToolParam(description = "Optional time period filter: last_hour, last_day, last_week, last_month, last_year") String timePeriod
    ) {
        if (StrUtil.isBlank(q)) {
            return "Error: query 'q' is required.";
        }

        String apiKey = resolveApiKey();
        if (StrUtil.isBlank(apiKey)) {
            return "Error: missing SearchAPI key. Set config key searchapi.key (or search-api.api-key), env SEARCHAPI_API_KEY, or -Dsearchapi.api-key.";
        }

        String normalizedGl = normalizeGl(gl);
        String normalizedHl = normalizeHl(hl);
        String normalizedLocation = normalizeLocation(location);
        int normalizedPage = normalizePage(page);
        String normalizedTimePeriod = normalizeTimePeriod(timePeriod);

        HttpRequest request = HttpRequest.get(SEARCH_API_URL)
                .form("engine", "google")
                .form("q", q)
                .form("api_key", apiKey)
                .form("gl", normalizedGl)
                .form("hl", normalizedHl)
                .form("page", normalizedPage)
                .timeout(20_000);

        if (StrUtil.isNotBlank(normalizedLocation)) {
            request.form("location", normalizedLocation);
        }
        if (StrUtil.isNotBlank(normalizedTimePeriod)) {
            request.form("time_period", normalizedTimePeriod);
        }
        log.info("searchWebsite normalized args: gl={}, hl={}, location={}, page={}, timePeriod={}",
                normalizedGl, normalizedHl, normalizedLocation, normalizedPage, normalizedTimePeriod);

        try (HttpResponse response = request.execute()) {
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                return "Search API request failed, status=" + response.getStatus() + ", body=" + response.body();
            }

            JSONObject root = JSONUtil.parseObj(response.body());
            return toConciseResult(root, q);
        } catch (Exception e) {
            return "Search failed: " + e.getMessage();
        }
    }

    /**
     * 从 Spring 配置、系统属性和环境变量中读取 SearchAPI Key。
     *
     * @return API Key；未配置时为空
     */
    String resolveApiKey() {
        String apiKey = configuredApiKey;
        if (StrUtil.isBlank(apiKey) && environment != null) {
            apiKey = environment.getProperty("searchapi.key");
        }
        if (StrUtil.isBlank(apiKey) && environment != null) {
            apiKey = environment.getProperty("search-api.api-key");
        }
        if (StrUtil.isBlank(apiKey)) {
            apiKey = System.getProperty("searchapi.api-key");
        }
        if (StrUtil.isBlank(apiKey)) {
            apiKey = System.getenv("SEARCHAPI_API_KEY");
        }
        return apiKey;
    }

    /** 将支持的中文/英文写法规范为 SearchAPI 语言代码。 */
    String normalizeHl(String hl) {
        if (StrUtil.isBlank(hl)) {
            return "en";
        }
        String lower = hl.trim().toLowerCase(Locale.ROOT);
        if ("zh".equals(lower) || "zh-cn".equals(lower) || "zh_cn".equals(lower)) {
            return "zh-CN";
        }
        if ("en".equals(lower) || "en-us".equals(lower) || "en_us".equals(lower)) {
            return "en";
        }
        return "en";
    }

    /** 将国家代码限制为当前支持的 cn 或 us。 */
    String normalizeGl(String gl) {
        if (StrUtil.isBlank(gl)) {
            return "us";
        }
        String lower = gl.trim().toLowerCase(Locale.ROOT);
        if ("cn".equals(lower) || "us".equals(lower)) {
            return lower;
        }
        return "us";
    }

    /**
     * 清理位置分隔符并把常见中文地名转换为 SearchAPI 标准名称。
     */
    String normalizeLocation(String location) {
        if (StrUtil.isBlank(location)) {
            return "";
        }
        String normalized = location.trim()
                .replace("，", ",")
                .replaceAll("\\s*,\\s*", ", ");
        if (normalized.contains("上海")) {
            return "Shanghai, China";
        }
        if ("中国".equals(normalized) || "中华人民共和国".equals(normalized)) {
            return "China";
        }
        return normalized;
    }

    /** 将空值和非正页码规范为第一页。 */
    int normalizePage(Integer page) {
        if (page == null || page < 1) {
            return 1;
        }
        return page;
    }

    /** 仅保留 SearchAPI 支持的时间范围枚举。 */
    String normalizeTimePeriod(String timePeriod) {
        if (StrUtil.isBlank(timePeriod)) {
            return "";
        }
        String normalized = timePeriod.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_TIME_PERIODS.contains(normalized) ? normalized : "";
    }

    /**
     * 从 SearchAPI JSON 中提取总数和前五条标题、链接、摘要。
     *
     * @param root API 响应 JSON
     * @param query 原搜索词
     * @return 适合模型继续处理的多行文本
     */
    String toConciseResult(JSONObject root, String query) {
        List<String> lines = new ArrayList<>();
        lines.add("query: " + query);

        JSONObject info = root.getJSONObject("search_information");
        if (info != null) {
            Object total = info.get("total_results");
            if (total != null) {
                lines.add("total_results: " + total);
            }
        }

        JSONArray organic = root.getJSONArray("organic_results");
        if (organic == null || organic.isEmpty()) {
            lines.add("No organic results.");
            return String.join("\n", lines);
        }

        int max = Math.min(5, organic.size());
        lines.add("top_results:");
        for (int i = 0; i < max; i++) {
            JSONObject item = organic.getJSONObject(i);
            String title = item.getStr("title", "");
            String link = item.getStr("link", "");
            String snippet = item.getStr("snippet", "");
            lines.add((i + 1) + ". " + title);
            lines.add("   link: " + link);
            if (StrUtil.isNotBlank(snippet)) {
                lines.add("   snippet: " + snippet);
            }
        }
        return String.join("\n", lines);
    }
}
