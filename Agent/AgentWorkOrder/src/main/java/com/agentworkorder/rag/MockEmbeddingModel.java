package com.agentworkorder.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 无外部依赖的 Mock Embedding 模型（{@code agent.rag.embedding.provider=mock}）。
 *
 * <p>它<b>不是</b>语义模型，而是一个确定性的“字符哈希向量器”：
 * <ul>
 *   <li><b>确定性</b>：同一段文本永远得到同一个向量，便于重复入库与测试断言；</li>
 *   <li><b>hashing trick</b>：把中文单字与字符二元组哈希到固定维度，再做 L2 归一化；</li>
 *   <li><b>关键词重叠可召回</b>：例如“审核驳回后怎么重新提交”与语料中的“审核驳回”共享二元组，
 *       余弦相似度会明显高于无关文本。</li>
 * </ul>
 *
 * <p>用途仅限开发/冒烟：验证“加载 → 切片 → 向量化 → 写入 → 检索 → 工具 → SSE”整条链路。
 * 真实语义检索必须配置 {@code provider=openai} + 真实的 embedding 服务。
 */
public class MockEmbeddingModel implements EmbeddingModel {

    /** FNV-1a 32 位偏移基数。 */
    private static final int FNV_OFFSET_BASIS = 0x811c9dc5;

    /** FNV-1a 32 位质数。 */
    private static final int FNV_PRIME = 0x01000193;

    private final int dimensions;

    public MockEmbeddingModel(int dimensions) {
        // 维度必须为正数，且与 Milvus collection / manifest 保持一致
        if (dimensions <= 0) {
            throw new IllegalArgumentException("MockEmbeddingModel 的 dimensions 必须大于 0");
        }
        this.dimensions = dimensions;
    }

    /**
     * 对一批文本生成向量。
     *
     * <p>步骤：
     * <ol>
     *   <li>逐个取 {@link EmbeddingRequest#getInstructions()} 中的文本；</li>
     *   <li>把文本映射成固定维度的稀疏哈希向量；</li>
     *   <li>按原始顺序包装成 {@link EmbeddingResponse} 返回。</li>
     * </ol>
     */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        List<Embedding> embeddings = new ArrayList<>(instructions.size());
        for (int i = 0; i < instructions.size(); i++) {
            embeddings.add(new Embedding(vectorize(instructions.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    /**
     * 单文档向量化。{@link EmbeddingModel} 把该方法声明为抽象方法，
     * 向量库在入库/检索时也会直接调它，因此必须显式实现。
     */
    @Override
    public float[] embed(Document document) {
        return vectorize(document == null ? null : document.getText());
    }

    /** 直接返回配置维度，避免父接口默认实现为了探测维度而额外做一次 embedding。 */
    @Override
    public int dimensions() {
        return dimensions;
    }

    /**
     * 文本 → 向量。
     *
     * <p>特征取「单字」与「相邻字符二元组」：
     * <ul>
     *   <li>单字权重 0.5，保证极短文本（如“派单”）也有特征；</li>
     *   <li>二元组权重 1.0，承载主要的词序信息；</li>
     *   <li>每个特征用 FNV-1a 哈希到 [0, dimensions)，并用哈希最高位决定符号，
     *       减少不同特征落到同一维时的正向叠加偏差；</li>
     *   <li>最后做 L2 归一化，使不同长度文本的余弦相似度可比。</li>
     * </ul>
     */
    private float[] vectorize(String text) {
        float[] vector = new float[dimensions];
        if (text == null || text.isEmpty()) {
            return vector;
        }
        // 统一小写并去掉首尾空白，减少大小写/空格带来的噪声
        String normalized = text.toLowerCase().strip();
        // 1) 单字特征
        for (int i = 0; i < normalized.length(); i++) {
            addFeature(vector, normalized.substring(i, i + 1), 0.5f);
        }
        // 2) 二元组特征（相邻两个字符）
        for (int i = 0; i + 1 < normalized.length(); i++) {
            addFeature(vector, normalized.substring(i, i + 2), 1.0f);
        }
        // 3) L2 归一化；零向量直接返回，避免除零
        double norm = 0.0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm > 0.0) {
            float scale = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= scale;
            }
        }
        return vector;
    }

    /** 把一个特征按“索引 + 符号”累加到向量上。 */
    private void addFeature(float[] vector, String feature, float weight) {
        int hash = fnv1a(feature);
        int index = Math.floorMod(hash, dimensions);
        // 最高位作符号位：同一特征始终同号，不同特征尽量分散
        float sign = ((hash >>> 31) & 1) == 0 ? 1.0f : -1.0f;
        vector[index] += sign * weight;
    }

    /** FNV-1a 32 位哈希：实现简单、分布稳定、无需额外依赖。 */
    private static int fnv1a(String value) {
        int hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
