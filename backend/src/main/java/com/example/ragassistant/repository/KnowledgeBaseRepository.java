package com.example.ragassistant.repository;

import com.example.ragassistant.domain.CurrentUser;
import com.example.ragassistant.domain.KnowledgeBase;
import com.example.ragassistant.web.ApiModels.Member;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** SQL 中先限制租户，再判断创建人或成员；列表与单条读取使用同一权限语义。 */
@Repository
public class KnowledgeBaseRepository {
    private final JdbcClient jdbc;
    private static final RowMapper<KnowledgeBase> MAPPER = (rs, row) -> new KnowledgeBase(
            rs.getString("id"), rs.getString("tenant_id"), rs.getString("name"),
            rs.getString("description"), rs.getString("owner_id"), rs.getTimestamp("created_at").toInstant());

    public KnowledgeBaseRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(String id, CurrentUser user, String name, String description) {
        jdbc.sql("INSERT INTO knowledge_base(id, tenant_id, name, description, owner_id) VALUES (?, ?, ?, ?, ?)")
                .params(id, user.tenantId(), name, description, user.id()).update();
    }

    public Optional<KnowledgeBase> findReadable(String id, CurrentUser user) {
        return jdbc.sql("""
                SELECT k.* FROM knowledge_base k
                WHERE k.id = :id AND k.tenant_id = :tenant
                  AND (k.owner_id = :user OR EXISTS (
                    SELECT 1 FROM knowledge_member m WHERE m.knowledge_base_id = k.id AND m.user_id = :user))
                """).param("id", id).param("tenant", user.tenantId()).param("user", user.id())
                .query(MAPPER).optional();
    }

    public List<KnowledgeBase> listReadable(CurrentUser user) {
        return jdbc.sql("""
                SELECT k.* FROM knowledge_base k WHERE k.tenant_id = :tenant
                  AND (k.owner_id = :user OR EXISTS (
                    SELECT 1 FROM knowledge_member m WHERE m.knowledge_base_id = k.id AND m.user_id = :user))
                ORDER BY k.created_at DESC LIMIT 200
                """).param("tenant", user.tenantId()).param("user", user.id()).query(MAPPER).list();
    }

    public void grant(String knowledgeBaseId, String userId) {
        jdbc.sql("INSERT IGNORE INTO knowledge_member(knowledge_base_id, user_id) VALUES (?, ?)")
                .params(knowledgeBaseId, userId).update();
    }

    /** 一并列出创建人，避免前端把创建人的固有权限误当成可撤销成员关系。 */
    public List<Member> listMembers(String knowledgeBaseId, String tenantId) {
        return jdbc.sql("""
                SELECT u.id, u.username, CASE WHEN u.id = k.owner_id THEN 'OWNER' ELSE 'READER' END AS role
                FROM app_user u JOIN knowledge_base k ON k.tenant_id = u.tenant_id
                WHERE k.id = :kb AND k.tenant_id = :tenant
                  AND (u.id = k.owner_id OR EXISTS (
                    SELECT 1 FROM knowledge_member m WHERE m.knowledge_base_id = k.id AND m.user_id = u.id))
                ORDER BY CASE WHEN u.id = k.owner_id THEN 0 ELSE 1 END, u.username
                """).param("kb", knowledgeBaseId).param("tenant", tenantId)
                .query((rs, row) -> new Member(rs.getString("id"), rs.getString("username"), rs.getString("role")))
                .list();
    }

    public void revoke(String knowledgeBaseId, String userId) {
        jdbc.sql("DELETE FROM knowledge_member WHERE knowledge_base_id = ? AND user_id = ?")
                .params(knowledgeBaseId, userId).update();
    }
}
