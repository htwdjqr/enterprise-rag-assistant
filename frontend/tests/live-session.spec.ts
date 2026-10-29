import { test, expect } from "@playwright/test";
import { readFile } from "node:fs/promises";

// 仅在显式开启且真实后端已运行时执行；不创建业务数据、不调用模型、不输出凭据。
test("真实会话：登录后刷新恢复、HttpOnly Cookie、退出及过期后不能恢复", async ({
  page,
  context,
}) => {
  test.skip(
    process.env.LIVE_SESSION_TEST !== "1",
    "需先启动真实后端，再设置 LIVE_SESSION_TEST=1",
  );
  const env = await readFile(new URL("../../.env", import.meta.url), "utf8");
  const password = env.match(/^BOOTSTRAP_ALICE_PASSWORD=(.*)$/m)?.[1].trim();
  if (!password) throw new Error("缺少本地 alice 初始化密码");
  async function login() {
    await page.getByLabel("账号", { exact: true }).fill("alice");
    await page.getByLabel("密码", { exact: true }).fill(password!);
    await page.getByRole("button", { name: "登录工作台", exact: true }).click();
    await expect(page.getByRole("button", { name: "退出登录" })).toBeVisible();
  }
  await page.goto("/");
  await login();
  const cookie = (await context.cookies()).find(
    (item) => item.name === "JSESSIONID",
  );
  expect(cookie?.httpOnly).toBe(true);
  expect(cookie?.sameSite).toBe("Lax");
  await page.reload();
  await expect(page.getByRole("button", { name: "退出登录" })).toBeVisible();
  expect(
    await page.evaluate(() => localStorage.length + sessionStorage.length),
  ).toBe(0);
  expect((await page.request.get("/api/me")).status()).toBe(200);
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(page.getByRole("heading", { name: "欢迎回来" })).toBeVisible();
  expect((await page.request.get("/api/me")).status()).toBe(401);
  // 旧 Cookie 的服务端会话也必须失效，不能只靠前端删 Cookie 完成退出。
  const oldSession = await page.request.get("/api/me", {
    headers: { Cookie: `JSESSIONID=${cookie!.value}` },
  });
  expect(oldSession.status()).toBe(401);
  await page.reload();
  await expect(page.getByRole("heading", { name: "欢迎回来" })).toBeVisible();
  await login();
  await context.clearCookies({ name: "JSESSIONID" });
  await page.reload();
  await expect(page.getByRole("heading", { name: "欢迎回来" })).toBeVisible();
});
