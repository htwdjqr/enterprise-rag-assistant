import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import type { ProxyOptions } from "vite";

/** 开发时由 Vite 同源代理 API，浏览器不直接跨域访问后端，CSRF Cookie 也可正常传递。 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const proxy: Record<string, ProxyOptions> = {
    "/api": {
      target: env.API_PROXY_TARGET || "http://127.0.0.1:8080",
      changeOrigin: true,
      configure(server) {
        // Vite 默认把代理连接失败返回为空白 500，页面会误显示成业务处理异常。
        // 明确返回服务不可用；不把底层错误、请求头或认证凭据发送到页面。
        server.on("error", (_error, _request, response) => {
          if (!("writeHead" in response) || response.headersSent) return;
          response.writeHead(503, {
            "Content-Type": "application/problem+json; charset=utf-8",
          });
          response.end(
            JSON.stringify({
              detail:
                "无法连接后端服务，请先启动 Spring Boot 后端并检查 API 代理地址。仅体验界面可选择“体验交互演示”。",
            }),
          );
        });
      },
    },
  };
  return {
    plugins: [react()],
    server: { port: 5173, strictPort: true, proxy },
    preview: { proxy },
  };
});
