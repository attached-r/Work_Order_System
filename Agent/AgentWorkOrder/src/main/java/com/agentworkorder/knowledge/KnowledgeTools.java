package com.agentworkorder.knowledge;

import com.agentworkorder.audit.AuditedToolInvoker;
import com.agentworkorder.rag.KnowledgeRetriever;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 知识检索工具（M3）：把 RAG 封装成与 {@code WorkOrderTools} 并列的 {@code @Tool}。
 *
 * <p>为什么用「工具」而不是每轮自动检索：
 * <ul>
 *   <li>只有规则类问题才付出检索与上下文 token 成本；</li>
 *   <li>天然复用 M2 的权限可见性与审计闸门（{@code searchKnowledge → workorder:query}）；</li>
 *   <li>工具描述明确区分「规则」与「实时数据」，减少与工单查询的混淆。</li>
 * </ul>
 *
 * <p>工具描述写得具体是有意为之：模型是否在正确时机调用、是否用业务术语而不是代词，
 * 主要取决于这段描述的质量。
 */
@Component
@ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeTools {

    private final KnowledgeRetriever retriever;
    private final AuditedToolInvoker auditedToolInvoker;

    public KnowledgeTools(KnowledgeRetriever retriever, AuditedToolInvoker auditedToolInvoker) {
        this.retriever = retriever;
        this.auditedToolInvoker = auditedToolInvoker;
    }

    /**
     * 检索内部业务知识库。
     *
     * <p>执行链路：审计模板 → {@link KnowledgeRetriever} → 向量库（活跃版本 + 阈值过滤）
     * → 裸 JSON（results/count 或错误体）。
     *
     * @param query       检索问题，要求用业务术语概括
     * @param topK        期望返回的片段数，可空；服务端会 clamp 到 [1, maxTopK]
     * @param toolContext 本轮工具上下文（审计用）
     */
    @Tool(name = "searchKnowledge",
            description = "检索内部业务知识库，适用于工单流程、状态含义、角色权限、操作规范、"
                    + "被驳回/超时等常见问题。只查规则，不查具体工单数据；"
                    + "查具体工单请用 listWorkOrders / queryWorkOrder。")
    public String searchKnowledge(
            @ToolParam(description = "检索问题，用业务术语概括，例如「审核驳回后如何重新提交」；避免使用「它」「这个」等代词")
            String query,
            @ToolParam(required = false, description = "返回片段数，默认 4，最大 8")
            Integer topK,
            ToolContext toolContext) {

        // 步骤 1：整理审计入参（Map.of 不接受 null，故用 HashMap 并显式归一）
        Map<String, Object> args = new HashMap<>();
        args.put("query", query == null ? "" : query);
        args.put("topK", topK == null ? "default" : topK);

        // 步骤 2：经共享审计模板调用检索；成功/业务失败/异常都会落一条 agent_tool_call_log
        return auditedToolInvoker.invoke("searchKnowledge", args, toolContext,
                () -> retriever.searchJson(query, topK));
    }
}
