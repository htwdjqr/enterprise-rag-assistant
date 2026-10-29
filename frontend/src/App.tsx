import { useEffect, useState } from "react";
import {
  BookOpen,
  ChevronDown,
  CircleHelp,
  FileText,
  LogOut,
  MessageSquare,
  Plus,
  ShieldCheck,
  Users,
} from "lucide-react";
import {
  NavLink,
  Navigate,
  Route,
  Routes,
  useSearchParams,
} from "react-router-dom";
import { Login } from "./components/Login";
import { Brand, EmptyState, ErrorNotice, Spinner } from "./components/ui";
import { CreateKnowledgeBase } from "./components/CreateKnowledgeBase";
import { AskPage } from "./pages/AskPage";
import { DocumentsPage } from "./pages/DocumentsPage";
import { MembersPage } from "./pages/MembersPage";
import { DemoApi } from "./api/demo";
import { HttpApi } from "./api/http";
import { messageOf, useQuery } from "./hooks";
import type { Api } from "./types";

export default function App() {
  const [api, setApi] = useState<Api>();
  const [notice, setNotice] = useState("");
  const [restoring, setRestoring] = useState(true);
  useEffect(() => {
    let active = true;
    void HttpApi.restore()
      .then((restored) => {
        if (active) setApi(restored);
        else restored?.dispose();
      })
      .catch((error) => {
        if (active) setNotice(messageOf(error));
      })
      .finally(() => {
        if (active) setRestoring(false);
      });
    return () => {
      active = false;
    };
  }, []);
  useEffect(() => {
    if (!api) return;
    api.onExpired = () => {
      api.dispose();
      setApi(undefined);
      setNotice("登录已失效，请重新登录。");
    };
    return () => {
      api.onExpired = undefined;
    };
  }, [api]);
  async function logout() {
    try {
      if (api instanceof HttpApi) await api.logout();
      else api?.dispose();
      setApi(undefined);
      setNotice("");
    } catch (error) {
      setNotice(messageOf(error));
    }
  }
  if (restoring)
    return (
      <main className="loading-state" role="status">
        <Spinner />
        正在恢复登录状态…
      </main>
    );
  return api ? (
    <Workspace
      key={`${api.mode}-${api.user.id}`}
      api={api}
      notice={notice}
      logout={logout}
      changeDemoUser={(username) => setApi(new DemoApi(username))}
    />
  ) : (
    <Login
      notice={notice}
      onLogin={(next) => {
        setNotice("");
        setApi(next);
      }}
    />
  );
}

