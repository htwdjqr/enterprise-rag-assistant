package com.example.ragassistant.storage;

import com.example.ragassistant.config.RagProperties;
import com.example.ragassistant.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * 本地文件存储适配器。以后切换 MinIO / S3 时可把这些方法抽成接口。
 * 磁盘文件名使用服务端生成的 UUID，原始文件名仅作展示，防止目录穿越与同名覆盖。
 */
@Component
public class LocalFileStorage {
    private static final Set<String> ALLOWED = Set.of("pdf", "doc", "docx", "txt", "md");
    private final Path root;

    public LocalFileStorage(RagProperties properties) throws IOException {
        // 相对路径以进程工作目录为基准：本机从 backend/ 启动时，默认解析为 backend/data/files/。
        root = properties.storageRoot().toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    /**
     * 上传请求阶段调用：先保存原件并计算摘要，再把结果交给 Service 登记 MySQL。
     * 此处不判断文档是否能解析、不调用模型；字节大小上限由 Spring multipart 配置约束。
     */
    public StoredFile save(String documentId, MultipartFile file) throws IOException {
        // 浏览器可能提交带路径的名字，统一分隔符后只保留最后一段，并移除控制字符。
        String supplied = file.getOriginalFilename();
        String name = supplied == null ? "" : supplied.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (file.isEmpty() || name.length() > 255 || !ALLOWED.contains(extension)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请上传非空的 PDF、DOC、DOCX、TXT 或 Markdown 文件，文件名不超过 255 字符");
        }
        // 磁盘名使用服务端 UUID，原始名只供展示，避免不同员工的同名文件互相覆盖。
        String key = documentId + "." + extension;
        Path path = resolve(key);
        MessageDigest digest = sha256();
        // DigestInputStream 在 Files.copy 读取字节的同时更新摘要，不必为计算 SHA-256 再读一遍文件。
        // try-with-resources 在成功和异常路径都关闭输入流。
        try (InputStream input = new DigestInputStream(file.getInputStream(), digest)) {
            Files.copy(input, path);
        } catch (IOException e) {
            // 写盘失败可能留下半个文件，先尝试删除；异常继续上抛，不能登记成上传成功。
            Files.deleteIfExists(path);
            throw e;
        }
        // SHA-256 用于记录文件摘要，本项目没有据此拒绝重复上传，也不以摘要替代权限校验。
        return new StoredFile(key, name, Files.size(path), HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * Worker 根据数据库中的 storageKey 找到原件，下载和删除也复用此方法。
     * normalize 消除路径中的 . 和 ..，再要求结果直接位于 root 内，拒绝路径穿越与嵌套目录。
     * 这是路径结构校验，不代替租户或知识库权限校验；存储目录也不应允许不可信方创建符号链接。
     */
    public Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || !path.getParent().equals(root)) {
            throw new IllegalArgumentException("非法存储路径");
        }
        return path;
    }

    /** 文件不存在也视为已完成删除，支持 Worker 重试；磁盘权限等真正的 I/O 错误仍向上抛出。 */
    public void delete(String key) throws IOException { Files.deleteIfExists(resolve(key)); }

    private MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** 上传落盘结果；key 是内部存储名，originalFilename 是展示名，二者不能混用。 */
    public record StoredFile(String key, String originalFilename, long size, String sha256) { }
}
