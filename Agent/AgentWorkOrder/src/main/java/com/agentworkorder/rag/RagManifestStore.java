package com.agentworkorder.rag;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * manifest 的读写与内存快照。
 *
 * <p>为什么单独抽一个组件：
 * <ul>
 *   <li>入库服务要写、检索服务要读「当前活跃版本」，两者不应互相依赖；</li>
 *   <li>检索是高频路径，不能每次搜索都读磁盘，故在内存保留一份 volatile 快照；</li>
 *   <li>文件写入采用「临时文件 + 原子替换」，避免进程中途崩溃留下半截 JSON。</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true", matchIfMissing = true)
public class RagManifestStore {

    private final RagProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper()
            // manifest 未来可能加字段，旧版本读取时不应因未知字段失败
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 内存中的当前 manifest；未入库时为 null。 */
    private volatile RagManifest current;

    public RagManifestStore(RagProperties properties) {
        this.properties = properties;
    }

    /** 应用启动时读取一次；文件缺失/损坏都只告警，按“未入库”处理。 */
    @PostConstruct
    public void load() {
        Path path = path();
        if (!Files.isRegularFile(path)) {
            log.info("RAG manifest 不存在（{}），等待首次入库", path.toAbsolutePath());
            return;
        }
        try {
            current = objectMapper.readValue(path.toFile(), RagManifest.class);
            log.info("已加载 RAG manifest：activeVersion={} chunkCount={} model={} dimensions={}",
                    current.activeVersion(), current.chunkCount(), current.embeddingModel(), current.dimensions());
        } catch (Exception e) {
            log.warn("RAG manifest 读取失败，按未入库处理：{}", e.toString());
            current = null;
        }
    }

    /** 当前 manifest；未入库时为 {@code null}。 */
    public RagManifest get() {
        return current;
    }

    /**
     * 先落盘、再切换内存快照。
     *
     * <p>顺序很重要：如果落盘失败，内存里的 activeVersion 保持旧值，
     * 检索继续走旧索引，不会出现「内存说新版本、磁盘说旧版本」的分裂。
     */
    public void save(RagManifest manifest) throws IOException {
        Path path = path();
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // 临时文件必须与目标同目录，原子替换才成立
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), manifest);

        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 少数文件系统不支持原子移动；退化为普通替换
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }

        current = manifest;
        log.info("RAG manifest 已更新：activeVersion={} corpusHash={} chunks={}",
                manifest.activeVersion(), manifest.corpusHash(), manifest.chunkCount());
    }

    private Path path() {
        return Path.of(properties.getManifestPath());
    }
}
