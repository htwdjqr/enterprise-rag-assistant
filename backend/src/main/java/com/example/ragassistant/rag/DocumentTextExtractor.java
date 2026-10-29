package com.example.ragassistant.rag;

import com.example.ragassistant.config.RagProperties;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 将原文件转换成供分块器使用的全文字符串；此步骤不调用 Embedding、聊天模型或 Qdrant。
 * TXT/MD 按 UTF-8 读取，PDF/Word 交给 Apache Tika，只提取文字，不执行宏或递归解析附件。
 * 扫描 PDF 只有图片，没有可读文字层；本项目未接入 OCR，会明确失败而非生成空向量。
 */
@Component
public class DocumentTextExtractor {
    private final RagProperties properties;
    public DocumentTextExtractor(RagProperties properties) { this.properties = properties; }

    /**
     * @param path 经 LocalFileStorage 校验的实际磁盘路径，文件名通常是 UUID
     * @param originalFilename 用户原始展示名，用于格式分支和帮助 Tika 检测文件类型，不用作磁盘路径
     * @return 去除 NUL、BOM 和首尾空白后的非空全文，后续块偏移都基于这份文本
     * @throws Exception 读取、解析、长度超限或没有文字时向 Worker 抛出，由它记录失败状态
     */
    public String extract(Path path, String originalFilename) throws Exception {
        String lower = originalFilename.toLowerCase(java.util.Locale.ROOT);
        String text;
        if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            // 学习示例约定纯文本为 UTF-8；格式错误时失败，不悄悄写入乱码。
            text = Files.readString(path, StandardCharsets.UTF_8);
            // String.length 统计 UTF-16 单元，不是模型 token 数；限制的是提取结果的字符规模。
            // 纯文本先读再检查，文件字节大小另由上传阶段的 multipart 上限约束。
            if (text.length() > properties.maxExtractedCharacters()) {
                throw new DocumentExtractionException(DocumentExtractionException.Reason.TEXT_TOO_LONG);
            }
        } else {
            // Tika 以 SAX 事件输出解析结果，handler 收集正文；达到上限时终止并抛异常。
            // 不接受被截断的前半篇文档，避免缺失条款仍被当作完整资料发布。
            BodyContentHandler handler = new BodyContentHandler(properties.maxExtractedCharacters());
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, originalFilename);
            // ParseContext 向具体解析器传递行为配置；AutoDetectParser 再选择 PDF/Word 等解析器。
            ParseContext context = new ParseContext();
            PDFParserConfig pdf = new PDFParserConfig();
            // NO_OCR 只读 PDF 现有文字层，不能识别扫描图片中的汉字，也不会自动调用云端 OCR。
            pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
            context.set(PDFParserConfig.class, pdf);
            // 不递归处理 Office / PDF 内嵌附件，限制解析范围与资源消耗。
            context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
                @Override public boolean shouldParseEmbedded(Metadata m) { return false; }
                @Override public void parseEmbedded(InputStream in, org.xml.sax.ContentHandler h,
                        Metadata m, boolean outputHtml) { }
            });
            // try-with-resources 确保成功或异常后都关闭文件流，避免句柄泄漏、文件长期被占用。
            try (InputStream input = Files.newInputStream(path)) {
                new AutoDetectParser().parse(input, handler, metadata, context);
            }
            text = handler.toString();
        }
        // 仅移除常见无效字符和首尾空白，保留正文内部换行，方便后续阅读与引用。
        text = text.replace("\u0000", "").replace("\uFEFF", "").strip();
        if (text.isBlank()) {
            // 空文本不应继续分块、向量化；PDF 无文字可能是扫描件，也可能是空白页，提示保留两种可能。
            throw new DocumentExtractionException(lower.endsWith(".pdf")
                    ? DocumentExtractionException.Reason.PDF_NO_TEXT : DocumentExtractionException.Reason.NO_TEXT);
        }
        return text;
    }
}
