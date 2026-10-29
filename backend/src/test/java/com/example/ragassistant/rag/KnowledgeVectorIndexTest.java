package com.example.ragassistant.rag;

import com.example.ragassistant.TestFixtures;
import com.example.ragassistant.domain.DocumentStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeVectorIndexTest {
    private final VectorStore store = mock(VectorStore.class);
    private final KnowledgeVectorIndex index = new KnowledgeVectorIndex(store, TestFixtures.properties(Path.of(".")));

    @Test void emptyReadyListNeverBecomesUnrestrictedSearch() {
        assertThat(index.search("问题", "tenant-a", "kb-a", List.of())).isEmpty();
        verifyNoInteractions(store);
    }

    @Test void retrievalIncludesTenantKnowledgeBaseAndReadyDocumentFilter() {
        index.search("问题", "tenant-a", "kb-a", List.of("doc-a"));
        var request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(request.capture());
        String filter = request.getValue().getFilterExpression().toString();
        assertThat(filter).contains("tenant_id", "tenant-a", "knowledge_base_id", "kb-a", "document_id", "doc-a");
        assertThat(request.getValue().getTopK()).isEqualTo(5);
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void retryUsesSamePointIdAndCleansOldChunksFirst() {
        var doc = TestFixtures.document("doc-a", DocumentStatus.INDEXING);
        var chunks = List.of(new TextChunker.Chunk(0, 0, 2, "原文"));
        index.replace(doc, chunks);
        index.replace(doc, chunks);
        ArgumentCaptor<List<Document>> added = ArgumentCaptor.forClass((Class) List.class);
        verify(store, times(2)).add(added.capture());
        String firstId = added.getAllValues().getFirst().getFirst().getId();
        assertThat(UUID.fromString(firstId)).isNotNull();
        assertThat(added.getAllValues().getLast().getFirst().getId()).isEqualTo(firstId);
        var order = inOrder(store);
        order.verify(store).delete(any(Filter.Expression.class));
        order.verify(store).add(anyList());
    }
}
