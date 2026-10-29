package com.example.ragassistant.repository;

import com.example.ragassistant.domain.DocumentStatus;
import com.example.ragassistant.domain.KnowledgeDocument;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 文档表同时充当单实例的持久化任务队列，避免仅用 @Async 导致重启后任务丢失。
 * 状态更新带旧状态条件，相当于 compare-and-set，防止索引完成覆盖用户的删除请求。
 */
@Repository
public class DocumentRepository {
    private final JdbcClient jdbc;
    // 每行 SQL 结果都映射成不可变的业务记录；status 从数据库字符串还原为枚举。
    // 该对象是查询时刻的快照，其他请求更新数据库后，已有对象不会自动跟着变化。
    private static final RowMapper<KnowledgeDocument> MAPPER = (rs, row) -> new KnowledgeDocument(
            rs.getString("id"), rs.getString("knowledge_base_id"), rs.getString("tenant_id"),
            rs.getString("original_filename"), rs.getString("storage_key"), rs.getLong("size_bytes"),
            rs.getString("sha256"), DocumentStatus.valueOf(rs.getString("status")), rs.getInt("chunk_count"),
            rs.getInt("attempts"), rs.getString("error_message"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    public DocumentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * 上传入口登记 PENDING：同一条记录既描述原文件，也作为后台任务的持久化凭据。
     * 这里只存文件元信息，不把全文或向量写进 MySQL。占位符与 params 绑定参数，避免拼接 SQL。
     */
    public void insert(String id, String kbId, String tenant, String filename, String key, long size, String hash) {
        jdbc.sql("""
                INSERT INTO knowledge_document
                (id, knowledge_base_id, tenant_id, original_filename, storage_key, size_bytes, sha256, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """).params(id, kbId, tenant, filename, key, size, hash).update();
    }

    /** 按 ID 读取快照；权限由调用方 Service 校验，本方法本身不判断当前用户。 */
    public Optional<KnowledgeDocument> find(String id) {
        return jdbc.sql("SELECT * FROM knowledge_document WHERE id = ?").param(id).query(MAPPER).optional();
    }

    /** 分页展示尚未彻底删除的文档，包含处理中的状态及失败提示，便于前端轮询。 */
    public List<KnowledgeDocument> list(String kbId, int offset, int limit) {
        return jdbc.sql("""
                SELECT * FROM knowledge_document WHERE knowledge_base_id = ? AND status <> 'DELETED'
                ORDER BY created_at DESC, id LIMIT ? OFFSET ?
                """).params(kbId, limit, offset).query(MAPPER).list();
    }

    /**
     * 问答检索前读取已发布文档 ID，作为 Qdrant 过滤条件。
     * 即使失败任务已写入部分向量，只要 MySQL 不是 READY，就不能进入正常问答检索。
     */
    public List<String> readyIds(String kbId, String tenantId) {
        return jdbc.sql("""
                SELECT id FROM knowledge_document
                WHERE knowledge_base_id = ? AND tenant_id = ? AND status = 'READY'
                """).params(kbId, tenantId).query(String.class).list();
    }

    /**
     * 查询一份候选任务，不等于已经领取；Worker 随后必须通过 claim 的条件 UPDATE 确认。
     * 删除优先，同类任务按更新时间和 ID 排序，LIMIT 1 保证每轮最多处理一份。
     * FAILED / DELETE_FAILED 不在候选集合中，必须由用户显式重试，避免无限重复调用。
     */
    public Optional<KnowledgeDocument> nextTask() {
        return jdbc.sql("""
                SELECT * FROM knowledge_document WHERE status IN ('PENDING', 'DELETE_PENDING')
                ORDER BY CASE WHEN status = 'DELETE_PENDING' THEN 0 ELSE 1 END, updated_at, id LIMIT 1
                """).query(MAPPER).optional();
    }

    /**
     * 条件更新相当于“只有状态还是我刚才看到的值，才切换为处理中”。
     * update 返回受影响行数：1 表示领取成功；0 表示状态变化或记录不存在，Worker 应放弃。
     * attempts 在真正领取时加 1，并清除上一次失败提示；点击重试本身不会增加次数。
     */
    public boolean claim(String id, DocumentStatus expected, DocumentStatus processing) {
        return jdbc.sql("""
                UPDATE knowledge_document SET status = ?, attempts = attempts + 1,
                error_message = NULL, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND status = ?
                """).params(processing.name(), id, expected.name()).update() == 1;
    }

    /**
     * 全部向量批次调用成功后发布 READY，并登记块数。
     * WHERE status='INDEXING' 防止覆盖处理中发生的删除请求；更新 0 行时不会恢复检索资格。
     */
    public void ready(String id, int chunks) {
        jdbc.sql("""
                UPDATE knowledge_document SET status = 'READY', chunk_count = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND status = 'INDEXING'
                """).params(chunks, id).update();
    }

    /** 删除向量和原文件成功后标为 DELETED，保留业务记录，列表查询会隐藏它。 */
    public void finishDelete(String id) {
        jdbc.sql("""
                UPDATE knowledge_document SET status = 'DELETED', chunk_count = 0, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND status = 'DELETING'
                """).param(id).update();
    }

    /**
     * 保存索引或删除的失败结果。safeMessage 必须是适合给页面展示的固定提示。
     * 同样匹配旧状态：任务异常时若用户已申请删除，不将 DELETE_PENDING 改回索引失败。
     */
    public void fail(String id, DocumentStatus expected, DocumentStatus failed, String safeMessage) {
        jdbc.sql("""
                UPDATE knowledge_document SET status = ?, error_message = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND status = ?
                """).params(failed.name(), safeMessage, id, expected.name()).update();
    }

    /**
     * 重试只重新排队，不在 HTTP 请求中直接处理文件。
     * 索引失败回到 PENDING，删除失败回到 DELETE_PENDING；其他状态不允许重试，返回 false。
     */
    public boolean retry(String id) {
        return jdbc.sql("""
                UPDATE knowledge_document
                SET status = CASE WHEN status = 'FAILED' THEN 'PENDING' ELSE 'DELETE_PENDING' END,
                    error_message = NULL, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND status IN ('FAILED', 'DELETE_FAILED')
                """).param(id).update() == 1;
    }

    /**
     * 先标记删除意图，再由 Worker 清理物理数据。状态一旦离开 READY，就被问答过滤排除。
     * 正在 INDEXING 的文档也可申请删除，Worker 后续 ready/fail 的旧状态条件会保护该意图。
     */
    public void requestDelete(String id) {
        jdbc.sql("""
                UPDATE knowledge_document SET status = 'DELETE_PENDING', error_message = NULL,
                updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND status IN ('PENDING', 'INDEXING', 'READY', 'FAILED', 'DELETE_FAILED')
                """).param(id).update();
    }

    /**
     * 仅在当前单实例应用启动时调用：上次进程已经结束，处理中状态代表未完成任务。
     * 重新排队后会从头处理，不保留批次进度；确定性块 ID 和清理旧向量使重做可恢复。
     * FAILED / READY 不受影响。多实例不能直接使用此恢复方式，否则会重置其他实例的活跃任务。
     */
    public void recoverInterruptedTasks() {
        jdbc.sql("""
                UPDATE knowledge_document
                SET status = CASE WHEN status = 'INDEXING' THEN 'PENDING' ELSE 'DELETE_PENDING' END,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE status IN ('INDEXING', 'DELETING')
                """).update();
    }
}
