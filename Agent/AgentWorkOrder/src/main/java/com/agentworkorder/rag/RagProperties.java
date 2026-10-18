package com.agentworkorder.rag;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 相关配置，绑定 {@code agent.rag.*}（M3）。
 *
 * <p>设计纪律：
 * <ol>
 *   <li>Embedding 与 Chat 的配置<b>完全分开</b>——Chat 走 DeepSeek，Embedding 走硅基流动的
 *       {@code BAAI/bge-m3}；两把 key、两个 base-url，互不复用；</li>
 *   <li>所有“可调参数”都集中在这里，业务代码不写魔法数字；</li>
 *   <li>默认值即“能跑通”的值：{@code store=milvus}、{@code model=BAAI/bge-m3}、{@code dimensions=1024}。</li>
 * </ol>
 *
 * <p>注意：三大维度必须一致，否则 Milvus 入库/检索会报维度错误——
 * 本类 {@link Embedding#getDimensions()}、Milvus collection 的 embeddingDimension、
 * manifest 中记录的 dimensions。换模型/改维度后必须重新 reindex。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "agent.rag")
public class RagProperties {

    /** RAG 总开关。关闭后：不注册 searchKnowledge 工具、不创建向量库/入库/检索 Bean，对话只走 M1/M2。 */
    private boolean enabled = true;

    /** 向量库实现：{@code simple}（单文件、零中间件）或 {@code milvus}（本地 Docker 部署）。 */
    private String store = "milvus";

    /** 知识语料位置（Spring Resource 通配符）。默认读取 classpath 下 knowledge 目录的 md 文件。 */
    private String knowledgeLocations = "classpath*:knowledge/*.md";

    /** 每次检索默认返回的片段数。 */
    private int topK = 4;

    /** 检索片段数上限：模型即使传 100，也会被 clamp 到这里。 */
    private int maxTopK = 8;

    /** 相似度阈值：低于该分数的片段直接丢弃，避免把无关内容喂给模型。 */
    private double similarityThreshold = 0.35;

    /** 单个片段的正文截断长度（字符），防止一条结果撑爆 Chat 上下文。 */
    private int maxContentChars = 1200;

    /** 二次切片时单块的最大 token 数（TokenTextSplitter 参数）。 */
    private int chunkSize = 400;

    /** 过短的节不单独成块：正文短于该字符数时并入上一节。 */
    private int minChunkSizeChars = 120;

    /**
     * 启动时入库策略：
     * <ul>
     *   <li>{@code if-empty}（默认）—— manifest 缺失或语料/模型变化时才重建；</li>
     *   <li>{@code always} —— 每次启动强制重建（调试用）；</li>
     *   <li>{@code never} —— 启动不自动入库，只靠管理端点。</li>
     * </ul>
     */
    private String ingestOnStartup = "if-empty";

    /** 索引元信息文件（记录活跃版本、语料哈希、模型、维度等），不是业务数据。 */
    private String manifestPath = "./data/rag-manifest.json";

    /** SimpleVectorStore 的落盘文件（仅 store=simple 时使用）。 */
    private Simple simple = new Simple();

    /** Embedding 服务配置（独立于 Chat 的第二把 key）。 */
    private Embedding embedding = new Embedding();

    /** Milvus 连接与集合配置。 */
    private Milvus milvus = new Milvus();

    /** SimpleVectorStore 配置。 */
    @Getter
    @Setter
    public static class Simple {

        /** 向量快照文件；重启后从这里恢复索引。 */
        private String file = "./data/rag-simple-store.json";
    }

    /** Embedding 配置：mock 用于无 key 冒烟，openai 用于 OpenAI 兼容的 embedding 服务。 */
    @Getter
    @Setter
    public static class Embedding {

        /** {@code mock} 或 {@code openai}（OpenAI 兼容协议）。 */
        private String provider = "openai";

        /**
         * Embedding 服务的 base-url；硅基流动为 https://api.siliconflow.cn。
         * <p><b>不要带 /v1</b>：Spring AI 的 OpenAiApi 默认会把请求路径拼成
         * {@code /v1/embeddings}，base-url 再带 /v1 会变成 /v1/v1/embeddings（404）。
         */
        private String baseUrl = "https://api.siliconflow.cn";

        /** Embedding 服务的 API key；未配置时启动不失败，但检索会降级为“知识库暂不可用”。 */
        private String apiKey = "";

        /** 模型名；硅基流动的 BAAI/bge-m3 输出 1024 维向量。 */
        private String model = "BAAI/bge-m3";

        /** 向量维度：bge-m3 固定 1024；必须与 Milvus collection 的维度一致。 */
        private int dimensions = 1024;

        /**
         * 是否在请求体中携带 {@code dimensions} 参数。
         * <p>bge-m3 的维度是模型固定的，部分 OpenAI 兼容服务不接受该参数，故默认 false；
         * 只有换用支持可变维度的模型（如 text-embedding-3）时才打开。
         */
        private boolean requestDimensions = false;
    }

    /** Milvus 配置：本地 Docker Desktop 默认无鉴权，只需 host + port。 */
    @Getter
    @Setter
    public static class Milvus {

        /** Milvus 服务地址。 */
        private String host = "localhost";

        /** Milvus gRPC 端口，Docker 默认 19530。 */
        private int port = 19530;

        /** 数据库名；Milvus 默认库为 default。 */
        private String database = "default";

        /** 集合名：知识库专用集合，避免与其它业务向量混用。 */
        private String collection = "agent_knowledge";

        /** 相似度度量：COSINE（文本 embedding 的常用选择，分数越大越相似）。 */
        private String metricType = "COSINE";

        /** 索引类型：IVF_FLAT（中小规模知识库足够，构建快、召回稳定）。 */
        private String indexType = "IVF_FLAT";
    }
}
