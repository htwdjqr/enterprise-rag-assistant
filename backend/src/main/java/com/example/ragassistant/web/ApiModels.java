package com.example.ragassistant.web;

import com.example.ragassistant.domain.DocumentStatus;
import com.example.ragassistant.domain.KnowledgeDocument;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** 请求与响应 DTO 集中展示，便于初学者对照 HTTP 接口理解数据流。 */
public final class ApiModels {
    private ApiModels() { }

    public record CreateKnowledgeBase(@NotBlank @Size(max = 100) String name,
                                     @Size(max = 500) String description) { }
    public record GrantMember(@NotBlank @Size(max = 64) String username) { }
    public record AskRequest(@NotBlank @Size(max = 2000) String question) { }
    public record Me(String id, String username, String tenantId) { }
    /** 成员列表只暴露必要的身份字段，不返回密码散列或其他租户的信息。 */
    public record Member(String id, String username, String role) { }

    public record DocumentView(String id, String knowledgeBaseId, String filename, long sizeBytes,
            String sha256, DocumentStatus status, int chunkCount, int attempts, String errorMessage,
            Instant createdAt, Instant updatedAt) {
        public static DocumentView from(KnowledgeDocument d) {
            return new DocumentView(d.id(), d.knowledgeBaseId(), d.originalFilename(), d.sizeBytes(), d.sha256(),
                    d.status(), d.chunkCount(), d.attempts(), d.errorMessage(), d.createdAt(), d.updatedAt());
        }
    }

    /** sources 是本次交给模型的检索证据，不代表模型每一句话都已由程序核验。 */
    public record AskResponse(String status, String answer, List<Source> sources, long elapsedMs) { }
    public record Source(int reference, String documentId, String filename, int chunkIndex,
                         Double score, String excerpt, String downloadUrl) { }
}
