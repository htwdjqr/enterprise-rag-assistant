# HTTP 接口使用说明

基础地址：`http://localhost:8080`。业务接口支持浏览器 Cookie 会话或脚本 HTTP Basic 认证。

## 身份认证与 CSRF

演示账号 `alice` / `bob` 的密码由 `scripts/init-env.ps1` 随机写入 `.env`，不在此文档提供固定值。

所有写请求（POST / DELETE）都需要：

1. 登录后的会话 Cookie，或脚本使用的 `Authorization: Basic ...`。
2. 先调用 `GET /api/csrf`，保存响应 Cookie。
3. 用响应中的 `headerName` 作为请求头名，用 `token` 作为值，并附带上述 Cookie。

浏览器先匿名获取 `GET /api/csrf`，携带 Cookie 和 CSRF 请求头，以 `application/x-www-form-urlencoded` 提交 `POST /api/login` 的 `username`、`password`。成功返回 204 并建立 HttpOnly 会话 Cookie，错误密码返回 401，缺少 CSRF 返回 403。登录后重新获取 CSRF token；刷新时调用 `/api/me` 恢复身份。`POST /api/logout` 携带 CSRF 后销毁会话并返回 204。会话空闲 8 小时过期，应用重启后需重新登录；非本机部署须配置 HTTPS 和 Secure Cookie。`scripts/demo.ps1` 仍使用 HTTP Basic。

## 接口列表

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/me` | 已认证 | 当前用户 ID、用户名与租户 |
| GET | `/api/csrf` | 公开 | 获取写请求所需的 CSRF token |
| POST | `/api/login` | 公开，需 CSRF | 表单登录，创建会话 |
| POST | `/api/logout` | 需 CSRF | 销毁会话并清除 Cookie |
| GET | `/api/knowledge-bases` | 已认证 | 可读知识库，最多返回 200 条 |
| POST | `/api/knowledge-bases` | 已认证 | 创建知识库，返回 201 |
| POST | `/api/knowledge-bases/{id}/members` | 创建人 | 授予同租户用户读取权限，返回 204 |
| GET | `/api/knowledge-bases/{id}/members` | 创建人 | 返回创建人和成员的 id、username、role（OWNER / READER），不包含密码字段 |
| DELETE | `/api/knowledge-bases/{id}/members/{username}` | 创建人 | 撤销成员读取权限，返回 204；创建人的固有权限不受影响 |
| POST | `/api/knowledge-bases/{id}/documents` | 创建人 | multipart 上传，字段名 file，返回 202 |
| GET | `/api/knowledge-bases/{id}/documents?page=0&size=20` | 可读 | 分页文档列表，不包含 DELETED |
| GET | `/api/documents/{id}` | 可读 | 查询处理状态，含失败信息与尝试次数 |
| GET | `/api/documents/{id}/file` | 可读 | 下载原文件；删除中的文档不可下载 |
| POST | `/api/documents/{id}/retry` | 创建人 | 只接受 FAILED / DELETE_FAILED，返回 202 |
| DELETE | `/api/documents/{id}` | 创建人 | 标记删除，后台清理，返回 202 |
| POST | `/api/knowledge-bases/{id}/questions` | 可读 | 提问，返回答案与来源 |

本工程没有公开用户注册接口；成员授权只能使用已由管理员创建的账号。

## 请求与响应示例

创建知识库：

```json
{"name": "员工制度", "description": "差旅、休假和 IT 支持资料"}
```

授权成员：

```json
{"username": "bob"}
```

提问（question 必填，最多 2000 字符）：

```json
{"question": "出差住宿每天最多报销多少钱？"}
```

回答示意（来源 UUID、文本和分数以实际运行为准）：

```json
{
  "status": "ANSWERED",
  "answer": "国内出差住宿上限为每人每天 500 元。[1]",
  "sources": [
    {
      "reference": 1,
      "documentId": "服务端生成的文档 UUID",
      "filename": "employee-handbook.md",
      "chunkIndex": 0,
      "score": 0.82,
      "excerpt": "国内出差的住宿报销上限为每人每天 500 元……",
      "downloadUrl": "/api/documents/服务端生成的文档 UUID/file"
    }
  ],
  "elapsedMs": 1250
}
```

无证据时返回 HTTP 200，`status = NO_EVIDENCE` 且 sources 为空。模型自己判断证据不足而拒答时，状态仍可能是 ANSWERED，含义是“模型调用成功”，不是“事实已核验”。

## 状态码

- 400：缺少字段、无效参数、空文件或不支持的扩展名。
- 401：未登录或凭据错误。
- 403：缺少 / 错误的 CSRF token，或只有读取权限却执行管理操作。
- 404：资源不存在，或当前用户无读取权限；避免泄露资源是否存在。
- 409：文档当前状态不允许重试，或回答期间资料已变化。
- 413：上传文件超过 20 MB。
- 503：Embedding / Qdrant / 聊天模型不可用。

Controller 层错误采用 ProblemDetail；认证与 CSRF 错误由 Spring Security 处理，响应体形式可能不同。

当前为一次性非流式问答，每次只使用本次问题，不保存和拼接聊天历史。
