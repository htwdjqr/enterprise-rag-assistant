package com.example.ragassistant.rag;

import com.example.ragassistant.config.RagProperties;
import com.example.ragassistant.domain.KnowledgeDocument;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 隔离 Spring AI 的向量接口。VectorStore.add 内部调用 EmbeddingModel，再写入 Qdrant。
 * 业务代码不需要自己序列化浮点数组，但仍能看到完整的文档→分块→向量流程。
 * 当前配置由 QdrantVectorStore 存储索引、智普 embedding-3 生成向量；DeepSeek 不参与上传索引。
 */
@Component
public class KnowledgeVectorIndex {
    // 面向接口编程：Spring 按 application.yml 自动创建 Qdrant 实现并注入其 EmbeddingModel。
    private final VectorStore store;
    private final RagProperties properties;

    public KnowledgeVectorIndex(VectorStore store, RagProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /**
     * 为一份业务文件完整重建索引。source 是文件记录，chunks 是该文件拆出的全部文本块。
     * 各批次顺序执行，任意调用抛异常就交回 Worker 标记失败；这里不提前发布 READY。
     * 该流程不具备跨批次事务，失败时可能留下一部分 points，重试时先清理再重建。
     */
    public void replace(KnowledgeDocument source, List<TextChunker.Chunk> chunks) {
        // 即使重试后的分块变少，也要清理上次多出的旧块；仅 upsert 相同 ID 不能清掉它们。
        delete(source);
        // Spring AI Document 表示一个可检索文本块；不要与 MySQL 的 KnowledgeDocument 文件记录混淆。
        List<Document> documents = chunks.stream().map(chunk -> Document.builder()
                // ID 由 documentId:chunkIndex 确定，相同任务重做仍得到相同 ID，配合 upsert 避免重复。
                // 它不是内容摘要；重新上传同一文件会生成新的 documentId，因此不等于文件去重。
                .id(UUID.nameUUIDFromBytes((source.id() + ":" + chunk.index())
                        .getBytes(StandardCharsets.UTF_8)).toString())
                // 供模型向量化的块原文，也由 Spring AI 保存到 Qdrant 的 doc_content 字段供引用。
                .text(chunk.text())
                // 租户、知识库和文档 ID 用于检索隔离；文件名和块位置用于来源展示。
                // metadata-mode=NONE 表示这些字段不拼进 Embedding 输入，不表示不保存元数据。
                .metadata(Map.of("tenant_id", source.tenantId(), "knowledge_base_id", source.knowledgeBaseId(),
                        "document_id", source.id(), "filename", source.originalFilename(),
                        "chunk_index", chunk.index(), "start_offset", chunk.start(), "end_offset", chunk.end()))
                .build()).toList();
        // 默认一组最多 16 块，减少超大模型请求；与“每块多少字”的 chunkSize 是不同参数。
        for (int start = 0; start < documents.size(); start += properties.embeddingBatchSize()) {
            int end = Math.min(start + properties.embeddingBatchSize(), documents.size());
            // 应用只调用一次 add：框架内部先请求智普拿向量，再经 Qdrant gRPC 写 points。
            // 不要在此额外手动调用一次 Embedding，否则会重复计算、重复计费。
            // 框架还有内部批处理策略，因此业务分组不保证始终恰好对应一次 HTTP 请求。
            store.add(documents.subList(start, end));
        }
    }

    /**
     * 清理指定租户、指定文档的全部向量，不删除 MySQL 记录或磁盘原件。
     * 重建索引和删除文档共用本方法；重复清理同一组数据不应生成新的业务副作用。
     */
    public void delete(KnowledgeDocument document) {
        // 用结构化 AND 条件限定范围，不拼接任意过滤字符串，也不能只按文件名删除。
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        store.delete(b.and(b.eq("tenant_id", document.tenantId()),
                b.eq("document_id", document.id())).build());
    }

    /**
     * 供上传完成后的问答链路复用：仅在当前租户、知识库以及 MySQL 确认 READY 的文档内检索。
     * 这里仍由 VectorStore 内部调用相同 Embedding 模型向量化问题，保证与文档向量空间一致。
     */
    public List<Document> search(String question, String tenantId, String kbId, List<String> readyIds) {
        // 空集合直接短路，绝不能误退化成“无过滤的全库检索”。
        if (readyIds.isEmpty()) return List.of();
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var filter = b.and(b.eq("tenant_id", tenantId),
                b.and(b.eq("knowledge_base_id", kbId), b.in("document_id", readyIds.toArray()))).build();
        // topK 是最多召回块数，threshold 是最低相似度要求；分数不是“答案正确概率”。
        return store.similaritySearch(SearchRequest.builder().query(question)
                .topK(properties.topK()).similarityThreshold(properties.similarityThreshold())
                .filterExpression(filter).build());
    }
}
