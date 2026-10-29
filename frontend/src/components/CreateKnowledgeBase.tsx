import { useState, type FormEvent } from "react";
import { ErrorNotice, Modal, Spinner } from "./ui";
import { messageOf } from "../hooks";
import type { Api, KnowledgeBase } from "../types";

export function CreateKnowledgeBase({
  api,
  onClose,
  onCreated,
}: {
  api: Api;
  onClose: () => void;
  onCreated: (kb: KnowledgeBase) => void;
}) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    setBusy(true);
    setError("");
    try {
      onCreated(await api.createKnowledgeBase(name.trim(), description.trim()));
    } catch (e) {
      setError(messageOf(e));
      setBusy(false);
    }
  }
  return (
    <Modal title="新建知识库" onClose={onClose} busy={busy}>
      <form className="form-stack" onSubmit={submit}>
        <p className="muted">按团队或主题组织资料，创建后可邀请同事读取。</p>
        <ErrorNotice message={error} />
        <label htmlFor="kb-name">
          知识库名称
          <input
            id="kb-name"
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="例如：人事制度、产品使用指南"
            maxLength={100}
            required
          />
        </label>
        <label htmlFor="kb-description">
          简介 <span className="muted optional">选填</span>
          <textarea
            id="kb-description"
            rows={3}
            maxLength={500}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="说明这个知识库包含哪些内容"
          />
        </label>
        <div className="modal-actions">
          <button
            type="button"
            className="button secondary"
            disabled={busy}
            onClick={onClose}
          >
            取消
          </button>
          <button className="button primary" disabled={busy || !name.trim()}>
            {busy && <Spinner />}创建知识库
          </button>
        </div>
      </form>
    </Modal>
  );
}
