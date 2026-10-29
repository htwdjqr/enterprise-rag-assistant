package com.example.ragassistant.service;

import com.example.ragassistant.domain.CurrentUser;
import com.example.ragassistant.domain.KnowledgeDocument;
import com.example.ragassistant.repository.DocumentRepository;
import com.example.ragassistant.storage.LocalFileStorage;
import com.example.ragassistant.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** 上传只负责保存与登记，耗时的解析、Embedding 由后台任务处理。 */
@Service
public class DocumentService {
    private final KnowledgeBaseService knowledgeBases;
    private final DocumentRepository documents;
    private final LocalFileStorage storage;

    public DocumentService(KnowledgeBaseService knowledgeBases, DocumentRepository documents, LocalFileStorage storage) {
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.storage = storage;
    }

    public KnowledgeDocument upload(String kbId, MultipartFile file, CurrentUser user) throws IOException {
        knowledgeBases.requireManage(kbId, user);
        String id = UUID.randomUUID().toString();
        LocalFileStorage.StoredFile stored = storage.save(id, file);
        try {
            // 不把远程模型调用放进数据库事务；这里仅用一条 INSERT 原子登记任务。
            documents.insert(id, kbId, user.tenantId(), stored.originalFilename(), stored.key(), stored.size(), stored.sha256());
        } catch (RuntimeException e) {
            // 文件系统与 MySQL 没有共同事务：登记失败时补偿删除刚保存的文件。
            try { storage.delete(stored.key()); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
        return documents.find(id).orElseThrow();
    }

    public List<KnowledgeDocument> list(String kbId, CurrentUser user, int page, int size) {
        knowledgeBases.requireRead(kbId, user);
        return documents.list(kbId, page * size, size);
    }

    public KnowledgeDocument get(String id, CurrentUser user) {
        KnowledgeDocument doc = documents.find(id).orElseThrow(BusinessException::notFound);
        knowledgeBases.requireRead(doc.knowledgeBaseId(), user);
        return doc;
    }

    public void retry(String id, CurrentUser user) {
        KnowledgeDocument doc = get(id, user);
        knowledgeBases.requireManage(doc.knowledgeBaseId(), user);
        if (!documents.retry(id)) {
            throw new BusinessException(HttpStatus.CONFLICT, "只有索引失败或删除失败的文档可以重试");
        }
    }

    public void delete(String id, CurrentUser user) {
        KnowledgeDocument doc = get(id, user);
        knowledgeBases.requireManage(doc.knowledgeBaseId(), user);
        documents.requestDelete(id);
    }

    public Path downloadPath(KnowledgeDocument doc) {
        if (doc.status().name().startsWith("DELETE") || doc.status().name().equals("DELETING")) {
            throw BusinessException.notFound();
        }
        return storage.resolve(doc.storageKey());
    }
}
