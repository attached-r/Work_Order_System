package com.agentworkorder.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识入库服务：加载 → 切片 → 向量化 → 版本化写入 → manifest（M3 §七）。
 *
 * <p>核心设计是<b>版本化写入</b>，而不是“先清空再写入”：
 * <ol>
 *   <li>每次入库生成一个新的 {@code indexVersion}，所有 chunk 都带上这个版本；</li>
 *   <li>全部写入成功后，才原子切换 manifest 的 {@code activeVersion}；</li>
 *   <li>检索始终带 {@code indexVersion == activeVersion} 过滤；</li>
 *   <li>失败时 activeVersion 不动，旧索引继续服务，不会出现“半空索引”。</li>
 * </ol>
 *
 * <p>同时用 {@link AtomicBoolean} 做单飞：重建期间再次触发直接返回 409（管理端点）
 * 或跳过（启动流程），避免两批向量互相覆盖。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeIngestService {

    /** 版本号时间格式：UTC，例：20261018T083000Z。 */
    private static final DateTimeFormatter VERSION_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final RagProperties properties;
    private final KnowledgeLoader loader;
    private final RagManifestStore manifestStore;
    /**
     * 用 ObjectProvider 而不是直接注入：Milvus 客户端/向量库 Bean 是 @Lazy 的，
     * 只有真正入库时才创建；Milvus 未启动时异常由本类捕获并降级。
     */
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    /** 只有 store=simple 时才有值；用于入库后把内存向量快照落盘。 */
    private final ObjectProvider<SimpleVectorStore> simpleVectorStoreProvider;

    /** 二次切片器：参数来自配置，构造一次复用。 */
    private final TokenTextSplitter splitter;

    /** 单飞锁：true 表示已有一次重建在跑。 */
    private final AtomicBoolean ingesting = new AtomicBoolean(false);

    public KnowledgeIngestService(RagProperties properties,
                                  KnowledgeLoader loader,
                                  RagManifestStore manifestStore,
                                  ObjectProvider<VectorStore> vectorStoreProvider,
                                  ObjectProvider<SimpleVectorStore> simpleVectorStoreProvider) {
        this.properties = properties;
        this.loader = loader;
        this.manifestStore = manifestStore;
        this.vectorStoreProvider = vectorStoreProvider;
        this.simpleVectorStoreProvider = simpleVectorStoreProvider;
        // 步骤 0：按 M3 §4.3 的参数构造切片器；1.1.2 的 TokenTextSplitter 没有 overlap 参数，
        // 用「标题分节 + 每块标题前缀」补足上下文
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(properties.getChunkSize())
                .withMinChunkSizeChars(properties.getMinChunkSizeChars())
                .withKeepSeparator(true)
                .build();
    }

    /**
     * 启动自动入库。
     *
     * <p>策略：{@code never} 跳过；{@code always} 强制重建；{@code if-empty} 交给
     * {@link #ingest(boolean)} 内的 manifest 判断。
     *
     * <p><b>失败只告警、不抛出</b>：知识库是增强能力，不能因为 Milvus/embedding 不可用而让应用起不来。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ingestOnStartup() {
        String policy = properties.getIngestOnStartup();
        if ("never".equalsIgnoreCase(policy)) {
            log.info("RAG 启动入库策略为 never，跳过自动入库");
            return;
        }
        boolean force = "always".equalsIgnoreCase(policy);
        try {
            IngestResult result = ingest(force);
            log.info("RAG 启动入库完成：status={} version={} chunks={}",
                    result.status(), result.activeVersion(), result.chunkCount());
        } catch (IngestInProgressException e) {
            log.warn("RAG 启动入库被跳过：已有重建任务在进行");
        } catch (Exception e) {
            // 关键纪律：任何入库失败都不阻塞应用启动，也不影响工单查询
            log.warn("RAG 启动入库失败（不影响对话主流程）：{}", rootMessage(e));
        }
    }

    /**
     * 执行一次入库。
     *
     * @param force true 表示忽略 manifest 的“已是最新”判断，强制重建
     * @return 入库结果（重建或跳过）
     * @throws IngestInProgressException 已有重建在跑
     * @throws IOException               语料读取或 manifest 写入失败
     */
    public IngestResult ingest(boolean force) throws IOException {
        // 步骤 1：单飞。CAS 失败说明另一个线程正在重建
        if (!ingesting.compareAndSet(false, true)) {
            throw new IngestInProgressException();
        }
        try {
            // 步骤 2：加载语料并按标题切节
            KnowledgeLoader.LoadedCorpus corpus = loader.load();
            if (corpus.sections().isEmpty()) {
                throw new IllegalStateException("知识语料为空：请检查 " + properties.getKnowledgeLocations());
            }

            // 步骤 3：判断是否可以跳过（语料哈希 + embedding 配置完全一致）
            RagManifest previous = manifestStore.get();
            if (!force && isUpToDate(previous, corpus)) {
                log.info("RAG 索引已是最新，跳过入库：version={} corpusHash={}",
                        previous.activeVersion(), corpus.corpusHash());
                return new IngestResult("SKIPPED", previous.activeVersion(), previous.chunkCount(),
                        previous.documentCount(), previous.corpusHash(), "语料与 embedding 配置未变化");
            }

            // 步骤 4：生成新版本号（UTC 时间戳 + 语料哈希前 8 位）
            String version = newVersion(corpus.corpusHash());
            log.info("开始 RAG 入库：version={} sections={} documents={}",
                    version, corpus.sections().size(), corpus.documentCount());

            // 步骤 5：TokenTextSplitter 二次切片 + 标题前缀 + 确定性 ID + indexVersion 元数据
            List<Document> chunks = buildChunks(corpus, version);

            // 步骤 6：写入向量库；此处才真正创建 Milvus 客户端（可能抛异常 → 本次入库中止，旧索引不受影响）
            VectorStore vectorStore = vectorStoreProvider.getObject();
            vectorStore.add(chunks);
            log.info("向量写入完成：version={} chunks={}", version, chunks.size());

            // 步骤 7：SimpleVectorStore 需要显式落盘；Milvus 不需要（本方法内部会跳过）
            persistSimpleStore();

            // 步骤 8：写入 manifest。只有这一步成功，新版本才真正“生效”
            RagManifest manifest = new RagManifest(
                    version,
                    corpus.corpusHash(),
                    embeddingProvider(),
                    embeddingModel(),
                    dimensions(),
                    chunks.size(),
                    corpus.documentCount(),
                    Instant.now().toString());
            manifestStore.save(manifest);

            // 步骤 9：尽力清理旧版本向量；失败只告警，检索过滤器已经保证旧版本不会被命中
            cleanupOldVersions(vectorStore, version);

            return new IngestResult("INGESTED", version, chunks.size(), corpus.documentCount(),
                    corpus.corpusHash(), "重建完成");
        } finally {
            ingesting.set(false);
        }
    }

    /** 状态查询：给管理端点用。 */
    public IngestStatus status() {
        RagManifest manifest = manifestStore.get();
        return new IngestStatus(
                properties.isEnabled(),
                properties.getStore(),
                manifest == null ? null : manifest.activeVersion(),
                manifest == null ? 0 : manifest.chunkCount(),
                manifest == null ? 0 : manifest.documentCount(),
                embeddingProvider(),
                embeddingModel(),
                dimensions(),
                manifest == null ? null : manifest.ingestedAt(),
                ingesting.get());
    }

    /**
     * 把一个「节」切成若干 chunk。
     *
     * <p>每个 chunk 做四件事：
     * <ol>
     *   <li>正文前补「《文档标题》> 章节标题」前缀——切片切断上下文时，模型仍知道出处；</li>
     *   <li>继承 source/title/section/docType/updatedAt 元数据；</li>
     *   <li>追加本次的 {@code indexVersion}，供检索过滤；</li>
     *   <li>生成确定性 ID：sha256(source#section#序号#版本#正文)。
     *       版本号纳入 ID，避免跨版本重建时主键冲突。</li>
     * </ol>
     */
    private List<Document> buildChunks(KnowledgeLoader.LoadedCorpus corpus, String version) {
        List<Document> chunks = new ArrayList<>();
        for (Document sectionDocument : corpus.sections()) {
            // 步骤 1：对这一节做 token 级二次切片；失败则退化为整节（宁可块大，不要丢内容）
            List<Document> parts;
            try {
                parts = splitter.apply(List.of(sectionDocument));
            } catch (Exception e) {
                log.warn("知识节切片失败，按整节入库：{}", e.toString());
                parts = List.of(sectionDocument);
            }

            Map<String, Object> baseMetadata = sectionDocument.getMetadata();
            String title = String.valueOf(baseMetadata.getOrDefault(KnowledgeMetadata.TITLE, ""));
            String section = String.valueOf(baseMetadata.getOrDefault(KnowledgeMetadata.SECTION, ""));
            String source = String.valueOf(baseMetadata.getOrDefault(KnowledgeMetadata.SOURCE, ""));

            for (int i = 0; i < parts.size(); i++) {
                String partText = parts.get(i).getText();
                if (partText == null || partText.isBlank()) {
                    continue;
                }

                // 步骤 2：标题前缀，提升可读性与 embedding 的语义集中度
                String prefixedText = "《" + title + "》> " + section + "\n\n" + partText;

                // 步骤 3：完整继承元数据并追加索引版本
                Map<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
                metadata.put(KnowledgeMetadata.INDEX_VERSION, version);

                // 步骤 4：确定性 ID，同版本重复入库不会产生随机 ID。
                // 只取 SHA-256 前 32 位十六进制（128 bit）：Milvus 自动建表的 doc_id 是 VARCHAR(36)，
                // 完整 64 位会因超出字段长度导致入库失败；128 bit 对百级 chunk 的碰撞概率可忽略。
                String id = sha256Hex(source + "#" + section + "#" + i + "#" + version + "#" + partText)
                        .substring(0, 32);

                chunks.add(Document.builder()
                        .id(id)
                        .text(prefixedText)
                        .metadata(metadata)
                        .build());
            }
        }
        return chunks;
    }

    /** 判断当前索引是否仍然有效：语料哈希、provider、model、维度四项都要一致。 */
    private boolean isUpToDate(RagManifest manifest, KnowledgeLoader.LoadedCorpus corpus) {
        if (manifest == null || !StringUtils.hasText(manifest.activeVersion())) {
            return false;
        }
        return Objects.equals(manifest.corpusHash(), corpus.corpusHash())
                && Objects.equals(manifest.embeddingProvider(), embeddingProvider())
                && Objects.equals(manifest.embeddingModel(), embeddingModel())
                && manifest.dimensions() == dimensions();
    }

    /** SimpleVectorStore 是内存 Map + 文件快照；入库后必须 save 才能重启恢复。 */
    private void persistSimpleStore() throws IOException {
        SimpleVectorStore simpleStore = simpleVectorStoreProvider.getIfAvailable();
        if (simpleStore == null) {
            return; // store=milvus
        }
        File file = new File(properties.getSimple().getFile());
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + parent.getAbsolutePath());
        }
        simpleStore.save(file);
        log.info("SimpleVectorStore 快照已保存：{}", file.getAbsolutePath());
    }

    /**
     * 清理旧版本向量。
     *
     * <p>Milvus 支持按 filter 删除；SimpleVectorStore 1.1.2 不支持
     * （会抛 {@code UnsupportedOperationException}），旧数据残留在 JSON 里但检索过滤不会命中。
     * 两种情况都只告警，不影响本次入库成功。
     */
    private void cleanupOldVersions(VectorStore vectorStore, String newVersion) {
        try {
            Filter.Expression stale = new FilterExpressionBuilder()
                    .ne(KnowledgeMetadata.INDEX_VERSION, newVersion)
                    .build();
            vectorStore.delete(stale);
            log.info("已清理旧版本向量：indexVersion != {}", newVersion);
        } catch (Exception e) {
            log.warn("清理旧版本向量失败（不影响检索正确性，过滤器会排除旧版本）：{}", e.toString());
        }
    }

    /** 版本号：UTC 时间戳 + 语料哈希前 8 位，既可排序又可追溯到语料。 */
    private static String newVersion(String corpusHash) {
        String hash = corpusHash.replace("sha256:", "");
        String suffix = hash.length() >= 8 ? hash.substring(0, 8) : hash;
        return VERSION_TIME.format(Instant.now()) + "-" + suffix;
    }

    /** SHA-256 十六进制串（用于确定性文档 ID）。 */
    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String embeddingProvider() {
        return properties.getEmbedding().getProvider();
    }

    private String embeddingModel() {
        return properties.getEmbedding().getModel();
    }

    private int dimensions() {
        return properties.getEmbedding().getDimensions();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.toString();
    }

    /**
     * 入库结果。
     *
     * @param status         INGESTED / SKIPPED
     * @param activeVersion  本次（或当前）活跃版本
     * @param chunkCount     切片数
     * @param documentCount  语料文档数
     * @param corpusHash     语料哈希
     * @param message        人读说明
     */
    public record IngestResult(String status, String activeVersion, int chunkCount,
                               int documentCount, String corpusHash, String message) {
    }

    /**
     * 知识库状态（管理端点返回体）。
     */
    public record IngestStatus(boolean enabled, String store, String activeVersion, int chunkCount,
                               int documentCount, String embeddingProvider, String embeddingModel,
                               int dimensions, String ingestedAt, boolean ingesting) {
    }

    /** 单飞冲突：管理端点映射为 409。 */
    public static class IngestInProgressException extends RuntimeException {

        public IngestInProgressException() {
            super("知识库重建正在进行中");
        }
    }
}
