import type {
  Answer,
  Api,
  KnowledgeBase,
  KnowledgeDocument,
  Member,
  User,
} from "../types";

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

/**
 * 真实后端适配器。密码仅用于登录请求，服务端会话由 HttpOnly Cookie 标识。
 * 写请求携带 /api/csrf 返回的掩码 token；Cookie 由浏览器同源发送。
 */
export class HttpApi implements Api {
  readonly mode = "live" as const;
  user!: User;
  onExpired?: () => void;
  private csrf?: { headerName: string; token: string };
  private requests = new Set<AbortController>();
  private closed = false;

  private constructor() {}

  static async login(username: string, password: string) {
    const api = new HttpApi();
    try {
      api.csrf = await api.request("/api/csrf");
      await api.request("/api/login", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ username, password }),
      });
      api.user = await api.request<User>("/api/me");
      // 登录会轮换会话并清除旧 CSRF token，必须重新获取后再执行写操作。
      api.csrf = await api.request("/api/csrf");
      return api;
    } catch (error) {
      api.dispose();
      throw error;
    }
  }

  /** 刷新后由 Cookie 恢复身份；401 表示尚未登录，其他错误保留具体提示。 */
  static async restore() {
    const api = new HttpApi();
    try {
      api.user = await api.request<User>("/api/me");
      api.csrf = await api.request("/api/csrf");
      return api;
    } catch (error) {
      api.dispose();
      if (error instanceof ApiError && error.status === 401) return undefined;
      throw error;
    }
  }

  async logout() {
    // 先销毁服务端会话，成功后再清理内存，避免刷新又自动登录。
    await this.request("/api/logout", { method: "POST" });
    this.dispose();
  }

  private async request<T>(
    path: string,
    init: RequestInit = {},
    blob = false,
  ): Promise<T> {
    if (this.closed) throw new ApiError(401, "请重新登录。");
    const controller = new AbortController();
    this.requests.add(controller);
    const abort = () => controller.abort();
    init.signal?.addEventListener("abort", abort, { once: true });
    if (init.signal?.aborted) controller.abort();
    // 模型可能比普通接口慢，超时后明确提示，禁止自动重试可能产生费用的问答。
    const timer = setTimeout(abort, 180_000);
    try {
      const headers = new Headers(init.headers);
      headers.set("X-Requested-With", "XMLHttpRequest");
      if (
        init.body &&
        !(init.body instanceof FormData) &&
        !headers.has("Content-Type")
      )
        headers.set("Content-Type", "application/json");
      if (this.csrf && !["GET", "HEAD"].includes(init.method || "GET")) {
        headers.set(this.csrf.headerName, this.csrf.token);
      }
      const response = await fetch(path, {
        ...init,
        headers,
        credentials: "same-origin",
        signal: controller.signal,
      });
      if (!response.ok) {
        let detail: string | undefined;
        if (response.headers.get("content-type")?.includes("json")) {
          const body = await response.json().catch(() => ({}));
          detail = typeof body.detail === "string" ? body.detail : undefined;
        }
        const fallback: Record<number, string> = {
          401: "用户名或密码错误，或登录已失效。",
          403: "没有操作权限，或登录状态已变化，请重新登录后再试。",
          404: "内容不存在或已失去访问权限，请刷新知识库。",
          413: "文件超过 20 MB，请选择较小的文件。",
          503: "模型或检索服务暂不可用，请稍后重试。",
        };
        if (response.status === 401) this.onExpired?.();
        throw new ApiError(
          response.status,
          detail || fallback[response.status] || "服务处理失败，请稍后重试。",
        );
      }
      if (blob) return (await response.blob()) as T;
      if (
        response.status === 204 ||
        response.headers.get("content-length") === "0"
      )
        return undefined as T;
      // retry/delete 接口返回 202 空响应；不能一律 response.json()。
      const text = await response.text();
      return text ? (JSON.parse(text) as T) : (undefined as T);
    } catch (error) {
      if (error instanceof ApiError) throw error;
      if (controller.signal.aborted)
        throw new ApiError(408, "请求已取消或等待超时，请检查状态后重试。");
      throw new ApiError(0, "无法连接服务，请检查后端是否启动或稍后重试。");
    } finally {
      clearTimeout(timer);
      init.signal?.removeEventListener("abort", abort);
      this.requests.delete(controller);
    }
  }

  listKnowledgeBases() {
    return this.request<KnowledgeBase[]>("/api/knowledge-bases");
  }
  createKnowledgeBase(name: string, description: string) {
    return this.request<KnowledgeBase>("/api/knowledge-bases", {
      method: "POST",
      body: JSON.stringify({ name, description }),
    });
  }
  listDocuments(kb: string, page: number, size: number) {
    return this.request<KnowledgeDocument[]>(
      `/api/knowledge-bases/${encodeURIComponent(kb)}/documents?page=${page}&size=${size}`,
    );
  }
  upload(kb: string, file: File) {
    const body = new FormData();
    body.append("file", file);
    return this.request<KnowledgeDocument>(
      `/api/knowledge-bases/${encodeURIComponent(kb)}/documents`,
      { method: "POST", body },
    );
  }
  retryDocument(id: string) {
    return this.request<void>(
      `/api/documents/${encodeURIComponent(id)}/retry`,
      { method: "POST" },
    );
  }
  deleteDocument(id: string) {
    return this.request<void>(`/api/documents/${encodeURIComponent(id)}`, {
      method: "DELETE",
    });
  }
  download(id: string) {
    return this.request<Blob>(
      `/api/documents/${encodeURIComponent(id)}/file`,
      {},
      true,
    );
  }
  listMembers(kb: string) {
    return this.request<Member[]>(
      `/api/knowledge-bases/${encodeURIComponent(kb)}/members`,
    );
  }
  grant(kb: string, username: string) {
    return this.request<void>(
      `/api/knowledge-bases/${encodeURIComponent(kb)}/members`,
      { method: "POST", body: JSON.stringify({ username }) },
    );
  }
  revoke(kb: string, username: string) {
    return this.request<void>(
      `/api/knowledge-bases/${encodeURIComponent(kb)}/members/${encodeURIComponent(username)}`,
      { method: "DELETE" },
    );
  }
  ask(kb: string, question: string, signal?: AbortSignal) {
    return this.request<Answer>(
      `/api/knowledge-bases/${encodeURIComponent(kb)}/questions`,
      {
        method: "POST",
        body: JSON.stringify({ question }),
        signal,
      },
    );
  }
  dispose() {
    this.closed = true;
    this.csrf = undefined;
    this.requests.forEach((controller) => controller.abort());
    this.requests.clear();
  }
}
