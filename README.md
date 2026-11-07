# AI Testcase Platform

![status](https://img.shields.io/badge/status-design--stage-yellow)
![java](https://img.shields.io/badge/Java-17-orange)
![spring](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F)
![react](https://img.shields.io/badge/React-18-61DAFB)
![license](https://img.shields.io/badge/license-MIT-blue)

> 把需求文档 / OpenAPI 文档编译成测试点，用大模型批量生成测试用例，经人工评审形成标注数据，导出 Excel 与 JUnit 骨架，并回传执行结果做失败归因——全流程带采纳率、驳回分布、token 成本等可量化指标。

一个「AI 真正落地」而不是「套个聊天框」的项目：模型被当作**会失败的远程调用**来治理，而不是当成一个函数来调用。

---

## 解决什么问题

| 环节 | 人工现状 | 本项目的做法 |
|---|---|---|
| 读文档拆测试点 | 逐页看完，靠经验，漏测难以发现 | 结构化切分（OpenAPI 按 operation、文本按 section）后逐点抽取，可回溯到原文位置 |
| 写用例 | 复制粘贴改，格式不统一 | 模板 + JSON Schema 约束输出，结构化落库 |
| 用例质量 | 无法度量 | 评审动作即标注，采纳率 / 编辑率 / 驳回原因分布 |
| 成本 | 黑盒 | 每次调用记账，四级归集到文档粒度 |

## 技术栈

- **后端**：Java 17 · Spring Boot 3.3 · MyBatis-Plus · MySQL 8 · Flyway
- **前端**：Vite · React 18 · TypeScript · MUI · Tailwind CSS · TanStack Query
- **AI**：provider 抽象层（`mock` 离线可跑 / `openai-compatible` 接真实模型）
- **无外部中间件**：队列用 MySQL 表 + `FOR UPDATE SKIP LOCKED`，进度用 SSE

## 目录结构

```
ai-testcase-platform/
├── docs/            设计与过程文档（架构、接口、运行手册、开发日志）
├── backend/         Spring Boot 服务（待 T01 创建）
└── frontend/        React 前端（待 T01 创建）
```

## 文档索引

| 文档 | 内容 |
|---|---|
| [docs/01-architecture.md](docs/01-architecture.md) | 系统架构设计 + 14 个任务的分解、依赖图、验收标准 |
| [docs/JOURNAL.md](docs/JOURNAL.md) | 开发日志：每一步做了什么、为什么这么做、踩了什么坑 |

## 快速开始

> T01（工程骨架）完成后补充。目标：起 MySQL → `mvn spring-boot:run` → `npm run dev`，三步跑通。

## 当前进度

> 项目处于**设计阶段**：架构已定稿，代码尚未开始。开发顺序与验收标准见 [docs/01-architecture.md](docs/01-architecture.md) §6。

- [x] 选题与可行性评估
- [x] 系统架构设计与任务分解（14 个任务，关键路径 10 节点）
- [x] T01 工程骨架与基础设施
- [ ] T02 通用基础设施与鉴权闭环
- [ ] …（共 14 个任务，见架构文档任务列表）

## 环境要求

| 依赖 | 版本 |
|---|---|
| JDK | 17+ |
| Maven | 3.9+ |
| Node.js | 20+ |
| MySQL | 8.0+（异步任务依赖 `FOR UPDATE SKIP LOCKED`，5.7 不可用） |

## 快速开始

```bash
# 1. 建库（只需一次）
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS ai_testcase DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

# 2. 配置：复制模板后填数据库密码；AI_API_KEY 可留空，此时 provider 用 mock 也能跑通全链路
cp .env.example .env

# 3. 启动后端（Flyway 会自动建 17 张表）
cd backend && mvn spring-boot:run
curl http://localhost:8080/api/health        # 期望 {"code":0,...}

# 4. 启动前端（另开一个终端）
cd frontend && npm install && npm run dev    # http://localhost:5173，/api 已代理到 8080
```

> **打包产物的启动方式**：`java -Dserver.port=8080 -jar backend/target/ai-testcase-platform.jar`。
> 必须显式带 `-Dserver.port`——部分沙箱/IDE 会注入 `SERVER__PORT` 环境变量，Spring 的宽松绑定会把它映射成 `server.port` 且优先级高于配置文件，导致端口漂移、前端 proxy 失效。正常部署机无此变量，可用 `SERVER_PORT` 控制。

## 许可证

[MIT](LICENSE)
