# 开发日志

> 记录原则：**每完成一个可验收的步骤就追加一条**，不写"今天写了很多代码"这种流水账。每条必须能回答四个问题：做了什么、为什么这么做（决策与取舍）、踩了什么坑、下一步是什么。
>
> 这份日志有两个用途：① 三个月后的你能快速回忆起设计意图；② 面试被问"这个项目怎么来的"时，你有真实的演进过程可讲。

---

## 项目全生命周期路线图

一个能上简历的项目，通常要走完这 6 个阶段。当前处于阶段 2 结束、阶段 3 开始。

| 阶段 | 名称 | 产出 | 状态 |
|---|---|---|---|
| 0 | 选题与可行性判断 | 一句话价值主张、技术难点清单 | ✅ 完成 |
| 1 | 需求收敛（MVP 边界） | 核心闭环、模块清单、明确不做什么 | ✅ 完成 |
| 2 | 架构设计 | 分层、表设计、接口契约、任务分解、依赖清单 | ✅ 完成 |
| 3 | 骨架与基础设施 | 项目能起来、能连库、能鉴权、能跑测试 | ⬜ 进行中 |
| 4 | 纵向切片开发 | 每个任务端到端可验收，按依赖顺序推进 | ⬜ 未开始 |
| 5 | 质量与交付 | 单元/集成测试、演示数据、README、运行手册 | ⬜ 未开始 |

---

## 2026-11-07 · 阶段 0-2

### 做了什么

1. **确定选题**：AI 测试用例生成与评审平台。在三个候选（本项目 / RAG 知识库问答 / 日志根因分析）中选定，理由是**评估指标天然存在**——评审动作本身产生标注数据，不需要硬造准确率。
2. **确定系统形态**：Web 应用（多模块前后端分离），排除单页工具。判据是业务存在「生成 → 评审 → 导出 → 回传分析」多阶段流转，单页装不下状态与数据。
3. **确定技术栈**：Java 17 + Spring Boot 3.3 + MyBatis-Plus + MySQL 8；前端 Vite + React 18 + TS + MUI + Tailwind。约束：不引 Redis / MQ，保持单机可跑。
4. **完成架构设计**（`docs/01-architecture.md`，1275 行）：
   - 分层 controller → service → domain / aicore / infra，按业务垂直切 7 个包
   - 17 张表、47 个 REST + 1 个 SSE、14 个可独立验收的任务
   - 关键路径 `T01→T02→T03→T05→T07→T08→T10→T11→T12→T13`（10 节点）
5. **确定两个关键决策**：docx 支持（只抽纯文本 + 表格）；模型默认用 `mock` provider，保证无 Key、断网也能演示全链路。

### 关键决策与理由

| 决策 | 理由 | 被否决的方案 |
|---|---|---|
| 模型接入做成 provider 抽象层 | 换供应商只改一个实现类；`mock` 让 CI 和演示不依赖外网 | 直接写死某家 SDK |
| 异步用「DB 表 + 定时轮询 + SKIP LOCKED」 | 不引 MQ 也能保证单机多线程不重复消费，且进程重启不丢任务 | 内存队列（重启即丢）、Redis 队列（多一个中间件） |
| 采纳率分母 = 已评审数（不含 DRAFT） | 未评审不代表模型失败；放进分母会导致"生成越多指标越差" | 除以全部用例数 |
| 驳回原因强制字典码，自由文本不进聚合 | 自由文本无法 GROUP BY，是脏数据的源头 | 让用户随便填，事后用 LLM 归类 |
| 禁止真实 provider 失败后自动降级 mock | 会静默污染评估数据，指标全部失真 | 失败即降级，保证"永不报错" |

### 踩坑记录

- 架构文档中的 Mermaid 图必须用 `mermaid v11 parse()` 实际校验，肉眼看着对不代表能渲染（`$ref` 递归、中文括号、节点 id 重复都会崩）。
- 任务依赖要写成可校验的图，用脚本做 DFS 拓扑排序验证无环，比人工核对可靠。

### 下一步

- 阶段 3：T01 工程骨架（Maven + Flyway 建表 + Vite 前端 + MUI/Tailwind 共存）
- 仓库初始化、首次提交、远端推送

---

## 2026-11-07 · 阶段 2.5 · 仓库初始化

### 做了什么

1. 项目落盘到 `D:\code\Project\ai-testcase-platform`，`git init -b main`，采用单仓库多模块结构（`docs/` + 后续 `backend/`、`frontend/`）。
2. 建立仓库基础设施：`.gitignore`（构建产物 / 密钥 / IDE 文件）、`.gitattributes`（强制 LF，避免 Windows 与 Linux 换行符差异导致整个文件显示被修改）、`.editorconfig`（统一缩进与编码）、`LICENSE`（MIT）。
3. 按开源项目惯例补齐 `README.md`（徽章、解决什么问题、技术栈、文档索引、环境要求）与 `CHANGELOG.md`。
4. 建立 `.env.example` 模板与本机 `.env`（真实 Key 只存在于本机，已被忽略）。

### 关键决策与理由

