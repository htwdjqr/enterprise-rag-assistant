package com.example.ragassistant.web;

import com.example.ragassistant.domain.CurrentUser;
import com.example.ragassistant.service.DocumentService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** 原文件下载同样需要鉴权，不能把本地目录映射成公开静态资源。 */
@Validated
@RestController
@RequestMapping("/api")
public class DocumentController {
    private final DocumentService documents;
    public DocumentController(DocumentService documents) { this.documents = documents; }

    @PostMapping(value = "/knowledge-bases/{kbId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiModels.DocumentView upload(@PathVariable String kbId, @RequestPart("file") MultipartFile file,
                                        @AuthenticationPrincipal CurrentUser user) throws IOException {
        return ApiModels.DocumentView.from(documents.upload(kbId, file, user));
    }

    @GetMapping("/knowledge-bases/{kbId}/documents")
    public List<ApiModels.DocumentView> list(@PathVariable String kbId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal CurrentUser user) {
        return documents.list(kbId, user, page, size).stream().map(ApiModels.DocumentView::from).toList();
    }

    @GetMapping("/documents/{id}")
    public ApiModels.DocumentView get(@PathVariable String id, @AuthenticationPrincipal CurrentUser user) {
        return ApiModels.DocumentView.from(documents.get(id, user));
    }

    @PostMapping("/documents/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retry(@PathVariable String id, @AuthenticationPrincipal CurrentUser user) { documents.retry(id, user); }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void delete(@PathVariable String id, @AuthenticationPrincipal CurrentUser user) { documents.delete(id, user); }

    @GetMapping("/documents/{id}/file")
    public ResponseEntity<Resource> download(@PathVariable String id, @AuthenticationPrincipal CurrentUser user) throws IOException {
        var doc = documents.get(id, user);
        var path = documents.downloadPath(doc);
        if (!Files.isRegularFile(path)) throw BusinessException.notFound();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(path))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(doc.originalFilename(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(path));
    }
}
