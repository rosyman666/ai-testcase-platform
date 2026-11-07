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
- **T02 通用基础设施与鉴权闭环**：`ErrorCode`（12 个错误码）/ `BizException` / `GlobalExceptionHandler` 统一错误语义；`PageQuery` + `PageResult` 分页契约；11 个枚举收敛到 `common/enums`（`CodedEnum` 单一真源）；`auth` 模块（登录、Token、拦截器、`AuthContext`）与种子账号 `admin`；`AuditMetaObjectHandler` 自动填充审计字段；`config/AsyncConfig` 有界线程池与 `AiProperties` 模型配置
- **前端登录闭环**：`api/http.ts` 统一请求出口（`code !== 0` 拦截、`Authorization` 注入、统一错误提示）、`api/system.ts`、登录态 `store`、`pages/Login.tsx`、路由表 `/login` `/` `*`

### Fixed

- **`ApiResponse.isSuccess()` 污染响应体**：Jackson 按 bean getter 规则把 `isSuccess()` 序列化成 `"success": true`，使响应体从 4 字段变 5 字段，破坏接口契约。删除该方法，判成功一律用 `code == 0`
- **`NoResourceFoundException` 被映射成 50000**：Spring 6.1 起 404 改抛 `NoResourceFoundException`（不再是 `NoHandlerFoundException`），全局处理器未覆盖，访问不存在路径返回"系统内部错误"。补映射到 `40401`
- **`Instant` 序列化出 9 位纳秒**：`Instant.toString()` 的小数位随精度浮动，无法被前端 `dayjs` 稳定解析。改为固定 3 位毫秒 + `Z`
- **`IdGen` 重启后必撞唯一键**（`D-17`）：进程内计数器重启归零，而 `uk_doc_source_doc_no` 等唯一索引不看逻辑删标记，导致重启后首次上传必然 `DuplicateKeyException` → `50000`。改为「播种 + 重试」：按 `前缀+日期` 隔离计数，用裸 `JdbcTemplate`（**不带 `deleted = 0`**）查当日最大序号播种，撞键后重新播种而非 `++`，连续 3 次撞键或当日达 6 位上限均抛 `40901`
