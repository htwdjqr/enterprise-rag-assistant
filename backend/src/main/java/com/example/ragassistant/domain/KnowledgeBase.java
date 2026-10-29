package com.example.ragassistant.domain;

import java.time.Instant;

/** 知识库是本示例的授权边界：一个知识库里的所有文档共享成员权限。 */
public record KnowledgeBase(String id, String tenantId, String name, String description,
                            String ownerId, Instant createdAt) { }
