package com.example.ragassistant.service;

import com.example.ragassistant.domain.DocumentStatus;
import com.example.ragassistant.domain.KnowledgeDocument;
import com.example.ragassistant.rag.DocumentTextExtractor;
import com.example.ragassistant.rag.DocumentExtractionException;
import com.example.ragassistant.rag.KnowledgeVectorIndex;
import com.example.ragassistant.rag.TextChunker;
import com.example.ragassistant.repository.DocumentRepository;
import com.example.ragassistant.storage.LocalFileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文档后台处理入口，运行在 Spring 定时任务线程，不在上传接口的 HTTP 请求线程中。
 * 上传接口把文件保存到磁盘、把 PENDING 记录写入 MySQL 后即可返回，本文档任务随后独立运行。
 *
 * <p>索引链路：poll → nextTask → claim → extract → split → replace → ready。
 * 删除链路复用同一入口：poll → nextTask → claim → 删除向量 → 删除文件 → finishDelete。
 * 一个任务完整执行后才处理下一个任务，当前实例中不会同时执行索引和物理删除。
 *
 * <p>MySQL、文件系统、Qdrant 没有共同事务，不能靠一个 {@code @Transactional} 撤销全部操作。
 * 索引任务失败后保留原文件和失败状态，靠人工重试、旧向量清理及确定性块 ID 恢复。
 * 当前只支持单应用实例；启动恢复机制不能直接用于多个实例共同消费任务。
 */
@Component
// 只有配置为 true 或没有显式配置时才创建该 Bean；WORKER_ENABLED=false 可关闭后台处理。
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentWorker {
    private static final Logger log = LoggerFactory.getLogger(DocumentWorker.class);
    private final DocumentRepository documents;
    private final LocalFileStorage storage;
    private final DocumentTextExtractor extractor;
    private final TextChunker chunker;
    private final KnowledgeVectorIndex index;
    // 启动事件线程写入，定时任务线程读取；volatile 保证其他线程能看见恢复完成。
    // 它只是启动门闩，不是任务锁，也不能替代数据库的状态校验。
    private volatile boolean ready;

    public DocumentWorker(DocumentRepository documents, LocalFileStorage storage, DocumentTextExtractor extractor,
                          TextChunker chunker, KnowledgeVectorIndex index) {
        this.documents = documents;
        this.storage = storage;
        this.extractor = extractor;
        this.chunker = chunker;
        this.index = index;
    }

    /**
     * 应用启动完成后执行一次，先恢复上次进程退出时未完成的任务，再允许轮询领取。
     * 恢复失败时不会把 ready 设为 true，避免任务状态未整理好就继续处理。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        documents.recoverInterruptedTasks();
        ready = true;
    }

    /**
     * Spring 自动调用：本轮执行结束后，默认等待 3000 毫秒再执行下一轮。
     * fixedDelay 不是“每 3 秒启动一个并行任务”；单个文件越慢，下一份文件等待越久。
     * 每轮最多处理一份文档。失败状态不参与自动领取，防止反复调用模型产生费用。
     */
    @Scheduled(fixedDelayString = "${app.worker.poll-delay-ms:3000}")
    public void poll() {
        if (!ready) return;
        // nextTask 返回 Optional：有记录才调用 process；为空时本轮直接结束，不表示发生异常。
        documents.nextTask().ifPresent(this::process);
    }

    /**
     * 处理 nextTask 查到的一份任务快照。必须先 claim 成功，才允许执行磁盘或远程服务操作。
     * 方法保持包级可见，既供本类调用，也方便同包测试验证执行顺序和失败补偿。
     */
    void process(KnowledgeDocument doc) {
        // nextTask 只返回 PENDING / DELETE_PENDING，因此在这里分成索引和删除两条路径。
        boolean deleting = doc.status() == DocumentStatus.DELETE_PENDING;
        DocumentStatus processing = deleting ? DocumentStatus.DELETING : DocumentStatus.INDEXING;
        // SELECT 与 UPDATE 之间，用户可能请求删除。WHERE status=旧状态防止处理过期快照。
        // 返回 false 表示状态已变化，本轮放弃即可，不能继续解析或写向量。
        if (!documents.claim(doc.id(), doc.status(), processing)) return;
        // phase 只用于本次执行的日志与安全提示；MySQL 中持久化的状态仍是 processing。
        String phase = deleting ? "DELETE_VECTORS" : "EXTRACT_TEXT";
        try {
            if (deleting) {
                // 先清理检索索引，确认调用成功后才删除原文件；向量清理失败时保留原件供重试。
                index.delete(doc);
                phase = "DELETE_FILE";
                storage.delete(doc.storageKey());
                // 两处物理数据均清理后才标记 DELETED；重复删除不存在的文件也能正常完成。
                documents.finishDelete(doc.id());
            } else {
                // 1. storageKey 是服务器保存的 UUID 文件名，不能把客户端原始文件名直接当路径。
                // originalFilename 只帮助提取器判断格式；extract 返回清洗后的全文，不是向量。
                String text = extractor.extract(storage.resolve(doc.storageKey()), doc.originalFilename());
                // 2. 全文分成带重叠的文本块。Chunk 同时记录文字、块序号和文本中的位置。
                var chunks = chunker.split(text);
                phase = "WRITE_VECTORS";
                // 3. replace 清理旧索引，再分批调用 Spring AI：智普生成向量，Qdrant 保存索引。
                // 方法内各批次调用成功返回后，才继续下一步；上传索引不调用 DeepSeek。
                index.replace(doc, chunks);
                // 4. READY 是对问答开放的发布标记，不能在向量尚未写完时提前设置。
                // 若用户中途请求删除，SQL 的旧状态条件确保不会重新变回 READY。
                phase = "PUBLISH_READY";
                documents.ready(doc.id(), chunks.size());
            }
        } catch (Exception e) {
            // 后台异常不能再返回给已经结束的上传请求，改为记录日志与 MySQL 失败状态。
            // 不输出解析器或模型原始异常正文，其中可能含内部路径、原文或服务信息。
            log.warn("Document task failed: id={}, stage={}, phase={}, exception={}", doc.id(), processing, phase, e.getClass().getSimpleName());
            // 只有我们定义的提取异常可以直接展示；其他异常按实际阶段生成固定提示。
            String safeMessage = e instanceof DocumentExtractionException ? e.getMessage() : switch (phase) {
                case "EXTRACT_TEXT" -> "文字提取失败，请检查文件是否损坏、加密或超过文本上限；扫描件需要先进行 OCR 识别。";
                case "WRITE_VECTORS" -> "向量索引失败，请检查智普 Embedding 服务及 Qdrant 连接、配额和向量维度后重试。";
                case "PUBLISH_READY" -> "更新文档状态失败，请检查 MySQL 后重试。";
                default -> "删除未完成，请检查 Qdrant 与文件存储后重试";
            };
            // 仍需匹配旧状态，避免把用户刚提交的 DELETE_PENDING 覆盖成 FAILED。
            // 不在这里循环重试：保留失败记录，由重试接口改回待处理状态，再由 poll 领取。
            // 若 MySQL 已不可用，此处记录失败也可能抛异常；重启后的 recover 会恢复中断状态。
            documents.fail(doc.id(), processing, deleting ? DocumentStatus.DELETE_FAILED : DocumentStatus.FAILED,
                    safeMessage);
        }
    }
}
