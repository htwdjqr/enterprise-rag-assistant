package com.example.ragassistant.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** 审计仅存访问者、知识库、来源文档和耗时，不默认记录问题或原文。 */
@Repository
public class AuditRepository {
    private final JdbcClient jdbc;
    public AuditRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public void insert(String userId, String kbId, List<String> sourceIds, String outcome, long elapsedMs) {
        jdbc.sql("""
                INSERT INTO question_audit(id, user_id, knowledge_base_id, source_document_ids, outcome, elapsed_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                """).params(UUID.randomUUID().toString(), userId, kbId, String.join(",", sourceIds), outcome, elapsedMs)
                .update();
    }
}
