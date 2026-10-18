package com.agentworkorder.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在线检索封装（M3 §六）：活跃版本过滤 → 相似度阈值 → Top-K → 截断 → 降级。
 *
 * <p>对上层（{@link com.agentworkorder.knowledge.KnowledgeTools}）只暴露一个方法：
 * 输入检索问题，输出<b>裸 JSON 字符串</b>。这样：
 * <ul>
 *   <li>成功体是 {@code {results:[...], count:N}}，模型可直接阅读；</li>
 *   <li>空召回是 {@code {results:[], note:"..."}}，明确告诉模型“别编造”；</li>
 *   <li>失败体是 {@code {code:500, message:"..."}}，与项目一错误体同形状，
 *       M2 的 {@code ToolResult.inspectCode} 会自动把审计标为 ERROR。</li>
 * </ul>
 *
 * <p>本类统一 catch 所有检索异常：RAG 是增强能力，向量库/embedding 故障绝不能穿透到 SSE 主流程。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeRetriever {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RagProperties properties;
    private final RagManifestStore manifestStore;
    private final ObjectProvider<VectorStore> vectorStoreProvider; // ObjectProvider延迟查找

    public KnowledgeRetriever(RagProperties properties,
                              RagManifestStore manifestStore,
                              ObjectProvider<VectorStore> vectorStoreProvider) {
        this.properties = properties;
        this.manifestStore = manifestStore;
        this.vectorStoreProvider = vectorStoreProvider;
    }

    /**
     * 检索知识库并返回可直接交给模型的 JSON。
     *
     * @param query       检索问题（业务术语概括，避免代词）
     * @param requestedK  模型请求的片段数，可空；会被 clamp 到 [1, maxTopK]
     * @return 成功体 / 空召回体 / 错误体
     */
    public String searchJson(String query, Integer requestedK) {
        // 步骤 1：空问题直接返回空结果，不打扰向量库
        if (!StringUtils.hasText(query)) {
            return emptyJson("检索问题为空");
        }

        // 步骤 2：没有活跃版本说明尚未入库（或 manifest 丢失），明确告知模型
        RagManifest manifest = manifestStore.get();
        if (manifest == null || !StringUtils.hasText(manifest.activeVersion())) {
            return emptyJson("知识库尚未入库，请先执行 /api/agent/knowledge/reindex");
        }

        try {
            // 步骤 3：拿到向量库（Milvus Bean 是 @Lazy，Milvus 未启动时这里才抛异常）
            VectorStore vectorStore = vectorStoreProvider.getObject();

            // 步骤 4：Top-K 收敛——模型传 100 也只取 maxTopK
            int topK = clampTopK(requestedK);

            // 步骤 5：只检索活跃版本，避免重建期间新旧规则混用
            Filter.Expression activeVersionOnly = new FilterExpressionBuilder()
                    .eq(KnowledgeMetadata.INDEX_VERSION, manifest.activeVersion())
                    .build();

            // 步骤 6：组装检索请求（相似度阈值过滤低相关片段）
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(properties.getSimilarityThreshold())
                    .filterExpression(activeVersionOnly)
                    .build();

            List<Document> documents = vectorStore.similaritySearch(request);
            return successJson(documents);
        } catch (Exception e) {
            // 步骤 7：统一降级——检索失败只影响这一个工具结果，对话与工单查询继续可用
            log.warn("知识检索失败：query={} err={}", query, rootMessage(e));
            return errorJson("知识库暂不可用：" + rootMessage(e));
        }
    }

    /** 成功体：逐个片段给出 source/title/section/score/content，并按 maxContentChars 截断。 */
    private String successJson(List<Document> documents) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (documents != null) {
            for (Document document : documents) {
                Map<String, Object> metadata = document.getMetadata();
                Map<String, Object> item = new LinkedHashMap<>();
                // 来源信息用于模型在回答里注明「依据某文档某章节」
                item.put("source", metadata.get(KnowledgeMetadata.SOURCE));
                item.put("title", metadata.get(KnowledgeMetadata.TITLE));
                item.put("section", metadata.get(KnowledgeMetadata.SECTION));
                item.put("score", roundScore(document.getScore()));
                // 单片段截断，防止一条召回撑爆 Chat 上下文
                item.put("content", truncate(document.getText(), properties.getMaxContentChars()));
                results.add(item);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("results", results);
        body.put("count", results.size());
        if (results.isEmpty()) {
            // 空召回时给模型一个明确信号：知识库没有覆盖，不许编造
            body.put("note", "知识库未检索到相关内容");
        }
        return toJson(body);
    }

    /** 空召回体。 */
    private String emptyJson(String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("results", List.of());
        body.put("note", note);
        return toJson(body);
    }

    /** 错误体：与项目一 ToolErrorVO 同形状，M2 审计会自动判定为 ERROR。 */
    private String errorJson(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 500);
        body.put("message", message);
        return toJson(body);
    }

    /** 默认取 agent.rag.top-k；非正数按默认值处理；上限为 max-top-k。 */
    private int clampTopK(Integer requestedK) {
        int defaultK = Math.max(1, properties.getTopK());
        int maxK = Math.max(1, properties.getMaxTopK());
        int topK = (requestedK == null || requestedK <= 0) ? defaultK : requestedK;
        return Math.min(topK, maxK);
    }

    /** 分数保留 4 位小数，输出更稳定、便于用户核对。 */
    private static double roundScore(Double score) {
        if (score == null) {
            return 0.0;
        }
        return Math.round(score * 10_000.0) / 10_000.0;
    }

    /** 截断到指定字符数；省略号占用配额，保证总长不超过上限。 */
    private static String truncate(String value, int max) {
        if (value == null || max <= 0 || value.length() <= max) {
            return value;
        }
        if (max <= 3) {
            return value.substring(0, max);
        }
        return value.substring(0, max - 3) + "...";
    }

    private static String toJson(Map<String, Object> body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            // 理论上不会发生（只含字符串/数字/列表）
            return "{\"code\":500,\"message\":\"知识库结果序列化失败\"}";
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.toString();
    }
}
