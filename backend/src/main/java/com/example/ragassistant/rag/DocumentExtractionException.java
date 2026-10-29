package com.example.ragassistant.rag;

/**
 * 应用自己定义的、可直接展示给用户的提取失败原因。
 * 提取器抛出本异常，Worker 捕获后将其固定提示写入 MySQL error_message；其他第三方异常需转换。
 * 构造器只接受预定义 Reason，避免任意解析器错误中的路径、原文或凭据进入页面。
 */
public class DocumentExtractionException extends IllegalArgumentException {
    /** 失败原因与处理建议绑定：同一个异常类型也能区分“无文字”与“超过上限”。 */
    public enum Reason {
        PDF_NO_TEXT("PDF 没有可提取的文字，可能是扫描件或空白文档。扫描件需先进行 OCR 识别，转换为带文字层的 PDF 或 UTF-8 文本后重新上传。当前未启用 OCR，直接重试不会解决。"),
        NO_TEXT("文档没有可提取的文字，请检查空白内容或扫描图片；图片文字需要先进行 OCR 识别。"),
        TEXT_TOO_LONG("文档提取的文字超过配置上限，请拆分文档后重新上传。");

        private final String message;
        Reason(String message) { this.message = message; }
    }

    public DocumentExtractionException(Reason reason) { super(reason.message); }
}
