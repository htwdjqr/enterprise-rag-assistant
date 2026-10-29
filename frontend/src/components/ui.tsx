import { useEffect, useRef, type ReactNode } from "react";
import { AlertCircle, Check, FileText, LoaderCircle, X } from "lucide-react";
import type { DocumentStatus } from "../types";

export function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <div className="brand">
      <span className="brand-symbol" aria-hidden="true">
        <i />
        <i />
        <i />
        <i />
      </span>
      <div>
        <strong>
          知序<span className="brand-dot">.</span>
        </strong>
        {!compact && <span className="brand-caption">企业知识助手</span>}
      </div>
    </div>
  );
}
export function Spinner() {
  return <LoaderCircle className="spin" size={17} aria-hidden="true" />;
}
export function ErrorNotice({
  message,
  retry,
}: {
  message?: string;
  retry?: () => void;
}) {
  return message ? (
    <div className="notice error" role="alert">
      <AlertCircle size={18} aria-hidden="true" />
      <span>{message}</span>
      {retry && (
        <button className="text-button" onClick={retry}>
          重试
        </button>
      )}
    </div>
  ) : null;
}
export function EmptyState({
  title,
  children,
  action,
}: {
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="empty-state">
      <div className="empty-icon">
        <FileText size={26} />
      </div>
      <h3>{title}</h3>
      <p>{children}</p>
      {action}
    </div>
  );
}
const statuses: Record<DocumentStatus, { text: string; type: string }> = {
  PENDING: { text: "等待处理", type: "pending" },
  INDEXING: { text: "正在索引", type: "pending" },
  READY: { text: "已就绪", type: "ready" },
  FAILED: { text: "处理失败", type: "failed" },
  DELETE_PENDING: { text: "等待删除", type: "pending" },
  DELETING: { text: "删除中", type: "pending" },
  DELETE_FAILED: { text: "删除失败", type: "failed" },
  DELETED: { text: "已删除", type: "muted" },
};
export function StatusBadge({ status }: { status: DocumentStatus }) {
  const { text, type } = statuses[status];
  return (
    <span className={`badge ${type}`}>
      {type === "ready" ? <Check size={12} /> : <span className="status-dot" />}
      {text}
    </span>
  );
}
/** 原生 dialog 提供键盘焦点约束；关闭与 Escape 都回到触发按钮。 */
export function Modal({
  title,
  children,
  onClose,
  busy = false,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
  busy?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    const opener = document.activeElement as HTMLElement | null;
    dialog?.showModal();
    // React 的 autoFocus 可能在 dialog 尚未打开时执行，因此打开后再聚焦首个可见输入。
    dialog
      ?.querySelector<HTMLElement>('input:not([type="file"]), textarea')
      ?.focus();
    return () => {
      dialog?.close();
      opener?.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      className="modal"
      aria-labelledby="modal-title"
      onCancel={(e) => {
        e.preventDefault();
        if (!busy) onClose();
      }}
    >
      <div className="modal-header">
        <h2 id="modal-title">{title}</h2>
        <button
          className="icon-button"
          aria-label="关闭弹窗"
          disabled={busy}
          onClick={onClose}
        >
          <X size={20} />
        </button>
      </div>
      {children}
    </dialog>
  );
}
