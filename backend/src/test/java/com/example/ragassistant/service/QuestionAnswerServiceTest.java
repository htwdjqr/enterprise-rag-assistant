package com.example.ragassistant.service;

import com.example.ragassistant.TestFixtures;
import com.example.ragassistant.domain.DocumentStatus;
import com.example.ragassistant.rag.AnswerGenerator;
import com.example.ragassistant.rag.KnowledgeVectorIndex;
import com.example.ragassistant.repository.AuditRepository;
import com.example.ragassistant.repository.DocumentRepository;
import com.example.ragassistant.web.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.example.ragassistant.TestFixtures.ALICE;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuestionAnswerServiceTest {
    private final KnowledgeBaseService kb = mock(KnowledgeBaseService.class);
    private final DocumentRepository docs = mock(DocumentRepository.class);
    private final KnowledgeVectorIndex index = mock(KnowledgeVectorIndex.class);
    private final AnswerGenerator generator = mock(AnswerGenerator.class);
    private final AuditRepository audit = mock(AuditRepository.class);
    private final QuestionAnswerService service = new QuestionAnswerService(kb, docs, index, generator,
            audit, TestFixtures.properties(Path.of(".")));

    @BeforeEach void setup() {
        when(docs.readyIds("kb-a", "tenant-a")).thenReturn(List.of("doc-a"));
        when(docs.find("doc-a")).thenReturn(Optional.of(TestFixtures.document("doc-a", DocumentStatus.READY)));
    }

    @Test void deniedUserNeverReachesVectorOrModelServices() {
        when(kb.requireRead("kb-a", ALICE)).thenThrow(BusinessException.notFound());
        assertThatThrownBy(() -> service.ask("kb-a", "住宿额度？", ALICE)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(index, generator);
    }

    @Test void noEvidenceDoesNotCallChatModel() {
        when(index.search(anyString(), anyString(), anyString(), anyList())).thenReturn(List.of());
        assertThat(service.ask("kb-a", "住宿额度？", ALICE).status()).isEqualTo("NO_EVIDENCE");
        verifyNoInteractions(generator);
    }

    @Test void sourcesComeFromAuthorizedReadyDocuments() {
        when(index.search(anyString(), anyString(), anyString(), anyList())).thenReturn(List.of(hit("tenant-a", "doc-a")));
        when(generator.answer(eq("住宿额度？"), contains("[1]"))).thenReturn("住宿上限 500 元。[1]");
        var result = service.ask("kb-a", "住宿额度？", ALICE);
        assertThat(result.answer()).contains("[1]");
        assertThat(result.sources()).hasSize(1);
        assertThat(result.sources().getFirst().downloadUrl()).isEqualTo("/api/documents/doc-a/file");
        verify(index).search("住宿额度？", "tenant-a", "kb-a", List.of("doc-a"));
    }

    @Test void unexpectedCrossTenantHitIsNotSentToModel() {
        when(index.search(anyString(), anyString(), anyString(), anyList())).thenReturn(List.of(hit("tenant-b", "doc-a")));
        assertThat(service.ask("kb-a", "住宿额度？", ALICE).sources()).isEmpty();
        verifyNoInteractions(generator);
    }

    @Test void deletingDocumentWhileGeneratingPreventsReturningStaleAnswer() {
        when(index.search(anyString(), anyString(), anyString(), anyList())).thenReturn(List.of(hit("tenant-a", "doc-a")));
        when(generator.answer(anyString(), anyString())).thenReturn("500 元。[1]");
        when(docs.readyIds("kb-a", "tenant-a")).thenReturn(List.of("doc-a"), List.of("doc-a"), List.of());
        assertThatThrownBy(() -> service.ask("kb-a", "住宿额度？", ALICE))
                .isInstanceOf(BusinessException.class).hasMessageContaining("资料发生变化");
    }

    @Test void modelFailureProducesServiceUnavailable() {
        when(index.search(anyString(), anyString(), anyString(), anyList())).thenReturn(List.of(hit("tenant-a", "doc-a")));
        when(generator.answer(anyString(), anyString())).thenThrow(new IllegalStateException("internal detail"));
        assertThatThrownBy(() -> service.ask("kb-a", "住宿额度？", ALICE))
                .isInstanceOf(BusinessException.class).hasMessage("聊天模型暂不可用，请稍后重试");
    }

    private Document hit(String tenant, String id) {
        return Document.builder().id("point-1").text("住宿上限 500 元。")
                .metadata(Map.of("tenant_id", tenant, "knowledge_base_id", "kb-a", "document_id", id, "chunk_index", 0))
                .score(.92).build();
    }
}
