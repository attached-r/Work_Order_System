package com.agentworkorder.rag;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.io.File;

/**
 * 向量库装配：{@code agent.rag.store} 二选一（M3）。
 *
 * <ul>
 *   <li>{@code simple} —— {@link SimpleVectorStore}：进程内 Map + JSON 文件快照，
 *       零中间件，适合本地无 Docker 的场景；</li>
 *   <li>{@code milvus} —— {@link MilvusVectorStore}：连本地 Docker Desktop 的 Milvus（默认 19530、无鉴权）。</li>
 * </ul>
 *
 * <p><b>为什么手工构造 MilvusVectorStore 而不用 starter 自动配置</b>：
 * 自动配置的键名/生效条件随版本变化（设计文档 R13），手工 builder 的 API 更稳定，
 * 也便于把“维度、集合名、索引、度量”四个关键参数与 {@link RagProperties} 一一对齐。
 *
 * <p><b>为什么 Milvus 相关 Bean 加 {@code @Lazy}</b>：
 * Milvus 未启动时，若在容器启动阶段创建客户端/建集合，会直接让应用起不来。
 * 延迟到第一次检索/入库时再创建，异常由调用方捕获并降级，
 * 从而保证“知识库挂了，工单问答照常可用”。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true", matchIfMissing = true)
public class VectorStoreConfig {

    // ==================================================================================
    // 分支一：SimpleVectorStore（单文件、零中间件）
    // ==================================================================================

    /**
     * Simple 向量库：启动时若快照文件存在则加载，保证重启不丢索引。
     */
    @Bean
    @ConditionalOnProperty(name = "agent.rag.store", havingValue = "simple", matchIfMissing = true)
    public SimpleVectorStore simpleVectorStore(@Qualifier("ragEmbeddingModel") EmbeddingModel embeddingModel,
                                               RagProperties props) {
        // 步骤 1：用 RAG 专用 embedding 模型构造内存向量库
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();

        // 步骤 2：若上次入库的快照存在，直接恢复（文件库里保存了文档、元数据与向量）
        File file = new File(props.getSimple().getFile());
        if (file.isFile()) {
            try {
                store.load(file);
                log.info("已从 {} 恢复 SimpleVectorStore", file.getAbsolutePath());
            } catch (Exception e) {
                // 快照损坏不应阻塞启动：当作空库，后续 reindex 会重建
                log.warn("加载 SimpleVectorStore 快照失败，按空库处理：{}", e.toString());
            }
        } else {
            log.info("SimpleVectorStore 快照不存在（{}），等待首次入库", file.getAbsolutePath());
        }
        return store;
    }

    // ==================================================================================
    // 分支二：Milvus（本地 Docker Desktop，默认 19530，无密码/无鉴权）
    // ==================================================================================

    /**
     * Milvus gRPC 客户端。
     *
     * <p>本地 Docker 部署未开启鉴权，因此只设置 host + port，不调用 withAuthorization。
     * 连接超时使用 SDK 默认值即可。
     */
    @Bean(destroyMethod = "close")
    @Lazy
    @ConditionalOnProperty(name = "agent.rag.store", havingValue = "milvus")
    public MilvusServiceClient milvusServiceClient(RagProperties props) {
        RagProperties.Milvus milvus = props.getMilvus();
        log.info("创建 Milvus 客户端：{}:{}（无鉴权）", milvus.getHost(), milvus.getPort());
        return new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(milvus.getHost())
                        .withPort(milvus.getPort())
                        .build());
    }

    /**
     * Milvus 向量库。
     *
     * <p>关键参数及其依据：
     * <ol>
     *   <li>{@code databaseName=default} —— Milvus 默认库；</li>
     *   <li>{@code collectionName=agent_knowledge} —— 知识库专用集合，避免与其它业务向量混用；</li>
     *   <li>{@code embeddingDimension=1024} —— {@code BAAI/bge-m3} 的固定输出维度，
     *       必须与 {@code agent.rag.embedding.dimensions} 一致；</li>
     *   <li>{@code metricType=COSINE} —— 文本 embedding 的常用度量，分数越大越相似，
     *       与 SimpleVectorStore 的余弦相似度语义一致，切换存储时阈值不用改；</li>
     *   <li>{@code indexType=IVF_FLAT} —— 中小规模知识库（百级 chunk）构建快、召回稳定；
     *       数据量很大时可换 AUTOINDEX/HNSW，但不属于 M3 范围；</li>
     *   <li>{@code initializeSchema=true} —— 集合不存在时自动建表（1.1.x 默认 false）。</li>
     * </ol>
     */
    @Bean
    @Lazy
    @ConditionalOnProperty(name = "agent.rag.store", havingValue = "milvus")
    public MilvusVectorStore milvusVectorStore(@Qualifier("ragEmbeddingModel") EmbeddingModel embeddingModel,
                                               MilvusServiceClient milvusServiceClient,
                                               RagProperties props) {
        RagProperties.Milvus milvus = props.getMilvus();
        int dimensions = props.getEmbedding().getDimensions();
        log.info("初始化 MilvusVectorStore：collection={} dimension={} metric={} index={}",
                milvus.getCollection(), dimensions, milvus.getMetricType(), milvus.getIndexType());

        return MilvusVectorStore.builder(milvusServiceClient, embeddingModel)
                .databaseName(milvus.getDatabase())
                .collectionName(milvus.getCollection())
                .embeddingDimension(dimensions)
                // 配置用字符串表达，运行时转成 Milvus SDK 的枚举；非法值会在启动时暴露
                .metricType(MetricType.valueOf(milvus.getMetricType()))
                .indexType(IndexType.valueOf(milvus.getIndexType()))
                .initializeSchema(true)
                .build();
    }
}
