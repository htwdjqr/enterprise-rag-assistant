package com.example.ragassistant.repository;

import com.example.ragassistant.domain.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** DAO 只负责 SQL，不在这里决定业务权限。参数绑定可避免 SQL 字符串拼接注入。 */
@Repository
public class UserRepository {
    private final JdbcClient jdbc;
    public UserRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<CurrentUser> findByUsername(String username) {
        return jdbc.sql("SELECT * FROM app_user WHERE username = :username")
                .param("username", username)
                .query((rs, row) -> new CurrentUser(rs.getString("id"), rs.getString("tenant_id"),
                        rs.getString("username"), rs.getString("password_hash"), rs.getBoolean("enabled")))
                .optional();
    }

    public void insert(String tenantId, String username, String passwordHash) {
        jdbc.sql("INSERT INTO app_user(id, tenant_id, username, password_hash) VALUES (?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), tenantId, username, passwordHash).update();
    }
}
