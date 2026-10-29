package com.example.ragassistant.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * 绑定 application.yml 中 app.rag 下的配置，作为不可变参数对象注入提取器、分块器和向量索引。
 * 默认值在配置文件中，不写在 record 中；@Validated 让非法配置在启动阶段尽早失败。
 *
 * @param storageRoot 原件存储目录，相对路径以应用工作目录为基准
 * @param chunkSize 每块最多包含的 Unicode 码点数，默认 600，不是模型 token 数
 * @param chunkOverlap 相邻块共享的码点数，默认 100，必须小于 chunkSize
 * @param topK 问答时最多召回多少个文本块，不影响上传分块数量
 * @param similarityThreshold 检索最低相似度，不是回答正确率
 * @param maxExtractedCharacters 提取全文字符上限，由 Java 字符串或 Tika handler 计数，不是 token 上限
 * @param maxContextCharacters 问答时交给聊天模型的证据字符预算，不是每块大小
 * @param embeddingBatchSize 业务层每批向量化的块数，默认 16；智普 Embedding-3 数组最多 64 条
 */
@Validated
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
        @NotNull Path storageRoot,
        @Min(100) @Max(4000) int chunkSize,
        @Min(0) int chunkOverlap,
        @Min(1) @Max(20) int topK,
        @DecimalMin("0.0") @DecimalMax("1.0") double similarityThreshold,
        @Min(1000) int maxExtractedCharacters,
        @Min(1000) int maxContextCharacters,
        @Min(1) @Max(64) int embeddingBatchSize) {

    public RagProperties {
        // 这是 record 的紧凑构造器：创建配置对象时检查跨字段关系，避免切块窗口不前进。
        // @Min 等注解只检查单个数值，无法表达 overlap 必须小于 size，因此还需这项约束。
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunk-overlap 必须满足 0 <= overlap < chunk-size");
        }
    }
}
