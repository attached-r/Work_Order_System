package com.agentworkorder.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识语料加载器：资源定位 → 读取 → H1 标题提取 → 按 H2/H3 切节（M3 §7.1）。
 *
 * <p>为什么先按标题切、再按 token 切：
 * 业务规则天然按「状态 / 角色 / 操作」分节。先按标题切能避免把两条不同规则塞进同一个 chunk；
 * 只有超长节才交给 {@code TokenTextSplitter} 二次切片（见 {@link KnowledgeIngestService}）。
 *
 * <p>切出的每个「节」是一个 {@link Document}，带
 * {@code source/title/section/docType/updatedAt} 元数据，供后续入库与引用来源使用。
 * {@code indexVersion} 在入库时再追加（同一份语料每次都使用新的版本号）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeLoader {

    private final RagProperties properties;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public KnowledgeLoader(RagProperties properties) {
        this.properties = properties;
    }

    /**
     * 加载全部知识语料。
     *
     * @return 按标题切好的「节」文档、语料哈希与文档数
     * @throws IOException 资源读取失败时抛出；调用方（入库服务）负责降级
     */
    public LoadedCorpus load() throws IOException {
        // 步骤 1：按配置的通配符定位资源（默认 classpath*:knowledge/*.md）
        Resource[] resources = resolver.getResources(properties.getKnowledgeLocations());
        // 步骤 2：按文件名排序，保证 corpusHash 与切片顺序稳定（不依赖文件系统返回顺序）
        Arrays.sort(resources, Comparator.comparing(KnowledgeLoader::filenameOf));

        List<Document> sections = new ArrayList<>();
        MessageDigest digest = sha256Digest();

        // 步骤 3：逐个文件读取、切节，并把「文件名 + 内容」喂给哈希
        for (Resource resource : resources) {
            String filename = filenameOf(resource);
            String text = readText(resource);
            if (text.isBlank()) {
                log.warn("知识文档为空，已跳过：{}", filename);
                continue;
            }

            digest.update(filename.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(text.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);

            String title = extractTitle(text, filename);
            String docType = docTypeOf(filename);
            String updatedAt = updatedAtOf(resource);

            // 步骤 4：按 H2/H3 切节；过短的节并入上一节
            List<Section> parsed = parseSections(text);
            List<Section> merged = mergeShortSections(parsed, properties.getMinChunkSizeChars());

            // 步骤 5：每个节转成一个 Document（索引版本入院时再加）
            for (Section section : merged) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put(KnowledgeMetadata.SOURCE, filename);
                metadata.put(KnowledgeMetadata.TITLE, title);
                metadata.put(KnowledgeMetadata.SECTION, section.heading);
                metadata.put(KnowledgeMetadata.DOC_TYPE, docType);
                metadata.put(KnowledgeMetadata.UPDATED_AT, updatedAt);

                sections.add(Document.builder()
                        .text(section.body.toString().strip())
                        .metadata(metadata)
                        .build());
            }

            log.info("知识文档已加载：{}（标题={}，节数={}）", filename, title, merged.size());
        }

        // 步骤 6：汇总语料哈希（sha256:十六进制），用于 manifest 判断“语料是否变化”
        String corpusHash = "sha256:" + HexFormat.of().formatHex(digest.digest());
        log.info("知识语料加载完成：文档 {} 篇，节 {} 个，corpusHash={}",
                resources.length, sections.size(), corpusHash);
        return new LoadedCorpus(sections, corpusHash, resources.length);
    }

    /** 用 UTF-8 读取资源文本；{@link TextReader} 是 Spring AI 提供的标准文本读取器。 */
    private static String readText(Resource resource) throws IOException {
        List<Document> documents = new TextReader(resource).get();
        return documents.isEmpty() ? "" : documents.get(0).getText();
    }

    /** 取文件名；不同打包方式下 getFilename 可能为空，退化为路径末段。 */
    private static String filenameOf(Resource resource) {
        String filename = resource.getFilename();
        if (filename != null && !filename.isBlank()) {
            return filename;
        }
        String path = resource.getDescription();
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** 提取第一个 H1 作为文档标题；没有 H1 时用文件名（去掉 .md）兜底。 */
    private static String extractTitle(String text, String filename) {
        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("# ") && !trimmed.startsWith("## ")) {
                return trimmed.substring(2).strip();
            }
        }
        return filename.replaceFirst("\\.md$", "");
    }

    /** 文件名前缀 → 文档类型（与 5 篇语料的编号约定一致）。 */
    private static String docTypeOf(String filename) {
        if (filename.startsWith("01")) {
            return "state-machine";
        }
        if (filename.startsWith("02")) {
            return "permission";
        }
        if (filename.startsWith("03")) {
            return "fields";
        }
        if (filename.startsWith("04")) {
            return "sop";
        }
        if (filename.startsWith("05")) {
            return "faq";
        }
        return "general";
    }

    /** 资源最后修改时间；jar 内资源拿不到时返回 unknown（不影响检索）。 */
    private static String updatedAtOf(Resource resource) {
        try {
            long lastModified = resource.lastModified();
            return lastModified > 0 ? Instant.ofEpochMilli(lastModified).toString() : "unknown";
        } catch (IOException e) {
            return "unknown";
        }
    }

    /**
     * 按 H2/H3 切节。
     *
     * <p>规则：
     * <ol>
     *   <li>H1 已作为文档标题，不再进入正文；</li>
     *   <li>H2 开启一个新节，章节名就是 H2 文本；</li>
     *   <li>H3 开启一个新节，章节名记作「H2 > H3」，保留层级上下文；</li>
     *   <li>第一个标题之前的内容记为「概述」节（通常很短，后续会被合并或跳过）。</li>
     * </ol>
     */
    private static List<Section> parseSections(String text) {
        List<Section> sections = new ArrayList<>();
        StringBuilder intro = new StringBuilder();
        Section current = null;
        String currentH2 = null;

        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();

            // H1：文档标题，跳过
            if (trimmed.startsWith("# ") && !trimmed.startsWith("## ")) {
                continue;
            }
            // H2：开启新节
            if (trimmed.startsWith("## ") && !trimmed.startsWith("### ")) {
                currentH2 = trimmed.substring(3).strip();
                current = new Section(currentH2);
                sections.add(current);
                continue;
            }
            // H3：开启新节，章节名带上级 H2
            if (trimmed.startsWith("### ")) {
                String h3 = trimmed.substring(4).strip();
                String heading = currentH2 == null ? h3 : currentH2 + " > " + h3;
                current = new Section(heading);
                sections.add(current);
                continue;
            }
            // 普通正文
            if (current == null) {
                intro.append(line).append('\n');
            } else {
                current.body.append(line).append('\n');
            }
        }

        // 开头的「概述」最多放 200 字，避免把文档导语当成大段规则
        String introText = intro.toString().strip();
        if (!introText.isEmpty() && introText.length() <= 200) {
            Section overview = new Section("概述");
            overview.body.append(introText);
            sections.add(0, overview);
        }

        // 没有 H2/H3 的文档：整体作为一个「全文」节
        if (sections.isEmpty() && !text.isBlank()) {
            Section whole = new Section("全文");
            whole.body.append(text.strip());
            sections.add(whole);
        }
        return sections;
    }

    /**
     * 过短的节并进上一节：
     * 标题 + 单句这类节单独入库只会产生无效召回，且会浪费 Top-K 名额。
     */
    private static List<Section> mergeShortSections(List<Section> sections, int minChars) {
        List<Section> merged = new ArrayList<>();
        for (Section section : sections) {
            String body = section.body.toString().strip();
            if (body.isEmpty()) {
                continue;
            }
            if (body.length() < minChars && !merged.isEmpty()) {
                Section previous = merged.get(merged.size() - 1);
                previous.body.append("\n\n").append(section.heading).append('\n').append(body);
            } else {
                section.body.setLength(0);
                section.body.append(body);
                merged.add(section);
            }
        }
        return merged;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            // JDK 必带 SHA-256；理论上不会发生
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 加载结果。
     *
     * @param sections      按标题切好的「节」文档
     * @param corpusHash    全量语料的 SHA-256（含文件名，排序后计算）
     * @param documentCount 语料文档数
     */
    public record LoadedCorpus(List<Document> sections, String corpusHash, int documentCount) {
    }

    /** 内部可变的「节」结构：标题 + 正文。 */
    private static final class Section {

        private final String heading;
        private final StringBuilder body = new StringBuilder();

        private Section(String heading) {
            this.heading = heading;
        }
    }
}
