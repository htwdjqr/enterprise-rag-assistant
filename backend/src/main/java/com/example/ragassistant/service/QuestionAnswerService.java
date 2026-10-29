package com.example.ragassistant.service;

import com.example.ragassistant.config.RagProperties;
import com.example.ragassistant.domain.CurrentUser;
import com.example.ragassistant.domain.DocumentStatus;
import com.example.ragassistant.rag.AnswerGenerator;
import com.example.ragassistant.rag.KnowledgeVectorIndex;
import com.example.ragassistant.repository.AuditRepository;
import com.example.ragassistant.repository.DocumentRepository;
import com.example.ragassistant.web.ApiModels.AskResponse;
import com.example.ragassistant.web.ApiModels.Source;
import com.example.ragassistant.web.BusinessException;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * RAG 主流程：鉴权 → 获取 READY 文档 → 带过滤检索 → 控制上下文 → 生成 → 返回来源。
 * 刻意没有把这些步骤藏进 Advisor，便于学习每个步骤为何存在、在哪里调参。
 */
@Service
public class QuestionAnswerService {
    private final KnowledgeBaseService knowledgeBases;
    private final DocumentRepository documents;
    private final KnowledgeVectorIndex index;
    private final AnswerGenerator generator;
    private final AuditRepository audit;
    private final RagProperties properties;

    public QuestionAnswerService(KnowledgeBaseService knowledgeBases, DocumentRepository documents,
            KnowledgeVectorIndex index, AnswerGenerator generator, AuditRepository audit, RagProperties properties) {
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.index = index;
        this.generator = generator;
        this.audit = audit;
        this.properties = properties;
    }

    public AskResponse ask(String kbId, String question, CurrentUser user) {
        long start = System.nanoTime();
        knowledgeBases.requireRead(kbId, user);
        List<String> readyIds = documents.readyIds(kbId, user.tenantId());
        List<Document> hits;
        try {
            hits = index.search(question, user.tenantId(), kbId, readyIds);
        } catch (RuntimeException e) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "检索服务暂不可用，请检查 Embedding 与 Qdrant 服务");
        }

        // 检索期间文档可能被删除，再读取一次当前状态；这是对前置过滤的补充，而非替代。
        knowledgeBases.requireRead(kbId, user);
        var stillReady = new HashSet<>(documents.readyIds(kbId, user.tenantId()));
        StringBuilder context = new StringBuilder();
        List<Source> sources = new ArrayList<>();
        int remaining = properties.maxContextCharacters();
        for (Document hit : hits) {
            Object documentId = hit.getMetadata().get("document_id");
            if (!(documentId instanceof String id) || !stillReady.contains(id)) continue;
            if (!user.tenantId().equals(hit.getMetadata().get("tenant_id"))
                    || !kbId.equals(hit.getMetadata().get("knowledge_base_id"))) continue;
            if (hit.getText() == null || hit.getText().isBlank()) continue;
            // 再从业务库拿真实文件名，防止检索载荷过期；保留可点击的原文件地址。
            var doc = documents.find(id).orElse(null);
            if (doc == null || doc.status() != DocumentStatus.READY) continue;
            int reference = sources.size() + 1;
            String header = "[" + reference + "] 文件：" + doc.originalFilename() + "\n";
            int budget = remaining - header.length() - 2;
            if (budget <= 0) break;
            String excerpt = truncateSafely(hit.getText(), budget);
            Object chunk = hit.getMetadata().get("chunk_index");
            int chunkIndex = chunk instanceof Number n ? n.intValue() : 0;
            context.append(header).append(excerpt).append("\n\n");
            remaining -= header.length() + excerpt.length() + 2;
            sources.add(new Source(reference, id, doc.originalFilename(), chunkIndex, hit.getScore(), excerpt,
                    "/api/documents/" + id + "/file"));
        }

        if (sources.isEmpty()) {
            long elapsed = elapsed(start);
            audit.insert(user.id(), kbId, List.of(), "NO_EVIDENCE", elapsed);
            // 无资料时不调用聊天模型，避免模型用常识补出不存在的企业制度。
            return new AskResponse("NO_EVIDENCE", "当前知识库没有检索到足够的相关资料，无法确认。请检查文档是否已就绪，或换一种问法。", List.of(), elapsed);
        }

        String answer;
        try {
            answer = generator.answer(question, context.toString());
        } catch (RuntimeException e) {
            audit.insert(user.id(), kbId, List.of(), "MODEL_ERROR", elapsed(start));
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "聊天模型暂不可用，请稍后重试");
        }
        // 生成可能耗时较长：返回前再检查撤权和文档删除，避免继续展示已失效的内容。
        knowledgeBases.requireRead(kbId, user);
        var finalReady = new HashSet<>(documents.readyIds(kbId, user.tenantId()));
        if (sources.stream().anyMatch(s -> !finalReady.contains(s.documentId()))) {
            throw new BusinessException(HttpStatus.CONFLICT, "回答生成期间资料发生变化，请重新提问");
        }
        long elapsed = elapsed(start);
        audit.insert(user.id(), kbId, sources.stream().map(Source::documentId).distinct().toList(), "ANSWERED", elapsed);
        return new AskResponse("ANSWERED", answer, List.copyOf(sources), elapsed);
    }

    private long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }

    private String truncateSafely(String value, int limit) {
        int end = Math.min(value.length(), limit);
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
}
