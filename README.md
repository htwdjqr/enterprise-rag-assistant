# 企业信息问答助手

Java RAG 学习工程：Spring Boot + Spring AI + MySQL + Qdrant，智普 Embedding-3 负责向量化，DeepSeek 负责回答，React 前端提供知识库、文档、权限和问答工作台。

## 目录

```text
enterprise-rag-assistant/
├─ backend/                 Java 后端工程，IDE 导入此目录的 pom.xml
│  ├─ src/                  Java 源码、资源、迁移与测试
│  ├─ pom.xml
│  ├─ Dockerfile
│  ├─ data/files/            本地运行时上传的原始文件
│  ├─ logs/                  历史验证日志
│  └─ target/                Maven 构建产物
├─ frontend/                React + TypeScript + Vite 前端工程
│  ├─ src/
│  ├─ tests/
│  ├─ public/
│  ├─ package.json
│  └─ Dockerfile
├─ docs/                    使用、接口、架构与测试资料
│  ├─ backend.md             后端配置、启动及架构说明
│  ├─ frontend.md            前端功能、认证及部署说明
│  ├─ api.md                 HTTP 接口契约
│  ├─ learning-guide.md      RAG 学习指南
│  ├─ design-system/         前端设计说明
│  └─ examples/              Markdown、TXT、Word、PDF 测试资料
├─ scripts/                 初始化、后端启动、演示与资料生成脚本
├─ compose.yaml             统一编排 MySQL、Qdrant、后端与前端
├─ .env                     本地私密配置，不提交
└─ .env.example             配置模板
```

## 本地启动

以下命令从本文件所在的项目根目录执行。需要 Java 21、Maven、Node.js 22.12+、PowerShell 7.2+ 和 Docker。

```powershell
# 首次使用且 .env 不存在时执行；随后填写两个平台的 API Key。
pwsh -File ./scripts/init-env.ps1

# 已有 .env 时直接从这里开始。不要覆盖现有密码。
docker compose up -d mysql qdrant
pwsh -File ./scripts/run-local.ps1 -JavaHome 'D:\path\to\jdk-21'
```

另开一个终端启动前端：

```powershell
cd frontend
npm ci
npm run dev
```

访问 http://127.0.0.1:5173 。真实登录使用根目录 `.env` 中的 `BOOTSTRAP_ALICE_PASSWORD` 或 `BOOTSTRAP_BOB_PASSWORD`；也可使用无需后端的交互演示。

脚本会读取根目录 `.env` 并在 `backend/` 内启动后端，因此默认原文件位置是 `backend/data/files/`。直接从 IDE 或 JAR 启动时，也应将工作目录设置为 `backend/` 并加载根目录配置。重启后端后需要重新登录，正常刷新页面可恢复会话。

## 构建与部署

```powershell
# 在根目录构建后端；也可进入 backend/ 后执行 mvn 命令。
mvn -f backend/pom.xml -B -ntp package

# 在根目录构建和测试前端。
npm --prefix frontend run build
npm --prefix frontend test

# 可选：用 Docker 一起部署前后端，访问 http://localhost:8081。
docker compose --profile app up -d --build frontend
```

本地进程与 Docker 部署使用不同原文件存储位置；切换方式前请阅读[后端说明](docs/backend.md)，避免已有文档无法下载。不要用 `docker compose down -v` 清理已有学习数据。

详细文档：[后端](docs/backend.md) · [前端](docs/frontend.md) · [接口](docs/api.md) · [学习指南](docs/learning-guide.md) · [人事制度测试资料](docs/examples/hr-test-data/测试问题与预期答案.md)。
