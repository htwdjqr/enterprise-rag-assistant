import { useState, type FormEvent } from "react";
import {
  ArrowRight,
  BookOpen,
  Eye,
  EyeOff,
  FileCheck2,
  LockKeyhole,
  Network,
  Quote,
} from "lucide-react";
import { Brand, ErrorNotice, Spinner } from "./ui";
import { HttpApi } from "../api/http";
import { DemoApi } from "../api/demo";
import { messageOf } from "../hooks";
import type { Api } from "../types";

export function Login({
  onLogin,
  notice,
}: {
  onLogin: (api: Api) => void;
  notice?: string;
}) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      const api = await HttpApi.login(username.trim(), password);
      setPassword("");
      onLogin(api);
    } catch (e) {
      setError(messageOf(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="login-page">
      <section className="login-story">
        <Brand />
        <div className="story-body">
          <div className="eyebrow light">
            <span /> KNOWLEDGE, CONNECTED
          </div>
          <h1>
            让散落的知识，
            <br />
            成为工作的答案<span>。</span>
          </h1>
          <p>
            从企业资料中找到依据。
            <br />
            少一点查找，多一点确定。
          </p>
          <div className="story-card">
            <div className="story-question">
              <Quote size={20} /> 出差住宿的报销标准是什么？
            </div>
            <p>
              国内出差住宿上限为每人每天 500 元，需提供住宿发票和出差审批单。
              <sup>[1]</sup>
            </p>
            <div className="story-source">
              <FileCheck2 size={16} /> 员工手册 · 差旅管理 <span>示例回答</span>
            </div>
          </div>
          <div className="story-features">
            <span>
              <BookOpen size={17} /> 统一知识
            </span>
            <span>
              <Network size={17} /> 有据可查
            </span>
            <span>
              <LockKeyhole size={17} /> 按权限访问
            </span>
          </div>
        </div>
        <div className="story-footer">知序 / 企业知识工作台</div>
      </section>
      <main className="login-main">
        <div className="login-form-wrap">
          <div className="eyebrow">YOUR KNOWLEDGE WORKSPACE</div>
          <h2>欢迎回来</h2>
          <p className="subtitle">登录工作台，从一个好问题开始。</p>
          <ErrorNotice message={error || notice} />
          <form onSubmit={submit} className="form-stack">
            <label htmlFor="username">
              账号
              <input
                id="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="请输入账号"
                autoComplete="username"
                required
                maxLength={64}
                pattern="[^:]+"
                disabled={busy}
              />
            </label>
            <label htmlFor="password">
              密码
              <div className="password-field">
                <input
                  id="password"
                  type={showPassword ? "text" : "password"}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="请输入密码"
                  autoComplete="current-password"
                  required
                  disabled={busy}
                />
                <button
                  type="button"
                  className="icon-button"
                  aria-label={showPassword ? "隐藏密码" : "显示密码"}
                  onClick={() => setShowPassword(!showPassword)}
                >
                  {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                </button>
              </div>
            </label>
            <button className="button primary wide" disabled={busy}>
              {busy ? <Spinner /> : null}
              {busy ? "正在登录…" : "登录工作台"}
              <ArrowRight size={18} />
            </button>
          </form>
          <div className="login-divider">
            <span>先了解一下</span>
          </div>
          <button
            className="button secondary wide"
            disabled={busy}
            onClick={() => onLogin(new DemoApi())}
          >
            体验交互演示 <ArrowRight size={17} />
          </button>
          <p className="form-note">
            演示使用虚构资料，不连接模型服务。
            <br />
            真实账号及密码请向管理员获取。
          </p>
        </div>
        <p className="login-footnote">
          <LockKeyhole size={14} />{" "}
          登录有效期内刷新可继续使用，公共设备使用后请退出登录。
        </p>
      </main>
    </div>
  );
}