| 决策 | 理由 | 被否决的方案 |
|---|---|---|
| 单仓库同时放 `backend/` + `frontend/` + `docs/` | 一次提交可同时包含接口变更与前端调用变更，联调不产生版本错位 | 前后端两个仓库（跨仓库同步成本高） |
| 密钥只走环境变量，代码零硬编码 | 一旦提交到公开仓库，Key 会被爬虫在数小时内扫走盗刷 | 写在 `application.yml`（方便但必然泄露） |
| 强制 `eol=lf` | 避免"只改一行、diff 显示整个文件都变了"的经典事故 | 依赖各人本地 `core.autocrlf` 设置 |
| 只提交与项目相关的文件 | 仓库是简历门面，`.idea/`、构建产物、个人配置只会制造噪音 | 直接 `git add .` 全量提交 |

### 踩坑记录

- Windows 下 `git add` 会提示 `LF will be replaced by CRLF`；正确做法是先用 `.gitattributes` 声明规则，再提交，而不是各人手动改配置。
- Git 提交身份必须全局配置且邮箱与 GitHub 账号一致，否则 GitHub 贡献图不计入这些提交。

### 下一步

- 创建 GitHub / Gitee 空仓库并推送
- 阶段 3：T01 工程骨架

---

## 2026-11-07 · 阶段 2.6 · 远端仓库与首次推送

### 做了什么

1. 在 GitHub（`rosyman666/ai-testcase-platform`）与 Gitee（`laiwenhao123666/ai-testcase-platform`）分别创建**空的公开仓库**——不勾选任何初始化文件，保证首次推送不会冲突。
2. 配置双远程：`origin` → GitHub（主），`gitee` → Gitee（国内镜像），`main` 分支上游指向 `origin/main`。
3. 添加本地别名 `git pushall`，一条命令同时推两端，避免手动推送时漏掉一边。
4. 首次推送成功，两端 `refs/heads/main` 指向同一提交 `7584836`。

### 关键决策与理由

| 决策 | 理由 | 被否决的方案 |
|---|---|---|
| GitHub 为主仓、Gitee 为镜像 | 简历与开源生态以 GitHub 为默认入口；Gitee 保证国内访问速度与备份冗余 | 只用一个平台（国内访问 GitHub 不稳定，单一平台有丢失风险） |
| 远端仓库创建时保持为空 | 远端若有 README/LICENSE，首推会 `non-fast-forward` 被拒，需要 `--allow-unrelated-histories` 合并，历史会变脏 | 用平台模板初始化（省事但污染历史） |
| 把代理写进仓库本地 `.git/config` | 只影响本仓库，不污染其他项目的网络配置 | 写全局配置（换网络环境时会牵连所有仓库） |

### 踩坑记录

- **推送全失败，报 `schannel: server closed abruptly` / `CONNECT tunnel failed, response 502`**：运行环境注入了 `HTTP_PROXY=http://127.0.0.1:59189` 这个内部代理，它不放行 github.com。定位方法：用 `curl -x <代理>` 逐个测试代理是否可用，发现本机 Clash 的 7897 端口可通。解决：`git config http.proxy http://127.0.0.1:7897`。
- `git push -u gitee main` 之后再 `git push` 会默认推到 Gitee，而不是 GitHub——因为 `-u` 会覆盖上游。需显式 `git branch -u origin/main main` 改回。

### 下一步

- 阶段 3：T01 工程骨架（Maven 多模块 + Flyway 建表 + Vite 前端 + MUI/Tailwind 共存验证）

---

## 2026-11-07 · 阶段 3 · T01 工程骨架与基础设施

### 做了什么

1. **后端骨架**（`backend/`，提交 `e45b51f`）：Spring Boot 3.3.5 + Java 17 + MyBatis-Plus 3.5.9，`com.aittest` 包下建 `common`(ApiResponse) / `config`(JacksonConfig) / `logging`(TraceFilter) / `system`(HealthController) 四个包。
2. **数据库版本化**：`V1__schema.sql` 建 15 张业务表，`V2__dict_and_config.sql` 建 2 张字典表 + 种子数据，共 17 张；Flyway 记录 `1 schema` / `2 dict and config` 两行，均 `success=1`。
3. **唯一端到端打通的接口** `GET /api/health`：返回 `{code,data,traceId}`，响应头回写 `X-Trace-Id`，`data` 带 `dbOk` 与当前 provider/model。
4. **前端骨架**（`frontend/`）：Vite 5 + React 18 + TS + MUI + Tailwind，`/api` 代理到 8080，一个能验证 MUI 与 Tailwind 共存的最小页面。
5. **双人验收**：工程师自测 8/8 → QA 独立复验 8/8，抓出 1 个 P3（`pom.xml` 注释结论与实测相反，已由 `c605c7e` 修正）。

### 关键决策与理由