function Workspace({
  api,
  notice,
  logout,
  changeDemoUser,
}: {
  api: Api;
  notice: string;
  logout: () => void;
  changeDemoUser: (username: string) => void;
}) {
  const bases = useQuery(() => api.listKnowledgeBases(), 15_000);
  const [params, setParams] = useSearchParams();
  const [creating, setCreating] = useState(false);
  const selected =
    bases.data?.find((kb) => kb.id === params.get("kb")) || bases.data?.[0];
  const suffix = selected ? `?kb=${encodeURIComponent(selected.id)}` : "";
  useEffect(() => {
    if (selected && params.get("kb") !== selected.id)
      setParams({ kb: selected.id }, { replace: true });
  }, [selected?.id, params, setParams]);
  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">
        跳到主内容
      </a>
      <aside className="sidebar">
        <Brand />
        <div className="workspace-label">
          <span className="workspace-avatar">企</span>
          <div>
            <strong>企业知识空间</strong>
            <span>团队资料与知识问答</span>
          </div>
        </div>
        <div className="sidebar-section-label">工作台</div>
        <nav className="main-nav" aria-label="主要导航">
          <NavLink to={`/ask${suffix}`}>
            <MessageSquare size={19} />
            <span>知识问答</span>
            <span className="nav-arrow">↗</span>
          </NavLink>
          <NavLink to={`/documents${suffix}`}>
            <FileText size={19} />
            <span>文档管理</span>
          </NavLink>
          <NavLink to={`/members${suffix}`}>
            <Users size={19} />
            <span>成员权限</span>
          </NavLink>
        </nav>
        <div className="sidebar-bottom">
          <div className="sidebar-hint">
            <CircleHelp size={18} />
            <strong>从资料到答案</strong>
            <p>
              选择知识库，上传文档，
              <br />
              开始有依据的问答。
            </p>
            <a href="/sample-handbook.md" download>
              获取示例资料 <span>↗</span>
            </a>
          </div>
          <div className="profile">
            <span className="avatar">
              {api.user.username.slice(0, 1).toUpperCase()}
            </span>
            <div>
              <strong>{api.user.username}</strong>
              <span>{api.mode === "demo" ? "示例账号" : "已登录"}</span>
            </div>
            <button
              className="icon-button"
              aria-label="退出登录"
              onClick={logout}
            >
              <LogOut size={18} />
            </button>
          </div>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <div className="breadcrumb">
            工作空间 <span>/</span> <strong>企业知识助手</strong>
          </div>
          <div className="topbar-status">
            <span className="green-dot" />
            {api.mode === "demo" ? "交互演示" : "已连接工作台"}
          </div>
        </header>
        {api.mode === "demo" && (
          <div className="demo-banner">
            <span>
              <strong>示例模式</strong>
              模拟回答，不连接真实后端。资料保存在当前浏览器，刷新后保留；PDF /
              Word 不解析正文。
            </span>
            <label htmlFor="demo-user">
              切换身份{" "}
              <select
                id="demo-user"
                value={api.user.username}
                onChange={(e) => changeDemoUser(e.target.value)}
              >
                <option value="alice">alice · 创建人</option>
                <option value="bob">bob · 成员</option>
              </select>
            </label>
          </div>
        )}
        <div className="knowledge-selector-row">
          <div className="knowledge-select">
            <span className="knowledge-select-icon">
              <BookOpen size={21} />
            </span>
            <div>
              <label htmlFor="knowledge-base">当前知识库</label>
              <div className="select-wrapper">
                <select
                  id="knowledge-base"
                  value={selected?.id || ""}
                  disabled={!bases.data?.length}
                  onChange={(e) => setParams({ kb: e.target.value })}
                >
                  {!bases.data?.length && (
                    <option value="">暂无可访问的知识库</option>
                  )}
                  {bases.data?.map((kb) => (
                    <option value={kb.id} key={kb.id}>
                      {kb.name}
                    </option>
                  ))}
                </select>
                <ChevronDown size={16} />
              </div>
            </div>
          </div>
          <div className="knowledge-controls">
            {selected && (
              <span className="scope-status">
                <ShieldCheck size={15} />
                {selected.ownerId === api.user.id ? "可管理" : "可读取"}
              </span>
            )}
            <button
              className="button secondary"
              onClick={() => setCreating(true)}
            >
              <Plus size={17} />
              新建知识库
            </button>
          </div>
        </div>
        <main id="main-content">
          <ErrorNotice message={notice} />
          <h1 className="sr-only">企业知识助手工作台</h1>
          <ErrorNotice message={bases.error} retry={bases.refresh} />
          {bases.loading && !bases.data ? (
            <div className="loading-state">
              <Spinner />
              正在加载知识库…
            </div>
          ) : selected ? (
            <Routes>
              <Route
                path="/ask"
                element={<AskPage key={selected.id} api={api} kb={selected} />}
              />
              <Route
                path="/documents"
                element={
                  <DocumentsPage key={selected.id} api={api} kb={selected} />
                }
              />
              <Route
                path="/members"
                element={
                  <MembersPage key={selected.id} api={api} kb={selected} />
                }
              />
              <Route
                path="*"
                element={<Navigate to={`/ask${suffix}`} replace />}
              />
            </Routes>
          ) : (
            <EmptyState
              title="建立你的第一个知识空间"
              action={
                <button
                  className="button primary"
                  onClick={() => setCreating(true)}
                >
                  <Plus size={17} />
                  新建知识库
                </button>
              }
            >
              创建知识库并添加资料，或联系创建人为你授权。
            </EmptyState>
          )}
        </main>
      </div>
      {creating && (
        <CreateKnowledgeBase
          api={api}
          onClose={() => setCreating(false)}
          onCreated={(kb) => {
            bases.setData((current) => [kb, ...(current || [])]);
            setParams({ kb: kb.id });
            setCreating(false);
          }}
        />
      )}
    </div>
  );
}
