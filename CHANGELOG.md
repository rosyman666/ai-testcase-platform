# Changelog

本项目的所有重要变更都记录在此文件。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- 系统架构设计文档：分层结构、17 张表设计、47 个 REST 接口与 1 个 SSE 端点、14 个可独立验收的任务及依赖图
- 开发日志与项目全生命周期路线图
- 仓库基础设施：`.gitignore` / `.gitattributes` / `.editorconfig` / MIT 许可证
- 模型接入的环境变量模板 `.env.example`（支持 mock 与 openai-compatible 两种 provider）
- **T01 工程骨架**：Spring Boot 3.3.5 + Java 17 + MyBatis-Plus 3.5.9 后端骨架，`GET /api/health` 健康检查（统一响应体 + `X-Trace-Id`）
- **数据库版本化**：Flyway 10.17 迁移，17 张表（15 业务 + 2 字典）与字典种子数据
- **前端骨架**：Vite 5 + React 18 + TypeScript + MUI + Tailwind（`preflight: false` 共存），`/api` 代理到 8080
- README 补齐环境要求与四步快速开始（含 `java -jar` 的端口注意事项）
