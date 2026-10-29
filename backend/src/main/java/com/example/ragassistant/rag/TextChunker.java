package com.example.ragassistant.rag;

import com.example.ragassistant.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 重叠滑窗分块：相邻块共享一小段内容，以减少答案恰好跨越块边界时的信息丢失。
 * 使用 Unicode 码点而非 UTF-16 char，避免把 emoji 等字符拆成两个代理项。
 * 这里刻意保留直观算法，便于学习；生产可替换为标题/段落感知或 token 分块器。
 */
@Component
public class TextChunker {
    private final RagProperties properties;
    public TextChunker(RagProperties properties) { this.properties = properties; }

    /**
     * 把提取器返回的全文按固定滑动窗口切块，不按 PDF 页码、标题或段落切分。
     * 默认 chunkSize=600、chunkOverlap=100，例如区间为 [0,600)、[500,1100)、[1000,1600)。
     * 重叠保留边界上下文，但会增加向量数、模型调用量和重复召回，需要按业务调整。
     */
    public List<Chunk> split(String text) {
        // Java 的 char 可能只是一个字符的一半；codePoints 将表情等补充字符作为完整码点处理。
        // 这里的长度与下面的偏移都按码点计算，既不是 UTF-16 char 数，也不是模型 token 数。
        int[] points = text.codePoints().toArray();
        List<Chunk> chunks = new ArrayList<>();
        for (int start = 0; start < points.length;) {
            // 最后一块不足 chunkSize 时取剩余全文；区间始终为左闭右开 [start, end)。
            int end = Math.min(start + properties.chunkSize(), points.length);
            // 这个 String 构造器的第三个参数是“码点个数”，因此传 end-start，而不是 end。
            String part = new String(points, start, end - start);
            // 跳过完全空白的窗口；用已保留块数作为序号，使输出块序号连续。
            if (!part.isBlank()) chunks.add(new Chunk(chunks.size(), start, end, part));
            // 已经覆盖末尾就停止，避免因重叠再次切出一个仅包含尾部重复内容的块。
            if (end == points.length) break;
            // 回退 overlap 个码点再开始下一块；配置已约束 0 <= overlap < chunkSize，保证窗口前进。
            start = end - properties.chunkOverlap();
        }
        return chunks;
    }

    /**
     * 一段文本及其定位信息，不是整份文件。index 从 0 开始，用于稳定块 ID 和来源展示。
     * start/end 指提取并清洗后的全文码点偏移，end 不包含在内，不是文件字节偏移或 PDF 页码。
     */
    public record Chunk(int index, int start, int end, String text) { }
}
