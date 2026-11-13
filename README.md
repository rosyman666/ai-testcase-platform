# AI Testcase Platform

![status](https://img.shields.io/badge/status-MVP%20in%20progress-orange)
![java](https://img.shields.io/badge/Java-17-orange)
![spring](https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F)
![react](https://img.shields.io/badge/React-18.3-61DAFB)
![license](https://img.shields.io/badge/license-MIT-blue)

> 把需求文档 / OpenAPI 文档编译成测试点，用大模型批量生成测试用例，经人工评审形成标注数据，导出 Excel 与 JUnit 5 骨架——全流程带采纳率、驳回分布、token 成本等可量化指标。

一个「AI 真正落地」而不是「套个聊天框」的项目：模型被当作**会失败的远程调用**来治理，而不是当成一个函数来调用。

---

## 解决什么问题

| 环节 | 人工现状 | 本项目的做法 |
|---|---|---|
| 读文档拆测试点 | 逐页看完，靠经验，漏测难以发现 | 结构化切分（OpenAPI 按 operation、文本按 section）后逐点抽取，可回溯到原文位置 |
| 写用例 | 复制粘贴改，格式不统一 | 模板 + JSON Schema 约束输出，结构化落库 |
| 用例质量 | 无法度量 | 评审动作即标注，采纳率 / 编辑率 / 驳回原因分布 |
| 成本 | 黑盒 | 每次调用记账，四级归集到文档粒度 |
| 批量产出的可信度 | 不知道哪条能用 | 人工评审沉淀标注数据，编辑率与驳回原因反向定位提示词问题 |

## 当前状态

**T01 ~ T09、T11 已完成并可端到端跑通**（离线 `mock` provider 下全链路无外部依赖）。T10、T14 待做，T12 / T13 在进行中。

| 任务 | 内容 | 状态 |
|---|---|---|
| T01 ~ T02 | 工程骨架、统一响应体、JWT 鉴权闭环 | ✅ |
| T03 | 文档接入与解析（docx / Markdown / OpenAPI） | ✅ |
| T04 | 模型 Provider 抽象层 + 提示词模板 + 调用记账 | ✅ |
| T05 | 测试点抽取（结构化上下文切分 + 去重与取代） | ✅ |
| T06 | 异步任务引擎（`FOR UPDATE SKIP LOCKED`）+ SSE 进度推送 | ✅ |
| T07 | 用例生成编排（退避重试 + 多级降级 + 结构化输出修复） | ✅ |
| T08 | 人工评审与标注数据闭环 | ✅ |
| T09 | 用例导出（Excel 双 sheet + JUnit 5 zip，含真 `javac` 编译校验） | ✅ |
| T10 | 执行结果导入与失败归因 | ⬜ 待做 |
| T11 | 指标与成本看板（5 个聚合端点 + 前端 4 个 Tab） | ✅ |
| T12 | 前端集成与交互打磨（统一布局 / 侧边导航） | 🚧 进行中 |
| T13 | 测试体系（27 个后端测试类 + 5 个前端测试文件） | 🚧 持续补齐 |
| T14 | 演示数据、一键启动脚本 | ⬜ 待做 |

> T10 未完成意味着**执行结果回传与失败归因这一段尚未实现**，当前指标看板展示的是「生成—评审」侧的采纳/成本数据，不含执行通过率。

## 已实现的关键设计

这几处是刻意做深的地方，也是这个项目区别于「调一次 API 展示结果」的部分。

**① 模型失败被分层治理，而不是一个 `try/catch`**

上游错误分为可重试（限流、超时、5xx）与不可重试（鉴权失败、模板缺失）两类，各带独立错误码落库。模型输出不合法时走**三步修复流水线**——剥 markdown 围栏 → 抽平衡片段（或按 `max_tokens` 截断补全）→ 语法归一化（单引号转双引号、裸 key 加引号、丢弃尾逗号），顺序不可交换；修完仍不合 schema 才降级，且「修过」与「没修过」在指标上分开统计，避免解析失败率永远显示 0。item 级错误码从 `LlmException` 透传到台账，避免「上游挂了」和「代码崩了」在库里长得一样。

**② 用例导出的产物是真的能编译的**

`junit.zip` 不是拼字符串——生成的 `GeneratedCase_001.java` 会经 `javac` 真实编译校验，zip 内自带 `Test` / `DisplayName` / `Timeout` 三个注解桩与 runner，解压后无需任何外部依赖即可运行。类名转义交给 JavaPoet 的 `$S`，不在拼装阶段提前转义。

**③ 导出端点的双响应形态**

成功返回二进制流（`Content-Type` + `Content-Disposition` 带文件名），失败返回 `application/json` 的 `ApiResponse`（HTTP 200 + 业务错误码，**不带** `Content-Disposition`）。端点因此不能声明固定的二进制 `produces`，前端靠响应头判别而非状态码。

**④ 指标口径唯一真源，且零值分层显示**

采纳率分母 = 已评审数（`ACCEPTED + EDITED + REJECTED`，**不含 DRAFT**），三者之和恒为 1；分母为 0 时返回数值 `0` 而非 `max(0,1)`。前端对零值分四种情况区别呈现：无数据 → 「暂无评审数据」（**绝不渲染 `0%`**）、成本为 0 但 token 非 0 → 显示 0 并标注单价配置成因、驳回分布 0 行 → 空态而不是空饼图。

**⑤ 时区与 SQL 口径写死**

统计窗口固定 `Asia/Shanghai` 偏移（+8 小时纯算术，不依赖 `ZoneId` / 运行时区域设置），左闭右开 `[from, to+1day)`；日分组用 `DATE(created_at + INTERVAL 8 HOUR)`。所有聚合 SQL 在 `ONLY_FULL_GROUP_BY` 下实测通过（非聚合列进 `GROUP BY` 或 `ANY_VALUE()`，不使用 `SET SESSION sql_mode` 绕过）。

## 技术栈

- **后端**：Java 17 · Spring Boot 3.3.5 · MyBatis-Plus · MySQL 8 · Flyway
- **前端**：Vite 5 · React 18.3 · TypeScript 5.6 · MUI 5 · TanStack Query 5 · Recharts 2
- **AI**：provider 抽象层（`mock` 离线可跑 / `openai-compatible` 接真实模型）
- **无外部中间件**：队列用 MySQL 表 + `FOR UPDATE SKIP LOCKED`，进度用 SSE

## 目录结构

```
ai-testcase-platform/
├── docs/                    设计与过程文档
│   ├── 01-architecture.md   架构、接口契约、数据表、任务分解与验收标准
│   ├── 05-frontend-merge-plan.md
│   ├── 06-incremental-prd-t09-t11.md
│   ├── 07-incremental-design-t09-t11.md
│   └── JOURNAL.md           开发日志：做了什么、为什么、踩了什么坑
├── backend/                 Spring Boot 服务
│   └── src/main/java/com/aittest/
│       ├── aicore/          Provider 抽象、提示词渲染、结构化输出解析
│       ├── auth/            JWT 鉴权
│       ├── doc/             文档接入与解析
│       ├── testpoint/       测试点抽取
│       ├── generation/      异步任务引擎 + 用例生成编排 + SSE
│       ├── testcase/        用例评审
│       ├── export/          Excel / JUnit zip 导出
│       ├── metrics/         指标聚合与成本归集
│       ├── system/          字典、配置、健康检查
│       ├── logging/         请求追踪
│       └── common/          统一响应体、异常处理、错误码
└── frontend/                React 前端
    └── src/
        ├── pages/           文档、测试点、任务、用例、提示词、指标看板
        ├── components/      charts / common
        ├── api/             HTTP 与 SSE 封装
        └── hooks/ store/ types/ utils/
```

## 快速开始

### 环境要求

| 依赖 | 版本 |
|---|---|
| JDK | 17+ |
| Maven | 3.9+ |
| Node.js | 20+ |
| MySQL | 8.0+（异步任务依赖 `FOR UPDATE SKIP LOCKED`，5.7 不可用） |

### 四步跑通

```bash
# 1. 建库（只需一次）
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS ai_testcase DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

# 2. 配置：复制模板后填数据库密码；AI_API_KEY 可留空，此时 provider 用 mock 也能跑通全链路
cp .env.example .env

# 3. 启动后端（Flyway 会自动建表）
cd backend && mvn spring-boot:run
curl http://localhost:8080/api/health        # 期望 {"code":0,...}

# 4. 启动前端（另开一个终端）
cd frontend && npm install && npm run dev    # http://localhost:5173，/api 已代理到 8080
```

访问 `http://localhost:5173` 后用默认账号 **`admin` / `admin123`** 登录（仅本地演示用，上线前必须改），首页可进入五个入口：**需求文档 / 提示词模板 / 生成任务（T07）/ 用例评审（T08）/ 指标与成本看板（T11）**。

> **打包产物的启动方式**：`java -Dserver.port=8080 -jar backend/target/ai-testcase-platform.jar`。
> 建议显式带 `-Dserver.port`——部分沙箱/IDE 会注入 `SERVER__PORT` 环境变量，Spring 的宽松绑定会把它映射成 `server.port` 且优先级高于配置文件，导致端口漂移、前端 proxy 失效。正常部署机无此变量，可用 `SERVER_PORT` 控制。

## 主要端点

共 43 个端点，覆盖 11 个 Controller。几个核心的：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查，返回当前生效的 provider |
| POST | `/api/docs` | 上传需求文档 / OpenAPI（`multipart/form-data`） |
| POST | `/api/testpoints/extract` | 触发测试点抽取（异步，返回 `taskId`） |
| POST | `/api/tasks` | 建用例生成任务 |
| GET | `/api/tasks/{id}/stream` | SSE 进度流（`?token=` 鉴权） |
| GET | `/api/cases` | 用例列表（分页 + 状态筛选） |
| POST | `/api/cases/{id}/review` | 评审：采纳 / 编辑采纳 / 驳回 |
| GET | `/api/export/cases.xlsx` | 导出 Excel（用例 + 评审两个 sheet） |
| GET | `/api/export/junit.zip` | 导出 JUnit 5 工程（含注解桩与 runner） |
| GET | `/api/metrics/overview` | 概览：生成数、采纳率、编辑率、驳回率、成本、耗时分位 |
| GET | `/api/metrics/{acceptance,reject-reasons,cost,quality}` | 趋势 / 驳回分布 / 成本 / 质量 |

完整契约见 [docs/01-architecture.md](docs/01-architecture.md) §4。

## 文档索引

| 文档 | 内容 |
|---|---|
| [docs/01-architecture.md](docs/01-architecture.md) | 系统架构、17 张表 ER 设计、接口契约、指标口径、任务分解与验收标准、实施偏差记录 |
| [docs/06-incremental-prd-t09-t11.md](docs/06-incremental-prd-t09-t11.md) | 用例导出 + 指标看板的增量需求 |
| [docs/07-incremental-design-t09-t11.md](docs/07-incremental-design-t09-t11.md) | 对应的增量设计（端点契约、零值分层、文件清单） |
| [docs/JOURNAL.md](docs/JOURNAL.md) | 开发日志：每一步做了什么、为什么这么做、踩了什么坑 |
| [CHANGELOG.md](CHANGELOG.md) | 变更记录与已知问题 |

## 开发约定

- **统一响应体**：所有接口返回 `{code, message, data, traceId}`，`data` 字段始终存在（可为 `null`），不靠 HTTP 状态码区分业务成败。
- **ID 序列化**：Long 型主键统一序列化为 JSON 字符串（超出 JS 安全整数范围），前端对应字段声明为 `string`，参与计算时显式 `Number(...)`。
- **数据库变更**：只通过 Flyway 迁移脚本，不手改线上表结构。
- **错误码**：五段式编号——`40xxx` 参数与鉴权（含 `409xx` 冲突）、`42xxx` 文档与生成、`43xxx` 任务状态、`50xxx` 上游模型与内部错误。新增错误码必须同时在文档登记。

## 许可证

[MIT](LICENSE)
