import { useEffect, useRef, useState, type FormEvent } from "react";
import {
  ArrowDownToLine,
  ArrowRight,
  ArrowUp,
  BookOpen,
  Check,
  FileText,
  Lightbulb,
  MessageSquarePlus,
  Quote,
  ShieldCheck,
  Sparkles,
} from "lucide-react";
import ReactMarkdown from "react-markdown";
import { Link } from "react-router-dom";
import { ErrorNotice, Spinner, StatusBadge } from "../components/ui";
import { messageOf, saveBlob, useQuery } from "../hooks";
import type { Answer, Api, KnowledgeBase } from "../types";

type Turn = { id: string; question: string; answer?: Answer; error?: string };
const suggestions = [
  {
    title: "差旅报销",
    question: "出差住宿每天最多报销多少钱？需要什么材料？",
    icon: FileText,
  },
  {
    title: "休假申请",
    question: "申请年假需要提前多久，谁来审批？",
    icon: BookOpen,
  },
  {
    title: "办公支持",
    question: "办公电脑发生故障，应该如何联系 IT 支持？",
    icon: Lightbulb,
  },
];

export function AskPage({ api, kb }: { api: Api; kb: KnowledgeBase }) {
  const [question, setQuestion] = useState("");
  const [turns, setTurns] = useState<Turn[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [downloading, setDownloading] = useState("");
  const pending = useRef<AbortController>(undefined);
  const end = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  const docs = useQuery(() => api.listDocuments(kb.id, 0, 5), 5000);
  useEffect(() => () => pending.current?.abort(), []);
  useEffect(() => {
    if (turns.length)
      end.current?.scrollIntoView({ behavior: "instant", block: "nearest" });
  }, [turns, busy]);
  async function send(e?: FormEvent) {
    e?.preventDefault();
    const value = question.trim();
    if (!value || busy) return;
    const id = crypto.randomUUID();
    const controller = new AbortController();
    pending.current = controller;
    setQuestion("");
    setError("");
    setBusy(true);
    setTurns((t) => [...t, { id, question: value }]);
    try {
      const answer = await api.ask(kb.id, value, controller.signal);
      if (!controller.signal.aborted)
        setTurns((t) =>
          t.map((turn) => (turn.id === id ? { ...turn, answer } : turn)),
        );
    } catch (e) {
      if (!controller.signal.aborted)
        setTurns((t) =>
          t.map((turn) =>
            turn.id === id ? { ...turn, error: messageOf(e) } : turn,
          ),
        );
    } finally {
      if (!controller.signal.aborted) {
        setBusy(false);
        input.current?.focus();
      }
    }
  }
  async function download(id: string, filename: string) {
    setDownloading(id);
    setError("");
    try {
      saveBlob(await api.download(id), filename);
    } catch (e) {
      setError(messageOf(e));
    } finally {
      setDownloading("");
    }
  }
  return (
    <div className="ask-layout">
      <section className="conversation-panel">
        <div className="section-toolbar">
          <div className="eyebrow">
            <span className="green-dot" /> 知识问答
          </div>
          <button
            className="text-button"
            disabled={busy || !turns.length}
            onClick={() => {
              setTurns([]);
              setQuestion("");
              input.current?.focus();
            }}
          >
            <MessageSquarePlus size={16} />
            新对话
          </button>
        </div>
        <div className={`conversation ${turns.length ? "has-turns" : ""}`}>
          {!turns.length ? (
            <div className="welcome">
              <div className="welcome-symbol">
                <Sparkles size={29} strokeWidth={1.4} />
              </div>
              <div className="eyebrow">ASK. DISCOVER. UNDERSTAND.</div>
              <h2>
                让企业知识，
                <br className="mobile-break" />
                随问即答。
              </h2>
              <p>
                查制度、找流程、解疑问。
                <br />
                从「{kb.name}」中，为你找到有依据的回答。
              </p>
              <div className="suggestions">
                {suggestions.map(({ title, question: prompt, icon: Icon }) => (
                  <button
                    key={title}
                    onClick={() => {
                      setQuestion(prompt);
                      input.current?.focus();
                    }}
                  >
                    <Icon size={19} />
                    <strong>{title}</strong>
                    <span>{prompt}</span>
                    <ArrowRight size={15} />
                  </button>
                ))}
              </div>
              <div className="welcome-note">
                <ShieldCheck size={15} /> 只检索你有权访问的当前知识库
              </div>
            </div>
          ) : (
            <div className="turns">
              {turns.map((turn) => (
                <article key={turn.id} className="turn">
                  <div className="user-message">
                    <span className="avatar small">
                      {api.user.username.slice(0, 1).toUpperCase()}
                    </span>
                    <div>
                      <span className="message-label">你</span>
                      <p>{turn.question}</p>
                    </div>
                  </div>
                  <div className="assistant-message">
                    <span className="assistant-avatar">
                      <Sparkles size={17} />
                    </span>
                    <div className="assistant-content">
                      <span className="message-label">
                        知序助手
                        {api.mode === "demo" && (
                          <span className="subtle-label">示例回答</span>
                        )}
                      </span>
                      {turn.error ? (
                        <>
                          <ErrorNotice message={turn.error} />
                          <button
                            className="text-button"
                            onClick={() => {
                              setQuestion(turn.question);
                              input.current?.focus();
                            }}
                          >
                            重新编辑问题 <ArrowRight size={15} />
                          </button>
                        </>
                      ) : turn.answer ? (
                        <>
                          {turn.answer.status === "NO_EVIDENCE" && (
                            <span className="badge pending">资料不足</span>
                          )}
                          <div className="markdown">
                            <ReactMarkdown>{turn.answer.answer}</ReactMarkdown>
                          </div>
                          {!!turn.answer.sources.length && (
                            <div className="sources">
                              <div className="source-label">
                                <Quote size={14} /> 参考来源 ·{" "}
                                {turn.answer.sources.length} 条
                              </div>
                              {turn.answer.sources.map((source) => (
                                <details
                                  className="source-card"
                                  key={`${source.reference}-${source.documentId}`}
                                >
                                  <summary>
                                    <span className="reference-number">
                                      {source.reference}
                                    </span>
                                    <span>{source.filename}</span>
                                    <span className="source-expand">
                                      查看原文
                                    </span>
                                  </summary>
                                  <div className="source-detail">
                                    <p>{source.excerpt}</p>
                                    <div className="source-footer">
                                      <span>
                                        文本块 {source.chunkIndex + 1}
                                        {source.score !== null &&
                                          ` · 相关性 ${source.score.toFixed(2)}`}
                                      </span>
                                      <button
                                        className="text-button"
                                        disabled={!!downloading}
                                        onClick={() =>
                                          void download(
                                            source.documentId,
                                            source.filename,
                                          )
                                        }
                                      >
                                        {downloading === source.documentId ? (
                                          <Spinner />
                                        ) : (
                                          <ArrowDownToLine size={14} />
                                        )}
                                        下载原文件
                                      </button>
                                    </div>
                                  </div>
                                </details>
                              ))}
                            </div>
                          )}
                          <span className="response-time">
                            <Check size={12} /> 检索与回答耗时{" "}
                            {(turn.answer.elapsedMs / 1000).toFixed(1)} 秒
                          </span>
                        </>
                      ) : (
                        <div className="thinking" role="status">
                          <Spinner /> 正在查阅资料并组织回答…
                        </div>
                      )}
                    </div>
                  </div>
                </article>
              ))}
              <div ref={end} />
            </div>
          )}
        </div>
        <div className="composer-area">
          <ErrorNotice message={error} />
          <form className="composer" onSubmit={send}>
            <label className="sr-only" htmlFor="question">
              输入问题
            </label>
            <textarea
              ref={input}
              id="question"
              rows={2}
              maxLength={2000}
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              placeholder={`向「${kb.name}」提问…`}
              onKeyDown={(e) => {
                if (
                  e.key === "Enter" &&
                  !e.shiftKey &&
                  !e.nativeEvent.isComposing
                ) {
                  e.preventDefault();
                  void send();
                }
              }}
            />
            <div className="composer-bottom">
              <span>
                <BookOpen size={14} /> {kb.name}
              </span>
              <div>
                <span className="character-count">
                  {question.length} / 2000
                </span>
                <button
                  className="send-button"
                  aria-label="发送问题"
                  disabled={busy || !question.trim()}
                >
                  {busy ? <Spinner /> : <ArrowUp size={21} />}
                </button>
              </div>
            </div>
          </form>
          <p className="composer-note">
            Enter 发送 · Shift + Enter 换行{" "}
            <span>每次独立检索，重要结论请核对原文。</span>
          </p>
        </div>
      </section>
      <aside className="knowledge-rail">
        <div className="rail-heading">
          <BookOpen size={18} />
          <h3>当前知识范围</h3>
        </div>
        <div className="scope-card">
          <span className="scope-icon">
            <BookOpen size={22} />
          </span>
          <h4>{kb.name}</h4>
          <p>{kb.description || "在这里汇聚团队资料，建立共享的知识空间。"}</p>
          <span className="badge ready">
            <ShieldCheck size={12} />
            {kb.ownerId === api.user.id ? "你是创建人" : "你有读取权限"}
          </span>
        </div>
        <div className="rail-heading small">
          <h3>近期文档</h3>
          <Link to={`/documents?kb=${kb.id}`}>
            查看全部 <ArrowRight size={13} />
          </Link>
        </div>
        <ErrorNotice message={docs.error} retry={docs.refresh} />
        <div className="rail-docs">
          {docs.loading && !docs.data ? (
            <div className="muted">
              <Spinner />
              加载文档…
            </div>
          ) : docs.data?.length ? (
            docs.data.slice(0, 3).map((doc) => (
              <div key={doc.id}>
                <FileText size={18} />
                <div>
                  <span>{doc.filename}</span>
                  <StatusBadge status={doc.status} />
                </div>
              </div>
            ))
          ) : (
            <p className="muted">还没有文档。添加资料后，即可开始问答。</p>
          )}
        </div>
        <div className="rail-tip">
          <Lightbulb size={19} />
          <h4>一个好问题，更接近答案</h4>
          <p>试着描述具体场景，例如“出差结束后，需要在几天内提交报销？”</p>
        </div>
        <div className="rail-footer">
          <ShieldCheck size={16} />
          <p>
            回答以可访问的资料为依据，
            <br />
            来源可展开，也可下载核对。
          </p>
        </div>
      </aside>
    </div>
  );
}
