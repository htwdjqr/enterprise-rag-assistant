import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, HttpApi } from "./http";

const response = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
function mockLogin() {
  return vi
    .fn<typeof fetch>()
    .mockResolvedValueOnce(
      response({ headerName: "X-XSRF-TOKEN", token: "before-login" }),
    )
    .mockResolvedValueOnce(new Response(null, { status: 204 }))
    .mockResolvedValueOnce(
      response({ id: "u1", username: "alice", tenantId: "company" }),
    )
    .mockResolvedValueOnce(
      response({ headerName: "X-XSRF-TOKEN", token: "masked-token" }),
    );
}
afterEach(() => vi.unstubAllGlobals());

describe("真实 API 适配器", () => {
  it("后端不可用时展示代理给出的启动提示，密码错误仍明确提示认证失败", async () => {
    const detail =
      "无法连接后端服务，请先启动 Spring Boot 后端并检查 API 代理地址。";
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response({ detail }, 503))
      .mockResolvedValueOnce(
        response({ headerName: "X-XSRF-TOKEN", token: "before-login" }),
      )
      .mockResolvedValueOnce(new Response(null, { status: 401 }));
    vi.stubGlobal("fetch", fetcher);
    await expect(HttpApi.login("alice", "test-password")).rejects.toMatchObject(
      { status: 503, message: detail },
    );
    await expect(
      HttpApi.login("alice", "incorrect-password"),
    ).rejects.toMatchObject({
      status: 401,
      message: "用户名或密码错误，或登录已失效。",
    });
  });
  it("使用独立认证请求获取身份和 CSRF，并为写操作附带 token", async () => {
    const fetcher = mockLogin().mockResolvedValueOnce(response({ id: "kb1" }));
    vi.stubGlobal("fetch", fetcher);
    const api = await HttpApi.login("alice", "test-password");
    await api.createKnowledgeBase("知识库", "说明");
    const login = fetcher.mock.calls[1][1]!;
    expect(new Headers(login.headers).get("Content-Type")).toBe(
      "application/x-www-form-urlencoded",
    );
    expect((login.body as URLSearchParams).get("username")).toBe("alice");
    expect((login.body as URLSearchParams).get("password")).toBe(
      "test-password",
    );
    expect(new Headers(login.headers).get("X-XSRF-TOKEN")).toBe("before-login");
    const options = fetcher.mock.calls[4][1]!;
    const headers = new Headers(options.headers);
    expect(headers.has("Authorization")).toBe(false);
    expect(headers.get("X-XSRF-TOKEN")).toBe("masked-token");
    expect(options.credentials).toBe("same-origin");
    expect(JSON.parse(options.body as string)).toEqual({
      name: "知识库",
      description: "说明",
    });
    api.dispose();
  });
  it("上传不手工设置 multipart boundary，202 空响应正常返回", async () => {
    const fetcher = mockLogin()
      .mockResolvedValueOnce(response({ id: "doc" }))
      .mockResolvedValueOnce(new Response(null, { status: 202 }));
    vi.stubGlobal("fetch", fetcher);
    const api = await HttpApi.login("alice", "test-password");
    await api.upload("kb", new File(["中文"], "policy.md"));
    expect(fetcher.mock.calls[4][1]?.body).toBeInstanceOf(FormData);
    expect(
      new Headers(fetcher.mock.calls[4][1]?.headers).has("Content-Type"),
    ).toBe(false);
    await expect(api.retryDocument("doc")).resolves.toBeUndefined();
    api.dispose();
  });
  it("登录过期通知页面，退出后不再发送包含凭据的请求", async () => {
    const fetcher = mockLogin().mockResolvedValueOnce(
      new Response(null, { status: 401 }),
    );
    vi.stubGlobal("fetch", fetcher);
    const api = await HttpApi.login("alice", "test-password");
    const expired = vi.fn();
    api.onExpired = expired;
    await expect(api.listKnowledgeBases()).rejects.toBeInstanceOf(ApiError);
    expect(expired).toHaveBeenCalledOnce();
    api.dispose();
    await expect(api.listKnowledgeBases()).rejects.toMatchObject({
      status: 401,
    });
    expect(fetcher).toHaveBeenCalledTimes(5);
  });

  it("通过 Cookie 恢复会话，退出调用服务端并禁止继续请求", async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        response({ id: "u1", username: "alice", tenantId: "company" }),
      )
      .mockResolvedValueOnce(
        response({ headerName: "X-XSRF-TOKEN", token: "restored-token" }),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 401 }));
    vi.stubGlobal("fetch", fetcher);
    const api = (await HttpApi.restore())!;
    expect(api.user.username).toBe("alice");
    expect(fetcher.mock.calls[0][1]?.credentials).toBe("same-origin");
    await api.logout();
    expect(fetcher.mock.calls[2][0]).toBe("/api/logout");
    expect(
      new Headers(fetcher.mock.calls[2][1]?.headers).get("X-XSRF-TOKEN"),
    ).toBe("restored-token");
    await expect(api.listKnowledgeBases()).rejects.toMatchObject({
      status: 401,
    });
    await expect(HttpApi.restore()).resolves.toBeUndefined();
    expect(fetcher).toHaveBeenCalledTimes(4);
  });
  it("读取后端 ProblemDetail，下载通过认证获取 Blob", async () => {
    const fetcher = mockLogin()
      .mockResolvedValueOnce(response({ detail: "资料已发生变化" }, 409))
      .mockResolvedValueOnce(
        new Response("文档原文", {
          headers: { "Content-Type": "application/octet-stream" },
        }),
      );
    vi.stubGlobal("fetch", fetcher);
    const api = await HttpApi.login("alice", "test-password");
    await expect(api.ask("kb", "问题")).rejects.toThrow("资料已发生变化");
    expect(await (await api.download("doc")).text()).toBe("文档原文");
    api.dispose();
  });
});
