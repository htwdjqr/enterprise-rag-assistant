-- MySQL 是业务事实源；Qdrant 只是可重建的检索索引。
CREATE TABLE app_user (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE knowledge_base (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_kb_owner FOREIGN KEY (owner_id) REFERENCES app_user(id)
);
CREATE INDEX idx_kb_tenant ON knowledge_base(tenant_id, owner_id);

-- 简化的权限模型：创建人可管理，成员可读取；不允许跨租户授权。
CREATE TABLE knowledge_member (
    knowledge_base_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (knowledge_base_id, user_id),
    CONSTRAINT fk_member_kb FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id),
    CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);

CREATE TABLE knowledge_document (
    id VARCHAR(36) PRIMARY KEY,
    knowledge_base_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    storage_key VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    attempts INT NOT NULL DEFAULT 0,
    error_message VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_document_kb FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id)
);
CREATE INDEX idx_document_kb_status ON knowledge_document(knowledge_base_id, status);
CREATE INDEX idx_document_queue ON knowledge_document(status, updated_at);

-- 仅记录检索审计元数据，不默认保存员工的问题和企业原文。
CREATE TABLE question_audit (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    knowledge_base_id VARCHAR(36) NOT NULL,
    source_document_ids TEXT NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    elapsed_ms BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
