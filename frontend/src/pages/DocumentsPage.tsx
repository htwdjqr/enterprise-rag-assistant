import { useRef, useState, type FormEvent } from "react";
import {
  ArrowDownToLine,
  ChevronLeft,
  ChevronRight,
  FileText,
  FolderOpen,
  RefreshCw,
  Trash2,
  UploadCloud,
  X,
} from "lucide-react";
import type { Api, KnowledgeBase, KnowledgeDocument } from "../types";
import { dateLabel, fileSize, messageOf, saveBlob, useQuery } from "../hooks";
import {
  EmptyState,
  ErrorNotice,
  Modal,
  Spinner,
  StatusBadge,
} from "../components/ui";

export function DocumentsPage({ api, kb }: { api: Api; kb: KnowledgeBase }) {
  const [page, setPage] = useState(0);
  return (
    <DocumentList
      key={`${kb.id}-${page}`}
      api={api}
      kb={kb}
      page={page}
      setPage={setPage}
    />
  );
}
function DocumentList({
  api,
  kb,
  page,
  setPage,
}: {
  api: Api;
  kb: KnowledgeBase;
  page: number;
  setPage: (p: number) => void;
}) {
  const query = useQuery(() => api.listDocuments(kb.id, page, 20), 3000);
  const [uploading, setUploading] = useState(false);
  const [deleting, setDeleting] = useState<KnowledgeDocument>();
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const owner = kb.ownerId === api.user.id;
  const documents = query.data || [];
  async function action(
    id: string,
    operation: () => Promise<void>,
    success: string,
  ) {
    setBusy(id);
    setError("");
    setNotice("");
    try {
      await operation();
      setNotice(success);
      setDeleting(undefined);
      query.refresh();
    } catch (e) {
      setError(messageOf(e));
    } finally {
      setBusy("");
    }
  }
  const pending = documents.filter((d) =>
    ["PENDING", "INDEXING", "DELETE_PENDING", "DELETING"].includes(d.status),
  ).length;
  return (
    <section className="page-content">
      <div className="page-heading">
        <div>
          <div className="eyebrow">KNOWLEDGE LIBRARY</div>
          <h2>把资料变成可用的知识</h2>
          <p>上传原文件，处理完成后即可用于问答。状态每 3 秒更新。</p>
        </div>
        {owner && (
          <button className="button primary" onClick={() => setUploading(true)}>
            <UploadCloud size={18} />
            上传文档
          </button>
        )}
      </div>
      <div className="document-overview">
        <div>
          <span className="stat-icon">
            <FolderOpen size={20} />
          </span>
          <strong>{documents.length}</strong>
          <span>本页文档</span>
        </div>
        <div>
          <span className="green-dot" />
          <strong>
            {documents.filter((d) => d.status === "READY").length}
          </strong>
          <span>可用于问答</span>
        </div>
        <div>
          <span className="amber-dot" />
          <strong>{pending}</strong>
          <span>正在处理</span>
        </div>
        <div className="overview-note">
          {owner
            ? "支持 PDF、Word、TXT、Markdown"
            : "读取权限 · 可查看和下载文档"}
        </div>
      </div>
      <ErrorNotice
        message={error || query.error}
        retry={query.error ? query.refresh : undefined}
      />
      {notice && (
        <div className="notice success" role="status">
          {notice}
          <button
            aria-label="关闭提示"
            className="icon-button"
            onClick={() => setNotice("")}
          >
            <X size={16} />
          </button>
        </div>
      )}
      <div className="data-panel">
        <div className="data-toolbar">
          <h3>
            文档列表 <span>{kb.name}</span>
          </h3>
          <button
            className="text-button"
            disabled={query.loading}
            onClick={query.refresh}
          >
            <RefreshCw size={15} className={query.loading ? "spin" : ""} />
            刷新
          </button>
        </div>
        {query.loading && !query.data ? (
          <div className="loading-state">
            <Spinner /> 正在加载文档…
          </div>
        ) : !documents.length ? (
          <EmptyState
            title={page ? "本页没有文档" : "知识从第一份资料开始"}
            action={
              owner && !page ? (
                <button
                  className="button secondary"
                  onClick={() => setUploading(true)}
                >
                  上传第一份文档
                </button>
              ) : undefined
            }
          >
            {page
              ? "可以返回上一页查看。"
              : "上传员工手册、操作指南或产品说明，让助手有据可答。"}
          </EmptyState>
        ) : (
          <div className="table-wrap">
            <table className="document-table">
              <thead>
                <tr>
                  <th>文档名称</th>
                  <th>处理状态</th>
                  <th className="date-column">上传时间</th>
                  <th className="actions-column">操作</th>
                </tr>
              </thead>
              <tbody>
                {documents.map((doc) => (
                  <tr key={doc.id}>
                    <td>
                      <div className="file-cell">
                        <span className="file-icon">
                          <FileText size={20} />
                        </span>
                        <div>
                          <strong>{doc.filename}</strong>
                          <span>
                            {fileSize(doc.sizeBytes)}
                            <i>·</i>
                            {doc.chunkCount} 个文本块
                            {doc.attempts > 1 && (
                              <>
                                <i>·</i>尝试 {doc.attempts} 次
                              </>
                            )}
                          </span>
                          {doc.errorMessage && (
                            <p className="file-error">{doc.errorMessage}</p>
                          )}
                        </div>
                      </div>
                    </td>
                    <td>
                      <StatusBadge status={doc.status} />
                    </td>
                    <td className="date-column muted">
                      {dateLabel(doc.createdAt)}
                    </td>
                    <td>
                      <div className="row-actions">
                        <button
                          className="icon-button"
                          aria-label={`下载 ${doc.filename}`}
                          disabled={!!busy || doc.status.startsWith("DELET")}
                          onClick={() =>
                            void action(
                              doc.id,
                              async () =>
                                saveBlob(
                                  await api.download(doc.id),
                                  doc.filename,
                                ),
                              "原文件已开始下载。",
                            )
                          }
                        >
                          <ArrowDownToLine size={17} />
                        </button>
                        {owner && (
                          <>
                            {["FAILED", "DELETE_FAILED"].includes(
                              doc.status,
                            ) && (
                              <button
                                className="icon-button"
                                aria-label={`重试 ${doc.filename}`}
                                disabled={!!busy}
                                onClick={() =>
                                  void action(
                                    doc.id,
                                    () => api.retryDocument(doc.id),
                                    "已重新提交处理任务。",
                                  )
                                }
                              >
                                <RefreshCw size={17} />
                              </button>
                            )}
                            <button
                              className="icon-button danger-text"
                              aria-label={`删除 ${doc.filename}`}
                              disabled={
                                !!busy ||
                                ["DELETE_PENDING", "DELETING"].includes(
                                  doc.status,
                                )
                              }
                              onClick={() => {
                                setError("");
                                setDeleting(doc);
                              }}
                            >
                              <Trash2 size={17} />
                            </button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="table-footer">
          <span>每页最多 20 条 · 第 {page + 1} 页</span>
          <div>
            <button
              className="icon-button"
              aria-label="上一页"
              disabled={page === 0}
              onClick={() => setPage(page - 1)}
            >
              <ChevronLeft size={18} />
            </button>
            <button
              className="icon-button"
              aria-label="下一页"
              disabled={documents.length < 20}
              onClick={() => setPage(page + 1)}
            >
              <ChevronRight size={18} />
            </button>
          </div>
        </div>
      </div>
      <p className="page-footnote">
        扫描版 PDF 需要先识别为文字；加密文件或损坏文件可能处理失败。
        <a href="/sample-handbook.md" download>
          下载示例员工手册
        </a>
      </p>
      {uploading && (
        <UploadModal
          api={api}
          kb={kb}
          onClose={() => setUploading(false)}
          onUploaded={() => {
            setUploading(false);
            setNotice("上传成功，正在等待解析与索引。");
            query.refresh();
          }}
        />
      )}
      {deleting && (
        <Modal
          title="删除这份文档？"
          busy={!!busy}
          onClose={() => {
            setDeleting(undefined);
            setError("");
          }}
        >
          <p className="modal-copy">
            「{deleting.filename}
            」将退出问答检索，原文件也会被删除。此操作无法撤销。
          </p>
          <ErrorNotice message={error} />
          <div className="modal-actions">
            <button
              className="button secondary"
              disabled={!!busy}
              onClick={() => setDeleting(undefined)}
            >
              取消
            </button>
            <button
              className="button danger"
              disabled={!!busy}
              onClick={() =>
                void action(
                  deleting.id,
                  () => api.deleteDocument(deleting.id),
                  "已提交删除，文档将退出检索。",
                )
              }
            >
              {busy && <Spinner />}确认删除
            </button>
          </div>
        </Modal>
      )}
    </section>
  );
}
function UploadModal({
  api,
  kb,
  onClose,
  onUploaded,
}: {
  api: Api;
  kb: KnowledgeBase;
  onClose: () => void;
  onUploaded: () => void;
}) {
  const [file, setFile] = useState<File>();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [dragging, setDragging] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  function choose(files: FileList | null) {
    setError("");
    const picked = files?.[0];
    if (!picked) return;
    if (files.length > 1) {
      setError("单次请选择一份文件。");
      return;
    }
    if (!/\.(pdf|docx?|txt|md)$/i.test(picked.name)) {
      setError("支持 PDF、DOC、DOCX、TXT 和 Markdown 文件。");
      return;
    }
    if (!picked.size || picked.size > 20 * 1024 * 1024) {
      setError("请选择非空且不超过 20 MB 的文件。");
      return;
    }
    setFile(picked);
  }
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!file) return;
    setBusy(true);
    setError("");
    try {
      await api.upload(kb.id, file);
      onUploaded();
    } catch (e) {
      setError(messageOf(e));
      setBusy(false);
    }
  }
  return (
    <Modal title="上传知识文档" onClose={onClose} busy={busy}>
      <form onSubmit={submit} className="form-stack">
        <p className="muted">添加到「{kb.name}」，处理成功后即可提问。</p>
        <input
          ref={input}
          className="sr-only"
          type="file"
          id="upload-file"
          aria-label="选择上传文件"
          accept=".pdf,.doc,.docx,.txt,.md"
          onChange={(e) => choose(e.target.files)}
          disabled={busy}
        />
        <div
          className={`dropzone ${dragging ? "dragging" : ""}`}
          onDragOver={(e) => {
            e.preventDefault();
            if (!busy) setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(e) => {
            e.preventDefault();
            setDragging(false);
            if (!busy) choose(e.dataTransfer.files);
          }}
        >
          <UploadCloud size={34} strokeWidth={1.5} />
          <strong>{file ? file.name : "将文件拖到这里"}</strong>
          <span>
            {file
              ? fileSize(file.size)
              : "PDF、Word、TXT、Markdown · 不超过 20 MB"}
          </span>
          <button
            type="button"
            className="button secondary"
            disabled={busy}
            onClick={() => input.current?.click()}
          >
            {file ? "重新选择" : "选择文件"}
          </button>
        </div>
        <ErrorNotice message={error} />
        <div className="modal-actions">
          <button
            type="button"
            className="button secondary"
            disabled={busy}
            onClick={onClose}
          >
            取消
          </button>
          <button className="button primary" disabled={!file || busy}>
            {busy && <Spinner />}
            {busy ? "正在上传…" : "开始上传"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
