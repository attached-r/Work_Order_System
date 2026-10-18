package com.agentworkorder.rag;

/**
 * 索引元信息（不是业务数据），落盘为 {@code agent.rag.manifest-path} 指向的小 JSON。
 *
 * <p>它回答三个问题：
 * <ol>
 *   <li>当前检索应该用哪个索引版本（{@code activeVersion}）；</li>
 *   <li>语料和 embedding 配置有没有变（{@code corpusHash / embeddingProvider / embeddingModel / dimensions}）；</li>
 *   <li>上次入库了多少内容、什么时候入的（{@code chunkCount / documentCount / ingestedAt}）。</li>
 * </ol>
 *
 * <p>用 record 而非实体：它只是一份快照，不需要更新语义，也不需要进数据库。
 */
public record RagManifest(
        String activeVersion,
        String corpusHash,
        String embeddingProvider,
        String embeddingModel,
        int dimensions,
        int chunkCount,
        int documentCount,
        String ingestedAt
) {
}
