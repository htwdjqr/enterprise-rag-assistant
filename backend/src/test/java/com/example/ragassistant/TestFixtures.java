package com.example.ragassistant;

import com.example.ragassistant.config.RagProperties;
import com.example.ragassistant.domain.*;

import java.nio.file.Path;
import java.time.Instant;

/** 测试用纯虚构身份和文档；与运行环境的初始化账号无关。 */
public final class TestFixtures {
    private TestFixtures() { }
    public static final CurrentUser ALICE = new CurrentUser("user-a", "tenant-a", "alice", "unused", true);
    public static RagProperties properties(Path root) {
        return new RagProperties(root, 100, 20, 5, 0.45, 10000, 2000, 4);
    }
    public static KnowledgeDocument document(String id, DocumentStatus status) {
        return new KnowledgeDocument(id, "kb-a", "tenant-a", "policy.md", id + ".md", 100,
                "hash", status, 1, 0, null, Instant.now(), Instant.now());
    }
}
