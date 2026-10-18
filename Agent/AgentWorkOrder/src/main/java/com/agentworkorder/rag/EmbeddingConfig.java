package com.agentworkorder.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 手工装配 RAG 使用的 {@link EmbeddingModel}（M3）。
 *
 * <p><b>为什么不用 Spring AI 的 embedding 自动配置</b>：
 * <ol>
 *   <li>Chat 走 DeepSeek 的 OpenAI 兼容端点，但 DeepSeek 只提供 Chat Completions，
 *       <b>不提供 /embeddings</b>。若让 OpenAI starter 用同一个 base-url 自动装配 EmbeddingModel，
 *       启动期或检索期必然 404；</li>
 *   <li>因此 {@code spring.ai.model.embedding: none} 保持不变，本类按
 *       {@code agent.rag.embedding.*} 单独构造一个独立 key/base-url 的模型；</li>
 *   <li>换 embedding 供应商时只需改配置，不动业务代码。</li>
 * </ol>
 *
 * <p>两个分支互斥：
 * <ul>
 *   <li>{@code provider=mock} —— 无 key 也能跑通全链路（开发/冒烟）；</li>
 *   <li>{@code provider=openai} —— OpenAI 兼容协议，默认指向硅基流动的 {@code BAAI/bge-m3}。</li>
 * </ul>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true", matchIfMissing = true)
public class EmbeddingConfig {

    /**
     * Mock 分支：确定性的字符哈希向量器。
     */
    @Bean(name = "ragEmbeddingModel")
    @ConditionalOnProperty(name = "agent.rag.embedding.provider", havingValue = "mock")
    public EmbeddingModel mockRagEmbeddingModel(RagProperties props) {
        int dimensions = props.getEmbedding().getDimensions();
        log.info("RAG Embedding 使用 mock：dimensions={}（仅用于链路验证，无真实语义能力）", dimensions);
        return new MockEmbeddingModel(dimensions);
    }

    /**
     * OpenAI 兼容分支：默认硅基流动 {@code BAAI/bge-m3}（1024 维）。
     *
     * <p>构造步骤：
     * <ol>
     *   <li>用独立的 base-url + api-key 创建 {@link OpenAiApi}（与 Chat 的 OpenAiApi 完全隔离）；</li>
     *   <li>设置模型名；只有 {@code request-dimensions=true} 时才携带 dimensions 参数
     *       （bge-m3 维度固定，部分兼容服务不接受该参数）；</li>
     *   <li>用 {@link MetadataMode#EMBED} 构造 {@link OpenAiEmbeddingModel}。</li>
     * </ol>
     *
     * <p>API key 缺失时<b>不在启动期抛异常</b>：先生成一个必然失败的模型，
     * 让检索在运行期被 {@code KnowledgeRetriever} 捕获并降级，保证对话主流程不受影响。
     */
    @Bean(name = "ragEmbeddingModel")
    @ConditionalOnProperty(name = "agent.rag.embedding.provider", havingValue = "openai")
    public EmbeddingModel openAiRagEmbeddingModel(RagProperties props) {
        RagProperties.Embedding embedding = props.getEmbedding();

        // 步骤 1：读取并校验连接参数；key 缺失只告警、不阻断启动
        String apiKey = embedding.getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            log.warn("agent.rag.embedding.api-key 未配置：向量化调用将失败，检索会降级为「知识库暂不可用」");
            apiKey = "missing-embedding-api-key";
        }
        String baseUrl = embedding.getBaseUrl();
        String model = embedding.getModel();
        log.info("RAG Embedding 使用 OpenAI 兼容服务：baseUrl={} model={} dimensions={}",
                baseUrl, model, embedding.getDimensions());

        // 步骤 2：构造独立的 OpenAI 客户端（不复用 Chat 的配置）
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        // 步骤 3：构造 embedding 参数；bge-m3 不需要传 dimensions
        OpenAiEmbeddingOptions.Builder options = OpenAiEmbeddingOptions.builder().model(model);
        if (embedding.isRequestDimensions()) {
            options.dimensions(embedding.getDimensions());
        }

        // 步骤 4：返回模型 Bean（MetadataMode.EMBED 表示只取 embedding，不做生成）
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options.build());
    }
}
