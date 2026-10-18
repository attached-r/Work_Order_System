package com.agentworkorder.rag;

/**
 * 知识库 {@code Document} 的元数据键常量。
 *
 * <p>入库侧（{@link KnowledgeIngestService}）与检索侧（{@link KnowledgeRetriever}）必须使用
 * <b>同一组字符串</b>：一旦拼写不一致，检索过滤会静默失效（查不到任何片段），
 * 因此像 {@code ToolContextKeys} 一样收拢到一个类里作为唯一来源。
 */
public final class KnowledgeMetadata {

    /** 文件名，如 {@code 04-operating-sop.md}。 */
    public static final String SOURCE = "source";

    /** 文档标题（H1），如「工单操作 SOP」。 */
    public static final String TITLE = "title";

    /** 章节标题（H2 或「H2 > H3」），如「审核驳回后怎么处理」。 */
    public static final String SECTION = "section";

    /** 文档类型：state-machine / permission / fields / sop / faq / general。 */
    public static final String DOC_TYPE = "docType";

    /** 语料更新时间（ISO-8601 字符串，取资源 lastModified；打包后不可知时为 unknown）。 */
    public static final String UPDATED_AT = "updatedAt";

    /** 本次入库的索引版本；检索时用它过滤出活跃版本，避免新旧索引混用。 */
    public static final String INDEX_VERSION = "indexVersion";

    private KnowledgeMetadata() {
    }
}
