# 后端工程

本目录是独立的 Java 21 / Spring Boot / Maven 工程，IDE 请导入本目录的 `pom.xml`。

- 完整配置、启动与架构说明：[后端文档](../docs/backend.md)。
- 根目录 `.env` 保存本地密钥；推荐从项目根目录运行 `scripts/run-local.ps1`，脚本会切换到本目录并加载配置。
- 在本目录执行 `mvn -B -ntp test` 或 `mvn -B -ntp package`；集成测试执行 `mvn -B -ntp -Pintegration verify`，需要 Docker。
- 默认上传文件保存在本目录 `data/files/`，构建产物位于 `target/`。
