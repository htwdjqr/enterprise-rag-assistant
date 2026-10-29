import { test, expect, type Page } from "@playwright/test";
import { readFile } from "node:fs/promises";

async function demo(page: Page) {
  await page.goto("/");
  await page.getByRole("button", { name: "体验交互演示" }).click();
  await expect(
    page.getByRole("heading", { name: /让企业知识，\s*随问即答。/ }),
  ).toBeVisible();
}
test("问答、来源原文、鉴权下载及无资料拒答", async ({ page }) => {
  await demo(page);
  await page.screenshot({
    path: "test-results/desktop-workspace.png",
    fullPage: true,
  });
  await page
    .getByRole("textbox", { name: "输入问题" })
    .fill("住宿报销上限是多少？");
  await page.getByRole("button", { name: "发送问题" }).click();
  await expect(page.locator(".markdown")).toContainText("500 元");
  await page.locator(".source-card summary").click();
  await expect(page.locator(".source-detail")).toContainText("出差审批单");
  const downloading = page.waitForEvent("download");
  await page.getByRole("button", { name: "下载原文件" }).click();
  expect((await downloading).suggestedFilename()).toContain("员工手册");
  await page.getByRole("textbox", { name: "输入问题" }).fill("今年奖金多少？");
  await page.getByRole("button", { name: "发送问题" }).click();
  await expect(page.getByText("资料不足", { exact: true })).toBeVisible();
  await page.screenshot({
    path: "test-results/desktop-answer.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "新对话", exact: true }).click();
  await expect(page.locator(".turn")).toHaveCount(0);
});
test("新建知识库、上传、自动刷新就绪状态及删除", async ({ page }) => {
  await demo(page);
  await page.getByRole("button", { name: "新建知识库" }).click();
  await page.getByLabel("知识库名称", { exact: true }).fill("项目文档");
  await page.getByRole("button", { name: "创建知识库", exact: true }).click();
  await expect(page.getByLabel("当前知识库")).toHaveValue(/.+/);
  await expect(page.locator(".composer-bottom")).toContainText("项目文档");
  await page.getByRole("link", { name: "文档管理" }).click();
  await expect(page.getByText("知识从第一份资料开始")).toBeVisible();
  await page.getByRole("button", { name: "上传文档", exact: true }).click();
  await page.getByLabel("选择上传文件").setInputFiles({
    name: "流程.md",
    mimeType: "text/markdown",
    buffer: Buffer.from("住宿上限 300 元。"),
  });
  await page.getByRole("button", { name: "开始上传" }).click();
  const row = page.getByRole("row").filter({ hasText: "流程.md" });
  await expect(row).toContainText("已就绪");
  await row.getByRole("button", { name: "删除 流程.md" }).click();
  await page.getByRole("button", { name: "确认删除" }).click();
  await expect(row).toHaveCount(0);
});
test("失败文档有重试反馈，文件类型在上传前校验", async ({ page }) => {
  await demo(page);
  await page.getByRole("link", { name: "文档管理" }).click();
  await page.getByRole("button", { name: "上传文档", exact: true }).click();
  await page.getByLabel("选择上传文件").setInputFiles({
    name: "test.exe",
    mimeType: "application/octet-stream",
    buffer: Buffer.from("bad"),
  });
  await expect(page.getByRole("alert")).toContainText("支持 PDF");
  await page.getByLabel("选择上传文件").setInputFiles({
    name: "空白.md",
    mimeType: "text/markdown",
    buffer: Buffer.from("  \n "),
  });
  await page.getByRole("button", { name: "开始上传" }).click();
  const row = page.getByRole("row").filter({ hasText: "空白.md" });
  await expect(row).toContainText("处理失败");
  await row.getByRole("button", { name: "重试 空白.md" }).click();
  await expect(row).toContainText("尝试 2 次");
});

test("PDF 原文件、知识库、权限与删除结果在刷新后保留", async ({ page }) => {
  await demo(page);
  await page.getByRole("button", { name: "新建知识库" }).click();
  await page.getByLabel("知识库名称", { exact: true }).fill("持久化测试");
  await page.getByRole("button", { name: "创建知识库", exact: true }).click();
  await expect(page.locator(".composer-bottom")).toContainText("持久化测试");
  const kb = await page.getByLabel("当前知识库").inputValue();
  await page.getByRole("link", { name: "成员权限" }).click();
  await page.getByLabel("成员账号", { exact: true }).fill("bob");
  await page.getByRole("button", { name: "授予读取权限" }).click();
  await expect(page.locator(".member-row")).toHaveCount(2);
  await page.getByRole("link", { name: "文档管理" }).click();
  await page.getByRole("button", { name: "上传文档", exact: true }).click();
  // 包含二进制字节，验证 IndexedDB 保存的是原文件，而不只是文件名或示例文本。
  const bytes = Buffer.concat([
    Buffer.from("%PDF-1.7\n"),
    Buffer.from([0, 255, 128]),
    Buffer.from("\n%%EOF"),
  ]);
  await page.getByLabel("选择上传文件").setInputFiles({
    name: "企业资料.pdf",
    mimeType: "application/pdf",
    buffer: bytes,
  });
  await page.getByRole("button", { name: "开始上传" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  // 不等待 READY 就刷新，验证处理中的状态也能恢复。
  await page.reload();
  await page.getByRole("button", { name: "体验交互演示" }).click();
  await expect(page.getByLabel("当前知识库")).toHaveValue(kb);
  const row = page.getByRole("row").filter({ hasText: "企业资料.pdf" });
  await expect(row).toContainText("已就绪");
  const downloading = page.waitForEvent("download");
  await row.getByRole("button", { name: "下载 企业资料.pdf" }).click();
  const download = await downloading;
  expect(download.suggestedFilename()).toBe("企业资料.pdf");
  expect(await readFile((await download.path())!)).toEqual(bytes);
  await page.getByLabel("切换身份").selectOption("bob");
  await expect(row).toBeVisible();
  await expect(
    page.getByRole("button", { name: "上传文档", exact: true }),
  ).toHaveCount(0);
  await page.getByLabel("切换身份").selectOption("alice");
  await row.getByRole("button", { name: "删除 企业资料.pdf" }).click();
  await page.getByRole("button", { name: "确认删除" }).click();
  await expect(row).toHaveCount(0);
  await page.reload();
  await page.getByRole("button", { name: "体验交互演示" }).click();
  await expect(page.getByLabel("当前知识库")).toHaveValue(kb);
  await expect(page.getByText("知识从第一份资料开始")).toBeVisible();
});
test("授权、撤权、切换账号及只读操作限制", async ({ page }) => {
  await demo(page);
  await page.getByRole("link", { name: "成员权限" }).click();
  await page.getByRole("button", { name: "撤销权限", exact: true }).click();
  await page.getByRole("button", { name: "确认撤销" }).click();
  await expect(page.locator(".member-row")).toHaveCount(1);
  await page.getByLabel("切换身份").selectOption("bob");
  await expect(page.getByText("建立你的第一个知识空间")).toBeVisible();
  await page.getByLabel("切换身份").selectOption("alice");
  await page.getByLabel("成员账号", { exact: true }).fill("bob");
  await page.getByRole("button", { name: "授予读取权限" }).click();
  await expect(page.locator(".member-row")).toHaveCount(2);
  await page.getByLabel("切换身份").selectOption("bob");
  await expect(page.getByText("你拥有此知识库的读取权限")).toBeVisible();
  await page.getByRole("link", { name: "文档管理" }).click();
  await expect(
    page.getByRole("button", { name: "上传文档", exact: true }),
  ).toHaveCount(0);
  await expect(page.getByRole("button", { name: /^删除 / })).toHaveCount(0);
});
test("移动端布局与对话框键盘关闭，不把凭据或对话写入持久存储", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await demo(page);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "test-results/mobile-workspace.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "新建知识库" }).click();
  await expect(page.getByLabel("知识库名称", { exact: true })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "新建知识库" })).toBeFocused();
  expect(
    await page.evaluate(() => localStorage.length + sessionStorage.length),
  ).toBe(0);
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(page.getByRole("heading", { name: "欢迎回来" })).toBeVisible();
});
test("真实连接模式展示登录错误、携带 CSRF 并显示服务失败，拒绝渲染答案中的脚本", async ({
  page,
}) => {
  let authenticated = false;
  let logins = 0;
  let asked = 0;
  let protectedRequest = false;
  await page.route(
    (url) => url.pathname.startsWith("/api/"),
    async (route) => {
      const req = route.request();
      const path = new URL(req.url()).pathname;
      const json = (data: unknown, status = 200) =>
        route.fulfill({
          status,
          contentType: "application/json",
          body: JSON.stringify(data),
        });
      if (path === "/api/me") {
        if (!authenticated) return json({}, 401);
        return json({ id: "alice", username: "alice", tenantId: "test" });
      }
      if (path === "/api/login") {
        if (++logins === 1) return json({}, 401);
        authenticated = true;
        return route.fulfill({ status: 204 });
      }
      if (path === "/api/logout") {
        authenticated = false;
        return route.fulfill({ status: 204 });
      }
      if (path === "/api/csrf")
        return route.fulfill({
          contentType: "application/json",
          headers: {
            "Set-Cookie": "XSRF-TOKEN=test-cookie; Path=/; SameSite=Lax",
          },
          body: JSON.stringify({
            headerName: "X-XSRF-TOKEN",
            token: "test-token",
          }),
        });
      if (path === "/api/knowledge-bases")
        return json([
          {
            id: "kb",
            name: "真实接口测试",
            description: "",
            ownerId: "alice",
            tenantId: "test",
            createdAt: new Date().toISOString(),
          },
        ]);
      if (path.endsWith("/documents")) return json([]);
      if (path.endsWith("/questions")) {
        protectedRequest =
          req.headers()["x-xsrf-token"] === "test-token" &&
          !req.headers()["authorization"] &&
          req.headers()["cookie"]?.includes("XSRF-TOKEN=test-cookie");
        asked++;
        if (asked === 1) return json({ detail: "模型服务暂不可用" }, 503);
        return json({
          status: "ANSWERED",
          answer: "<script>window.injected=true</script>\n\n安全的回答。",
          sources: [],
          elapsedMs: 100,
        });
      }
      return json({}, 404);
    },
  );
  await page.goto("/");
  await page.getByLabel("账号", { exact: true }).fill("alice");
  await page.getByLabel("密码", { exact: true }).fill("test-password");
  await page.getByRole("button", { name: "登录工作台", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("用户名或密码错误");
  await page.getByRole("button", { name: "登录工作台", exact: true }).click();
  await page.getByRole("textbox", { name: "输入问题" }).fill("测试问题");
  await page.getByRole("button", { name: "发送问题" }).click();
  await expect(page.getByRole("alert")).toContainText("模型服务暂不可用");
  expect(protectedRequest).toBe(true);
  await page.getByRole("textbox", { name: "输入问题" }).fill("再次提问");
  await page.getByRole("button", { name: "发送问题" }).click();
  await expect(page.locator(".markdown")).toContainText("安全的回答。");
  expect(
    await page.evaluate(
      () => (window as Window & { injected?: boolean }).injected,
    ),
  ).toBeUndefined();
  await page.reload();
  await expect(page.getByRole("button", { name: "退出登录" })).toBeVisible();
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(page.getByRole("heading", { name: "欢迎回来" })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("heading", { name: "欢迎回来" })).toBeVisible();
});
