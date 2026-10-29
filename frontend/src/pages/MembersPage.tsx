import { useState, type FormEvent } from "react";
import { LockKeyhole, Plus, ShieldCheck, UserRound, X } from "lucide-react";
import { ErrorNotice, Modal, Spinner } from "../components/ui";
import { messageOf, useQuery } from "../hooks";
import type { Api, KnowledgeBase, Member } from "../types";

export function MembersPage({ api, kb }: { api: Api; kb: KnowledgeBase }) {
  if (kb.ownerId !== api.user.id)
    return (
      <section className="page-content">
        <div className="permission-empty">
          <LockKeyhole size={32} />
          <h2>你拥有此知识库的读取权限</h2>
          <p>可以提问、查看和下载资料。成员授权由知识库创建人管理。</p>
        </div>
      </section>
    );
  return <MemberManagement api={api} kb={kb} />;
}
function MemberManagement({ api, kb }: { api: Api; kb: KnowledgeBase }) {
  const query = useQuery(() => api.listMembers(kb.id));
  const [username, setUsername] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [removing, setRemoving] = useState<Member>();
  async function grant(e: FormEvent) {
    e.preventDefault();
    if (!username.trim()) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await api.grant(kb.id, username.trim());
      setNotice(`已授予 ${username.trim()} 读取权限。`);
      setUsername("");
      query.refresh();
    } catch (e) {
      setError(messageOf(e));
    } finally {
      setBusy(false);
    }
  }
  async function revoke() {
    if (!removing) return;
    setBusy(true);
    setError("");
    try {
      await api.revoke(kb.id, removing.username);
      setNotice(`已撤销 ${removing.username} 的读取权限。`);
      setRemoving(undefined);
      query.refresh();
    } catch (e) {
      setError(messageOf(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="page-content">
      <div className="page-heading">
        <div>
          <div className="eyebrow">ACCESS & COLLABORATION</div>
          <h2>让合适的人，找到需要的知识</h2>
          <p>管理「{kb.name}」的成员，共享资料与问答能力。</p>
        </div>
        <span className="badge ready">
          <ShieldCheck size={14} />
          创建人权限
        </span>
      </div>
      <div className="member-layout">
        <div className="data-panel">
          <div className="data-toolbar">
            <h3>
              知识库成员 <span>{query.data?.length ?? "—"} 人</span>
            </h3>
            <button
              className="text-button"
              onClick={query.refresh}
              disabled={query.loading}
            >
              刷新
            </button>
          </div>
          <ErrorNotice message={query.error} retry={query.refresh} />
          {query.loading && !query.data ? (
            <div className="loading-state">
              <Spinner />
              正在加载成员…
            </div>
          ) : (
            <div className="member-list">
              {query.data?.map((member) => (
                <div className="member-row" key={member.id}>
                  <span className="avatar">
                    {member.username.slice(0, 1).toUpperCase()}
                  </span>
                  <div>
                    <strong>
                      {member.username}
                      {member.id === api.user.id && <small>你</small>}
                    </strong>
                    <span>
                      {member.role === "OWNER"
                        ? "管理资料、成员和知识库内容"
                        : "查看资料、下载文件和提问"}
                    </span>
                  </div>
                  <span
                    className={`badge ${member.role === "OWNER" ? "ready" : "muted"}`}
                  >
                    {member.role === "OWNER" ? "创建人" : "可读取"}
                  </span>
                  {member.role === "READER" && (
                    <button
                      className="text-button danger-text"
                      disabled={busy}
                      onClick={() => {
                        setError("");
                        setRemoving(member);
                      }}
                    >
                      撤销权限
                    </button>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
        <div className="invite-card">
          <span className="invite-icon">
            <UserRound size={23} />
          </span>
          <h3>邀请一位同事</h3>
          <p>输入同租户内已有的账号，为其授予此知识库的读取权限。</p>
          <form className="form-stack" onSubmit={grant}>
            <label htmlFor="member-username">
              成员账号
              <input
                id="member-username"
                maxLength={64}
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder={
                  api.mode === "demo" ? "例如：bob" : "请输入同事账号"
                }
                required
                disabled={busy}
              />
            </label>
            <button
              className="button primary wide"
              disabled={busy || !username.trim()}
            >
              {busy ? <Spinner /> : <Plus size={17} />}授予读取权限
            </button>
          </form>
          <p className="form-note">
            创建人的权限不可撤销。
            <br />
            成员不会获得上传或删除权限。
          </p>
        </div>
      </div>
      <ErrorNotice message={error} />
      {notice && (
        <div className="notice success" role="status">
          {notice}
          <button
            className="icon-button"
            aria-label="关闭提示"
            onClick={() => setNotice("")}
          >
            <X size={15} />
          </button>
        </div>
      )}
      {removing && (
        <Modal
          title="撤销成员权限？"
          onClose={() => setRemoving(undefined)}
          busy={busy}
        >
          <p className="modal-copy">
            撤销后，{removing.username} 将无法访问此知识库中的资料或继续提问。
          </p>
          <ErrorNotice message={error} />
          <div className="modal-actions">
            <button
              className="button secondary"
              disabled={busy}
              onClick={() => setRemoving(undefined)}
            >
              取消
            </button>
            <button
              className="button danger"
              disabled={busy}
              onClick={() => void revoke()}
            >
              {busy && <Spinner />}确认撤销
            </button>
          </div>
        </Modal>
      )}
    </section>
  );
}
