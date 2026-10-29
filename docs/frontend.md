# 知序 · 企业知识助手前端

独立的 **React 19 + TypeScript + Vite 7** 工程，用于演示 Java RAG 后端的完整业务流程。页面与 API 适配器分离，关键代码有中文注释。

## 启动

前端工程位于项目根目录的 `frontend/`，本文件集中保存前端说明。需要 Node.js **22.12+**（推荐 Node 22 LTS）。从项目根目录执行：

```powershell
cd frontend
npm ci
npm run dev
```

打开 **http://127.0.0.1:5173**。

- **真实连接**：先按[后端说明](backend.md)启动后端，再使用后端已有账号登录。`alice` / `bob` 的初始化密码在项目根目录 `.env` 中。登录后调用真实 API，上传和问答会触发后端的智普 / DeepSeek 调用。
- **交互演示**：点击登录页的“体验交互演示”，无需后端或 API Key。页面常驻“示例模式”说明，提供虚构手册和模拟回答，可切换 alice / bob 演示权限。

不要把根目录 `.env` 复制到 `frontend/`，也不要将云模型密钥放进 `VITE_*` 变量。

默认 Vite 将 `/api` 同源代理到 `http://127.0.0.1:8080`。后端端口不同时，把 `frontend/.env.example` 复制为 `frontend/.env.local`，修改 `API_PROXY_TARGET` 后重启 Vite。

## 可演示的功能

1. 账号登录 / 退出、密码显示切换、错误提示。
2. 知识库列表、切换、新建与简介。
3. 文档选择或拖拽上传、大小/类型检查、每 3 秒自动刷新状态、分页、失败重试、受保护下载和删除确认。
4. 独立问题输入、等待反馈、Markdown 回答、无证据提示、来源原文展开及来源文件下载。
5. 创建人查看成员、授予读取权限、撤销权限；成员只能提问、查看和下载。
6. 移动端布局、可键盘访问的弹窗和表单、页面加载与服务失败状态。

每次问答独立检索；界面中的对话记录仅在当前页面内存中，不是后端多轮会话。切换知识库会清空当前对话并取消等待旧回答，避免显示错库答案。

### 推荐演示路线

1. 进入交互演示，在默认员工制度库询问“出差住宿每天最多报销多少钱？”。
2. 展开来源，下载原手册，询问“今年奖金多少？”观察资料不足提示。
3. 在“文档管理”上传一份非空 Markdown，观察等待处理 → 已就绪；上传只含空格的 Markdown 可观察失败与重试。
4. 新建一个知识库，验证切换后没有旧库的问答记录。
5. 返回默认库，在“成员权限”撤销 bob，再通过顶端切换身份观察知识库消失。
6. 切回 alice 重新授权 bob；切换至 bob 后，可以查看资料，但不能上传、删除或管理成员。

示例模式只是在浏览器内模拟工作流：不解析 PDF/Word、不向量化、不调用模型、不访问生产数据。问答只从示例内容中按少量主题摘录，不是 RAG 算法。知识库、成员权限、文档状态和上传的原文件保存在当前浏览器的 IndexedDB 中，刷新后再次点击“体验交互演示”即可继续查看和下载；处理中的任务会根据保存的时间继续完成。登录凭据与聊天记录不保存。之前已因刷新丢失的文件需要重新上传。

数据只属于当前浏览器和网站地址：`localhost` 与 `127.0.0.1`、不同端口或浏览器之间不共享。清除站点数据、无痕窗口关闭或浏览器清理存储可能使数据丢失。可在文档列表删除不需要的文件；要恢复初始演示，在浏览器开发者工具 Application → IndexedDB 中删除 `zhixu-interactive-demo` 数据库后刷新。

## 目录与阅读顺序

以下路径均相对 `frontend/`。设计说明位于 [design-system/zhixu/MASTER.md](design-system/zhixu/MASTER.md)，不参与前端构建。

```text
src/
  types.ts                 与 Java DTO 对齐的类型
  api/http.ts              Cookie 会话、登录恢复、CSRF、超时和文件下载
  api/demo.ts              演示业务与权限检查
  api/demo-storage.ts      IndexedDB 事务与文件持久化（不保存凭据）
  App.tsx                  登录状态、知识库选择、路由与整体布局
  hooks.ts                 轮询与过期响应处理、下载辅助函数
  components/              登录、知识库创建、对话框、状态提示
  pages/AskPage.tsx         问答、来源及下载
  pages/DocumentsPage.tsx   上传、处理状态、分页、重试和删除
  pages/MembersPage.tsx     权限查看、授权和撤权
  styles.css               颜色、组件和布局
  styles-responsive.css    移动端覆盖规则
tests/workspace.spec.ts    浏览器端操作流程验证
```

真实模式与示例模式实现相同的 `Api` 接口，因此页面只处理业务交互。新增后台接口时，先更新 `types.ts` 和 `api/http.ts`，再实现界面。

## 认证与请求说明

- 密码仅用于 `POST /api/login`，不写入 localStorage / sessionStorage 或日志。后端创建会话，浏览器通过 HttpOnly、SameSite=Lax 的 `JSESSIONID` Cookie 识别身份。
- 登录前获取 `/api/csrf`，提交表单格式的用户名和密码；登录成功后重新获取 CSRF token。刷新时通过 `/api/me` 恢复身份，不要求重新输入密码。
- 会话空闲 8 小时后失效；重启后端也会清除内存会话。退出时调用 `POST /api/logout` 销毁服务端会话，再清理页面状态。部署到非本机环境需启用 HTTPS 和 Secure Cookie。
- 写请求附带 CSRF token，浏览器同源发送 Cookie。脚本客户端仍可使用 HTTP Basic。
- Vite 开发代理与 Nginx 部署代理保持前后端同源；不要为了开发方便关闭后端 CSRF。
- 文件上传使用 FormData，由浏览器生成 multipart boundary。文件下载先鉴权获取 Blob，再触发浏览器下载。
- 模型回答由 ReactMarkdown 渲染，不启用原始 HTML；来源中的下载 URL 不直接作为外部链接使用，而是根据文档 ID 调用受保护接口。
- 页面中的权限限制便于操作，真正的权限判断仍由 Java 后端执行。

## 构建与测试

在 `frontend/` 目录执行：

```powershell
npm run build
npm test

# 首次运行需要浏览器；已有 Playwright 对应浏览器可直接测试。
npx playwright install chromium
npm run test:e2e
```

单元测试验证 API 请求、CSRF、空响应、错误提示与退出后的凭据清理。浏览器测试覆盖问答、来源、下载、文档状态、重试、删除、授权与撤权、移动端、键盘焦点和不安全 HTML。真实连接模式的浏览器测试拦截 HTTP 响应，不调用云模型；Java 集成测试验证真实 MySQL/Qdrant 及成员列表权限。

## Docker 部署

先准备好后端配置，在工程根目录运行：

```powershell
docker compose --profile app up -d --build frontend
```

打开 **http://localhost:8081**。Nginx 提供前端并代理 `/api` 到 `app:8080`，依赖服务自动启动。可用 `FRONTEND_PORT` 修改宿主机端口。应用启动完成前可能暂时出现连接失败，稍后重试即可。

独立部署 `dist` 时也必须配置 `/api` 反向代理和 SPA 路由回退；不能直接双击 `dist/index.html`。