| 决策 | 理由 | 被否决的方案 |
|---|---|---|
| Tailwind `preflight: false` | Tailwind 的 preflight 会全局重置 `button`/`input` 默认样式，MUI 的 Button 会变成裸文字。关掉后由 MUI 的 `CssBaseline` 做唯一样式重置来源 | 保留 preflight（MUI 组件全部走样）、或换非组件库（放弃成熟组件生态） |
| 用 Flyway 而不是 `schema.sql` | `schema.sql` 每次启动重建，无版本、不可增量、无审计；Flyway 的 `flyway_schema_history` 能回答"这台机器现在是第几版" | `spring.sql.init` 自动执行（省事但无法演进） |
| `.env` 用 `spring.config.import` 加载 | Spring Boot 默认不读 `.env`（那是 Node 习惯）。不加载就只能写死在 yml 或每次手动 export，两者都会导致"换台机器跑不起来" | 环境变量 export（换机器就忘）、把密码写进 yml（必然泄露） |
| Long 序列化用 `Jackson2ObjectMapperBuilderCustomizer` | 只注册 `Long`/`long` 的 `ToStringSerializer`，保留 Boot 的默认特性；不覆盖整个 `ObjectMapper` Bean | 用 `@JsonSerialize` 逐字段标注（漏一个就是线上事故）、yml 全局转字符串（会把 `int` 也搞坏） |
| 不加 `@MapperScan`，只给 Mapper 接口打 `@Mapper` | `@MapperScan("com.aittest")` 通配包名会把 `LlmProvider` 这类业务接口误注册成 Mapper，启动期报奇怪的 bean 错误 | 通配扫描（T02 起改用精确 `com.aittest.**.mapper`） |
| 布尔列用 `TINYINT` 而非 `TINYINT(1)`，实体用 `Integer` | MySQL 8.0.17 起显示宽度对整数类型已废弃；真正的问题是 Connector/J 把 `TINYINT(1)` 优先映射成 `Boolean`、`TINYINT` 映射成 `Integer`，混用会导致 DAO 反序列化出错 | `TINYINT(1)` + 实体 `Boolean`（看起来更语义化，实则埋坑） |

### 踩坑记录

1. **端口被环境变量劫持（最隐蔽）**：本机注入了 `SERVER__PORT=59173`（双下划线）。Spring 的**宽松绑定**会把它归一化成 `server.port`，且**环境变量优先级高于 `application.yml`**，应用去抢 59173 直接启动失败。解法：`pom.xml` 给 `spring-boot-maven-plugin` 加 `-Dserver.port=${app.port}`（系统属性优先级高于环境变量）。
   ⚠️ 这在 `java -jar` 下**不生效**——打包产物不带该参数，仍会被劫持。我最初在 pom 注释里写"部署产物不受影响"，QA 实测证伪：裸跑 `java -jar` 崩在 59173，必须 `java -Dserver.port=8080 -jar`。
2. **Flyway 10 把 MySQL 支持拆成了独立模块**：只引 `flyway-core` 会报 `Unsupported Database: MySQL 8.0`，必须同时引 `flyway-mysql`（10.17.0）。
3. **`eslint-plugin-react-hooks@4.6` 与 `eslint@9` 的 peer 冲突**：4.x 的 peer 范围只到 eslint 8，`npm install` 直接 ERESOLVE 失败，且报错指向 eslint 而非该插件，极难定位。提到 5.x 解决。
4. **关掉 preflight 后浏览器默认 `body { margin: 8px }` 回来了**：需要在 `index.css` 显式 `margin: 0`，否则页面四周有白边。
5. **日志文件跨进程累加，直接 grep 会误判**：`logs/ai-testcase.log` 里累积了历史上失败尝试的 ERROR，全文件 grep 会得出"启动就报错"的错误结论。正确做法是按**本次运行的启动时间窗**切日志。

### QA 的验证手法（值得复用）

| 手法 | 解决的问题 |
|---|---|
| 建空库 `ai_testcase_qa_verify` 让 Flyway 从零重建，比对两次迁移的 checksum | 排除"表是手工建的 / 复用了旧产物" |
| 在 `/tmp` 编译探针，用**仓库里真实的** `JacksonConfig` 实测序列化 | 把"读代码觉得对"升级成"实测生效"——`long` 与 `Long` 都变字符串、`int` 保持数字 |
| 按日志时间窗切片再统计 ERROR | 排除历史日志污染 |
| 三种探针交叉验证 preflight（`box-sizing` 计数、`text-size-adjust`、utility 选择器数） | 防止单个检查写法差异蒙混过关 |
| 用 `git log --all -S "sk-..."` 查历史密钥 | 排除"提交后又删掉"的泄漏 |

### 下一步

- T02 通用基础设施与鉴权闭环：`ErrorCode` / `GlobalExceptionHandler` / `PageQuery` / `AuthInterceptor` / `@MapperScan` / 种子账号（`V3__seed_admin_user.sql`）
- 注意：`ApiResponse`、`JacksonConfig`、`TraceFilter` 已在 T01 交付，T02 **只扩展不重写**

---

## 模板（新增记录时复制）

```markdown
## YYYY-MM-DD · 阶段 N（任务号）

### 做了什么
1.

### 关键决策与理由
| 决策 | 理由 | 被否决的方案 |
|---|---|---|

### 踩坑记录
-

### 下一步
-
```
