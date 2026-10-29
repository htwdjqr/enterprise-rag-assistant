package com.example.ragassistant.service;

import com.example.ragassistant.TestFixtures;
import com.example.ragassistant.domain.DocumentStatus;
import com.example.ragassistant.rag.DocumentTextExtractor;
import com.example.ragassistant.rag.DocumentExtractionException;
import com.example.ragassistant.rag.KnowledgeVectorIndex;
import com.example.ragassistant.rag.TextChunker;
import com.example.ragassistant.repository.DocumentRepository;
import com.example.ragassistant.storage.LocalFileStorage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentWorkerTest {
    private final DocumentRepository docs = mock(DocumentRepository.class);
    private final LocalFileStorage storage = mock(LocalFileStorage.class);
    private final DocumentTextExtractor extractor = mock(DocumentTextExtractor.class);
    private final TextChunker chunker = mock(TextChunker.class);
    private final KnowledgeVectorIndex index = mock(KnowledgeVectorIndex.class);
    private final DocumentWorker worker = new DocumentWorker(docs, storage, extractor, chunker, index);

    @Test void onlyPublishesReadyAfterSuccessfulVectorWrite() throws Exception {
        var doc = TestFixtures.document("id", DocumentStatus.PENDING);
        when(docs.claim("id", DocumentStatus.PENDING, DocumentStatus.INDEXING)).thenReturn(true);
        when(storage.resolve("id.md")).thenReturn(Path.of("id.md"));
        when(extractor.extract(any(), anyString())).thenReturn("政策文本");
        when(chunker.split("政策文本")).thenReturn(List.of(new TextChunker.Chunk(0, 0, 4, "政策文本")));
        worker.process(doc);
        var order = inOrder(index, docs);
        order.verify(index).replace(eq(doc), anyList());
        order.verify(docs).ready("id", 1);
    }

    @Test void failedEmbeddingLeavesRetryableFailure() throws Exception {
        var doc = TestFixtures.document("id", DocumentStatus.PENDING);
        when(docs.claim("id", DocumentStatus.PENDING, DocumentStatus.INDEXING)).thenReturn(true);
        when(extractor.extract(any(), anyString())).thenThrow(new IllegalStateException("bad file"));
        worker.process(doc);
        verify(docs).fail(eq("id"), eq(DocumentStatus.INDEXING), eq(DocumentStatus.FAILED), anyString());
        verify(docs, never()).ready(anyString(), anyInt());
    }

    @Test void vectorDeleteFailureRetainsOriginalFileForRetry() throws Exception {
        var doc = TestFixtures.document("id", DocumentStatus.DELETE_PENDING);
        when(docs.claim("id", DocumentStatus.DELETE_PENDING, DocumentStatus.DELETING)).thenReturn(true);
        doThrow(new IllegalStateException("qdrant unavailable")).when(index).delete(doc);
        worker.process(doc);
        verify(storage, never()).delete(anyString());
        verify(docs).fail(eq("id"), eq(DocumentStatus.DELETING), eq(DocumentStatus.DELETE_FAILED), anyString());
    }

    @Test void scannedPdfFailureKeepsActionableMessageAndNeverCallsEmbedding() throws Exception {
        var doc = TestFixtures.document("id", DocumentStatus.PENDING);
        when(docs.claim("id", DocumentStatus.PENDING, DocumentStatus.INDEXING)).thenReturn(true);
        var failure = new DocumentExtractionException(DocumentExtractionException.Reason.PDF_NO_TEXT);
        when(extractor.extract(any(), anyString())).thenThrow(failure);
        worker.process(doc);
        verify(docs).fail("id", DocumentStatus.INDEXING, DocumentStatus.FAILED, failure.getMessage());
        verifyNoInteractions(chunker, index);
    }

    @Test void vectorFailureDoesNotExposeProviderResponseOrBlameFileFormat() throws Exception {
        var doc = TestFixtures.document("id", DocumentStatus.PENDING);
        when(docs.claim("id", DocumentStatus.PENDING, DocumentStatus.INDEXING)).thenReturn(true);
        when(extractor.extract(any(), anyString())).thenReturn("政策");
        when(chunker.split("政策")).thenReturn(List.of(new TextChunker.Chunk(0, 0, 2, "政策")));
        doThrow(new IllegalStateException("private provider response")).when(index).replace(eq(doc), anyList());
        worker.process(doc);
        verify(docs).fail("id", DocumentStatus.INDEXING, DocumentStatus.FAILED,
                "向量索引失败，请检查智普 Embedding 服务及 Qdrant 连接、配额和向量维度后重试。");
        verify(docs, never()).ready(anyString(), anyInt());
    }
}
