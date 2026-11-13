# 开发日志

> 记录原则：**每完成一个可验收的步骤就追加一条**，不写"今天写了很多代码"这种流水账。每条必须能回答四个问题：做了什么、为什么这么做（决策与取舍）、踩了什么坑、下一步是什么。
>
> 这份日志有两个用途：① 三个月后的你能快速回忆起设计意图；② 复盘"这个项目怎么来的"时，你有真实的演进过程可讲。

---

## 项目全生命周期路线图

一个完整交付的项目，通常要走完这 6 个阶段。当前处于阶段 2 结束、阶段 3 开始。

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
| 只提交与项目相关的文件 | 仓库是项目门面，`.idea/`、构建产物、个人配置只会制造噪音 | 直接 `git add .` 全量提交 |

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
| GitHub 为主仓、Gitee 为镜像 | 开源生态以 GitHub 为默认入口；Gitee 保证国内访问速度与备份冗余 | 只用一个平台（国内访问 GitHub 不稳定，单一平台有丢失风险） |
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

## 2026-11-07 · 阶段 4 · T02 通用基础设施与鉴权闭环

### 做了什么

1. **统一错误语义**：`common/ErrorCode.java`（12 个错误码，`4xxxx` 业务 / `5xxxx` 系统，全部带 `HttpStatus`）、`common/BizException.java`（service/domain 层**唯一**允许主动抛的异常）、`common/GlobalExceptionHandler.java`（把参数校验、404、唯一键冲突、未知异常统一翻译成 `{code,message,data,traceId}`，并把堆栈留在日志里而不是响应体里）。
2. **分页与序列化契约**：`common/PageQuery.java`（把 `page/size` 收口，限制上限防 `size=100000` 打挂库）、`common/PageResult.java`；`JacksonConfig` 为 `Long`/`long` 注册 `ToStringSerializer`、为 `Instant` 注册固定 3 位毫秒格式。
3. **枚举单一真源**：`common/enums/` 下 11 个枚举 + `CodedEnum` 接口，每个枚举自带 `code`/`label`，前端靠 `GET /api/system/enums` 取字典，避免前后端各写一份中文。
4. **鉴权闭环**：`auth/`（`AuthController` + `AuthService` + `TokenService` + `AuthContext` + `AuthInterceptor`），`V3__seed_admin_user.sql` 播种 `admin/admin123`（BCrypt），登录发 token、拦截器校验并把当前用户放进 `ThreadLocal`。
5. **审计字段自动填充**：`MybatisPlusConfig` 里的 `AuditMetaObjectHandler` 统一写 `createdAt`/`updatedAt`，业务代码禁手写。
6. **前端**：`api/http.ts` 统一出口（拦截 `code !== 0`、带 `Authorization`、统一错误 toast）、`api/system.ts`、`store` 保存登录态、`pages/Login.tsx`、`routes/index.tsx`（`/login`、`/`、`*` 兜底）。
7. **异步基础设施预留**：`config/AsyncConfig.java`（有界线程池，给 T05 的任务引擎用）、`config/AiProperties.java`（provider/model/base-url/api-key 四个键）。

### 关键决策与理由

| 决策 | 理由 | 被否决的方案 |
|---|---|---|
| `ApiResponse` 只有 4 个字段（`code`/`message`/`data`/`traceId`） | 前端只需要判断 `code === 0`，字段越少契约越稳 | 加 `success`/`timestamp`/`path`（每个都要前端配合，收益为零） |
| 枚举中文名放后端而非前端 | 导出 Excel 与 JUnit 注释里写的是中文，若前端各存一份，改文案时两边会分叉 | 前端维护 `label` 映射（改动要发两次版） |
| `AuthInterceptor` 而非 Spring Security | 本项目只有"登录/未登录"两种身份，引 Security 要配 `SecurityFilterChain`、CSRF、密码编码器，成本远超收益 | Spring Security（能力过剩） |
| 审计字段用 `MetaObjectHandler` 而非 DDL 默认值 | DDL `DEFAULT` 只在**不传该列**时生效；MP 的 insert 会把字段名带上（传 null），默认值根本不触发 | 只靠 `DEFAULT CURRENT_TIMESTAMP(3)`（实测不生效） |
| `@MapperScan("com.aittest.**.mapper")` 精确包名 | T01 的教训：通配 `com.aittest` 会把业务接口误注册成 Mapper | 通配根包 |

### 踩坑记录

1. **`isSuccess()` 把响应体从 4 字段变成 5 字段（最隐蔽）**：`ApiResponse` 上写了 `public boolean isSuccess()`，Jackson 按 bean getter 规则序列化出一个 `"success": true` 字段——**没人调用它，它自己冒出来了**。QA 用 `PageResult.isHasNext()` 同类泄漏证明了机制确实活着。解法：删掉 `isSuccess()`，判成功一律用 `code == 0`。**教训**：BO/VO 上任何 `isXxx()`/`getXxx()` 都会被序列化，加方法前先想"这会不会多一个 JSON 字段"。
2. **`NoResourceFoundException` 落到了 50000**：Spring 6.1 把 404 从 `NoHandlerFoundException` 换成了 `NoResourceFoundException`，而全局处理器只认前者，于是访问不存在的路径返回"系统内部错误"。解法：补处理器映射到 `40401`。
3. **`created_at` 断言测错了对象**：T02 原本计划"断言 `created_at` 与 `UTC_TIMESTAMP(3)` 相差 < 2 秒"，但 `created_at` 是 `AuditMetaObjectHandler` 用 **JVM 时钟**写的，与数据库会话时区无关——这个断言无论如何都过，测不出任何东西。改为在**同一条连接**上断言 `SELECT CURRENT_TIMESTAMP(3), UTC_TIMESTAMP(3), @@session.time_zone`，另加一个 JVM 侧用例（把 JVM 时区改成 `Asia/Shanghai`，断言 MP 写入仍是 UTC）。**这就是本项目"两个独立时间源"的由来**。
4. **`serverTimezone=UTC` 不改会话时区**：MySQL 8 里它只是 `connectionTimeZone` 的别名，会话仍跟随 `@@global.time_zone`（本机 `SYSTEM` = 东八区）。必须再加 `forceConnectionTimeZoneToSession=true`。（完整论证见 §3.2.1 与 D-12/D-16）
5. **`eslint-plugin-react-hooks@^4.6` 与 `eslint@^9` 的 peer 冲突**：报错指向 eslint 而非该插件，极难定位；提到 `^5.0.0` 解决（D-06）。
6. **工程师越权**：我在等用户批复时，工程师已经写好了 7 个 T02 文件。用 `git status` 发现、`git stash push -u` 保全、`TaskStop` 停手。**根因在我**：任务提示只写了"只做 T0x"，没写"交付后停手等确认"。之后所有派单都加了这句。

### 下一步

- T02 热修：`IdGen` 重启必撞唯一键（见下一条记录）
- T03 文档接入与解析

---

## 2026-11-07 · 阶段 4.5 · T02 热修 · IdGen 重启必撞键

### 做了什么

1. **`common/IdGen.java` 重写分配机制**：计数器从「三个前缀共用 `currentDay`/`currentSeq`」改为「按 `前缀+日期` 隔离的 `Map`」；新增 `NoType` 枚举（前缀 + 中文名绑定）、`SeqSeeder` 函数式接口、`nextAfterConflict`（丢缓存重播种）、`generateWithRetry(type, action)`（取号→写库→撞键重播种，最多 3 次）。
2. **新增 `config/IdGenSeederConfig.java`**：用**裸 `JdbcTemplate`** 查「该前缀该日期已占用的最大序号」作为播种起点，SQL 刻意**不带 `deleted = 0`**。
3. **两处超限改成业务错误**：撞键 3 次耗尽 → `40901`；当日序号达 6 位上限（999999）→ `40901`。旧实现在这两种情况下要么落 `50000`，要么直接输出 7 位序号破坏格式。
4. **独立验收程序实跑**（未入库，跑完即删）：12 条断言全过 —— 前缀隔离 4 条、重启自愈 2 条、软删行播种 2 条、多实例撞键重播种 4 条。

### 关键决策与理由

| 决策 | 理由 | 被否决的方案 |
|---|---|---|
| 「播种 + 重试」而非「撞库兜底」 | 撞库重试**不是分配机制**：单进程靠撞 N 次换一个能用的号，多实例两个进程各自从 1 开始会互撞 | 纯进程内计数器（重启必崩）、数据库序列表（单机每天几百条，过度设计） |
| 播种用裸 `JdbcTemplate` 而非 MyBatis-Plus | MP 会给查询自动注入 `deleted = 0`，而唯一索引根本不管逻辑删标记——软删行照样占号，用 MP 播种必然偏小 | MP `BaseMapper`（播种值偏小 = 热修失效） |
| 撞键后**重新播种**而非 `++` | 多实例下 `++` 会连撞：两个进程播种到 5、同时取 6，一个成功一个失败，`++` 得 7，而库里可能已有 7 | 简单 `++`（多实例收敛不了） |
| 计数按前缀隔离 | 三个前缀共用一个计数器会让 `doc_no` 跳号（传 3 个文档后建任务从 `000004` 起），而单号要写进 Excel 与 JUnit 注释供人追溯，跳号会被误判成数据丢失 | 共用计数器（省 3 行代码，换来"数据丢失"的误判） |
| 播种**懒执行**（首次取号才查库） | 不在启动时跑，就不与 Flyway 的迁移顺序耦合，也不会因启动期数据库没就绪而失败 | `@PostConstruct` 里查库（迁移未必跑完） |

### 踩坑记录

1. **原代码注释就是错的**：「调用方捕获唯一键冲突重试即可」——把兜底写成了机制。正确说法是"撞键是异常路径，正常路径必须靠播种起始值正确"。
2. **溢出**：`seq = seed + 1; if (seq > SEQ_MAX)` 在 `seed` 接近 `Integer.MAX_VALUE` 时溢出成负数，判断恒不成立，会吐出负数序号。改为**先比后加**（`if (used >= SEQ_MAX) throw`）。
3. **我自己的验收脚本第一版断言错了**：我断言"两个实例第一次就撞号"，实际没撞——因为播种是懒的，第二个实例取号时第一个已经落库了，它自然播到了新值。要真正触发撞键路径，得让第三个"进程"抢先占号。**教训**：测试跑出 FAIL 时先怀疑断言，别急着改被测代码。
4. **死代码**：`seqPartOf` 全仓无调用，且 Javadoc 写 `prefix.length() + DAY_LENGTH + 1`、实现写死 `1 + 8 + 1`，注释与代码自相矛盾。删掉。

### 下一步

- 推送双仓（`origin` + `gitee`）
- T03 文档接入与解析（25 个文件）

---

## 2026-11-07 · 阶段 5 · T03 文档接入与解析

### 做了什么

1. **四种格式的解析器**：`doc/service/parser/` 下 `DocumentParser`（接口 + 统一产物 record）、`ParserRegistry`（按 `DocType` 分发）、`MarkdownParser`（按标题层级切章节）、`TxtParser`（按空行/长度切块，首行前 60 字作标题）、`DocxParser`（POI `XWPFDocument`，遍历 `getBodyElements()` 同时抽段落与表格）、`OpenApiParser`（按 operation 切分）。
2. **`$ref` 展开**：深度上限 3 层，并用 `Set<String>` 记录已访问指针——自引用/三元环在深度 3 以内也能无限展开，只靠深度计数挡不住。
3. **`DocService`（737 行）**：查重秒回 / 软删恢复 / 失败重解析 / PENDING 不动，四态各有明确动作；`doc_no` 走 `IdGen.generateWithRetry`（D-17 的成果）。
4. **`config/TimezoneGuard`**：启动时在应用自己的 `DataSource` 上查 `@@session.time_zone`，非 `+00:00` 打 `[TZ-GUARD]` WARN，不抛异常、不阻断开机。
5. **VO 拆分**：`DocSourceVO` / `DocDetailVO` / `DocSectionVO` / `ApiOperationVO`，列表不带 `storage_path`/`deleted`/`created_by`/schema 三兄弟，converter 逐字段赋值。
6. **前端**：`types/doc.ts`、`api/doc.ts`、`DocList`/`DocUploadDialog`/`DocDetail`，路由表**只新增**两条懒加载路由。

### 关键决策与理由

| 决策 | 理由 | 被否决的方案 |
|---|---|---|
| `upload` 解析失败返 `code=0` + `parseStatus=FAILED`；`reparse` 才返 `42001` | 判据是 HTTP 语义：`upload` 的请求目的（接收并落盘建行）已达成，解析失败是**资源的状态**不是请求失败 | 统一返 `42001`（会让"文件存下来了但解析失败"和"根本没存下来"无法区分） |
| 扩展名判据只来自 `DocType.ofExtension` | 单一真源；另写一份 if-else 就必然有一天两边不一致 | 在 `DocService` 里按后缀判断（重复真源） |
| 落盘路径存**相对** `storage.dir` | 绝对路径把"根在当前机器的取值"烧进每一行 → 换机器全部失效，README 的"三步跑通"不成立 | 存绝对路径（本机能跑，换机器崩） |
| 查重命中 `PARSED` 时先判文件可读、不可读则重新落盘 | "行在、字节丢"原本永久不可自愈：重传只得 `reused:true`，只有主动重解析才发现丢了 | 直接秒回（把"可回溯"变成一句空话） |
| `TimezoneGuard` 用 `ApplicationRunner` | `@PostConstruct` 阶段 Flyway 未必迁移完，且抛异常会打挂上下文，与"只告警"冲突 | `@PostConstruct` / `@EventListener` |

### 踩坑记录

1. **落盘写进 `java.io.tmpdir`**：Javadoc 里老老实实写了"重启可能被清理"，但 `storage_path` 的存在理由恰恰是"重解析不必让用户重传"——把它写进会被系统清理的目录，等于让这句话变成假的，而且失败是**静默**的（库里有行、路径有值、文件已没）。已改为 `storage.dir` 持久目录（D-19）。
2. **`${STORAGE_DIR:uploads}` 的 `:默认值` 不处理空串**：`.env` 写 `STORAGE_DIR=` 时 Spring 绑出空串，`Path.of("")` 就是工作目录本身 → 文件静默落到 `backend/docs/`。上传、解析、入库全部成功，**没有任何测试会红**。`setDir` 补 `isBlank()` 回落 + `trim()`（D-21）。
3. **`InvalidPathException` 不是 `IOException`**：空白配置或脏 `storage_path` 抛它，会从 `catch (IOException)` 漏出去 → 英文的 `50000`。已统一归为"文件不可读"，返 `42001` + 中文（D-23）。
4. **我自己的验证脚本连错两次**：① 用 SQL 字面量写 Windows 绝对路径，反斜杠被 MySQL 当转义符吃掉，造出 `D:codeProject...` 这种脏值，我一度以为是产品 bug；② 断言"软删会删文件"——实际按 §3.2 / D-02 **软删不删文件**（否则重传恢复就没字节可复用）。**两次都是断言错，不是代码错**；先怀疑断言这条纪律今天用了两次。
5. **`V1__schema.sql` 的注释不能直接改**：Flyway 对已应用脚本逐字节 CRC32 校验，改一个注释字符 → `Migration checksum mismatch` → 应用起不来。注释漂移只能靠新迁移覆盖（`V4__fix_storage_path_comment.sql`，纯 `MODIFY COLUMN ... COMMENT`，走 INSTANT 不重建表）。
6. **`UPDATE` 会触发 `ON UPDATE CURRENT_TIMESTAMP(3)`**：修 `storage_path` 时若不写 `updated_at = updated_at`，会把审计时间改成执行时刻——和 §3.2.1.2 手工写库那个坑是同一件事的自动化版本。

### 验证

两个独立脚本共 **40 条断言全绿**（21 + 19）：

- `storage_path` 为相对路径且文件真实落盘；同文件重传 `reused=true` 且行数不变；删掉字节后重传**自愈**（路径更新 + 文件回来）；`reparse` 读回成功；历史绝对路径行双模式仍可读；脏路径返 `42001` 而非 `50000`；`.pdf` 返 `42002`；`created_at` 与 `UTC_TIMESTAMP(3)` 差 0s 且换 `+08` 会话重算不变
- 软删恢复：`docId`/`docNo` 不变、`deleted` 回 0、章节不翻倍、行数不增；`force=true` 才物理删行并清理文件
- `STORAGE_DIR=`（空）与 `STORAGE_DIR='   '`（空白）都回落到 `../uploads`，`InvalidPathException` 计数 0
- docx 与 OpenAPI 冒烟通过（OpenAPI 3 个 operation 的 method/path/summary 均落库）

### 下一步

- T04：`aicore` 抽象 + `llm_call_log`，并接住 §8.1.1 定义的 `40004 E_FIELD_TOO_LONG` / `40902 E_DUPLICATE`（`GlobalExceptionHandler` 新增 `DataIntegrityViolationException` 分支）

---

## 2026-11-07 · 阶段 6 · T04 模型接入层与提示词模板

### 做了什么

1. **`aicore` 的 6 个基础类**：`LlmProvider`（接口：`name()` / `supports(model)` / `chat()`）、`LlmRequest`（Lombok Builder，同时装"发给上游的"和"只用于记账的"两类字段）、`LlmResponse`、`LlmMessage`（`record` + 三个静态工厂）、`LlmUsage`（`totalTokens()` 是**派生值**）、`LlmException`（带 `ErrorCode` + `retryable`）。
2. **两个真实 provider + 路由**：`MockLlmProvider`（无 Key、确定性、按输入 SHA-256 选样，并用 `[[MOCK:xxx]]` 标记构造 6 类坏路径）、`OpenAiCompatibleProvider`（OkHttp 单例、超时/状态码分支、`retryOnConnectionFailure(false)`）、`LlmProviderRouter`（别名表 + 启动时对 `ai.fallback.enabled` 打 WARN）。
3. **记账装饰器 `LoggingLlmProvider`**：计时 → 转发 → 落 1 条 `llm_call_log` → 原样返回/抛出。成功、`RuntimeException`、`Error` 三条路径各落 1 条。
4. **成本**：`ModelPrice` / `ModelPriceMapper` / `ModelPriceCache`（5 分钟 TTL），单位是"每 1M token 的微元数"，`costMicro` 四舍五入到整数微元。
5. **结构化输出三件套**：`JsonRepairer`（去围栏 → 括号配对截取 → 截断补全 → 语法归一化）、`JsonSchemaValidator`（networknt Draft-07，错误消息带 `[2].priority` 这样的字段路径）、`StructuredOutputParser`（裸数组/对象解包两种形态）。
6. **提示词模板四件套**：`PromptTemplate`（实体）、`PromptTemplateLoader`（启动幂等 seed 4 个 `.md`）、`PromptRenderer`（`{{var}}` 单趟替换）、`PromptTemplateService`（列表/详情/新建版本/激活/渲染/**试跑**），配 `PromptTemplateController` 与 5 个 DTO。
7. **模板与 schema 资源**：`prompts/{testpoint_extract,case_generate,case_repair,failure_triage}.v1.md`（YAML front-matter + 中文正文 + few-shot + 禁止事项）、`schema/{testpoint,testcase,failure_triage}.schema.json`。
8. **错误码分流落地**：`ErrorCode` 新增 `E_FIELD_TOO_LONG(40004)` / `E_DUPLICATE(40902)`，`ColumnWhitelist` 把 `Data too long for column 'x'` 的列名翻成中文标签，`GlobalExceptionHandler` 新增 `DataIntegrityViolationException` 三分支（1406 / 1062 / 其余兜底）。
9. **前端**：`api/prompt.ts` + `pages/prompts/PromptTemplateList.tsx`（列表 + 详情弹窗 + 试跑结果芯片）+ `/prompts` 懒加载路由 + 首页"业务入口"两个按钮。
10. **测试**：4 个单测类共 32 条（`StructuredOutputParserTest` 12 / `PromptRendererTest` 7 / `GlobalExceptionHandlerTest` 8 / `LlmExceptionHandlerTest` 5），全部不依赖网络、不依赖数据库。

### 关键决策与理由

| 决策 | 理由 | 被否决的方案 |
|---|---|---|
| 记账做成**装饰器**且**不实现** `LlmProvider` 接口 | 调用点有 4 个 × 成功/失败 = 8 条分支，逐条手写必有一条漏，而漏掉只会让看板数字慢慢变小、**没有任何测试会红**。不实现接口是为了防止"装饰器包装饰器"→ 一次调用落 2 条台账（表现为"成本翻倍但调用次数正常"，极难发现） | 每个调用点自己写 `log`；或让装饰器 `implements LlmProvider` 以便统一 resolve |
| 记账字段塞进 `LlmRequest` 而不是"调用方事后补日志" | 装饰器只看得到请求对象。事后补日志等于把 8 条分支各写一遍，同样必有一条漏字段 | 请求对象只放"发给上游的"，记账另开一个 `CallContext` |
| `ModelPrice` 用 `effectiveFrom` + 内存取最新，不用 `GROUP BY model` | `SELECT input_price_micro ... GROUP BY model` 依赖 MySQL 宽容模式，`ONLY_FULL_GROUP_BY` 下直接报 1055 | 一条 SQL 拿到最新单价 |
| 修复器**只对括号不配平**的文本做截断补全 | 对完整 JSON 也补全会退到"最后一个安全点"，把尾字段切掉——输出**依然合法、依然能解析**，只是静默少字段 | 无条件调用 `completeTruncated`（第一版就是这么写的，被端到端抓出来） |
| 语法修不好 → `50004`；结构不对 → `50005`，两者不合并 | 前者是"模型没按格式说话"（改提示词/换模型），后者是"格式对了但内容不合约定"（改 schema/改模板），处置方向完全不同 | 统一一个 `E_LLM_OUTPUT_INVALID` |
| 模板版本号**不入参**，服务端 `MAX(version)+1` | 让前端传 → 两人同时提交撞成同一个号，然后就有人"顺手"让它覆盖；模板一旦可覆盖，`test_case.prompt_version` 指向的就是内容已经变了的东西，回溯彻底失效 | 前端传 `version` |
| 新建版本/激活都先 `deactivateByCode` 再写 | 反过来会有一瞬间两行 `is_active=1`，而 `selectActiveByCode` 带 `LIMIT 1` **不报错**，只表现为"有时候用新版、有时候用旧版" | 一条 `CASE WHEN` 的 UPDATE（行锁顺序不同会死锁） |
| `activate` 时 `updated_at = updated_at` 抑制 `ON UPDATE` | `is_active` 是"版本管理状态"不是"内容修改"；`updated_at` 该回答"这版内容最后改于何时" | 让它自动更新（审计语义被稀释） |
| 缺变量**一次列全**并抛 `40001` | 一次只报一个，用户要试 4 次才能填完 | 遇到第一个缺失就返回 |
| 模板 seed 用**显式清单**而不是通配 `prompts/*.md` | 目录里放一个草稿就会被 seed 进生产 | `PathMatchingResourcePatternResolver` 通配 |
| `PromptTemplateLoader` **不用** `@Transactional` | ① 自调用不走代理，注解根本不生效；② 也不需要——"半成品状态下次启动自动补齐"，绑事务反而让"第 4 个文件写错 → 前 3 个永远装不进" | 整个 seed 包一个事务 |
| 试跑端点 `POST /{id}/preview` **不落库** | 它是"看这条提示词会调出什么"，不是一次业务生成；落库会污染任务表 | 复用 T05 的生成接口 |
| `LlmException` 的翻译放 `aicore/LlmExceptionHandler` 而不是 `common` | 放 `common` 就形成 `common → aicore → common` 包级循环，以后想把 `common` 抽成 starter 会被这一个 import 卡住 | 塞进 `GlobalExceptionHandler`（省一个文件，代价是循环依赖） |

### 踩坑记录

1. **修复器把"好 JSON"修坏了（本阶段最重要的一个 bug）**：端到端跑 `failure_triage` 时 schema 报"未找到所需属性 `reason`"，而模型返回的 JSON 里明明有。根因是 `repairWithMeta` **无条件**调 `completeTruncated`，它退到"最后一个安全点"（最后一个逗号）把尾字段切掉，补完括号后**语法合法、能解析、只是少了一个字段**。修正两处：① `String completed = truncated ? completeTruncated(body) : body;`；② 逗号只有在它是**数组元素分隔符**（`stack.peek() == ']'`）时才算安全点。**这个 bug 的可怕之处是它不抛异常**——"半个用例被当成完整用例入库"就是这么来的。
2. **`LlmException` 没有任何处理器接住**：它继承 `RuntimeException` 而非 `BizException`，一路冒到 `GlobalExceptionHandler` 的兜底分支 → 用户看到 `50000 服务器开小差了`，而**台账里记的是 `E_LLM_UPSTREAM`**。于是"账本正常、用户被误导"，任何只看台账或只看 HTTP 状态（都是 200）的检查都不会红。补 `LlmExceptionHandler` 后返回 `50001` + `data.retryable`（D-25）。
3. **`Long` 被全局序列化成字符串**：`latencyMs` 用 `Long` 时前端拿到 `"153"`，按字典序排序 `"90" > "153"`。改成 `Integer`。
4. **`SERVER_PORT=8096` 不起作用，实际起在 8080**：沙箱注入了 `SERVER__PORT`，环境变量优先级高于 yml。改用 `-Dapp.port=8097`（系统属性优先级更高，且 pom 的 `jvmArguments` 已锁定）。
5. **`nohup ... &` 起的进程被沙箱杀掉**：改用受管后台任务。
6. **我自己的脚本把 `mysql` 密码参数插错了位置**：`cmd.insert(4, "-p" + pwd)` 插到了 `-P` 前面，`-P` 把 `-p123456` 当成端口值（`option 'port': value -p123456 adjusted to 0`），密码根本没传 → `Access denied (using password: NO)`。改用 `MYSQL_PWD` 环境变量，彻底避开短选项取值歧义。
7. **`40004` / `40902` 在页面上根本不可达**：设计时设想"`doc_no` 插重复 → `40902`、`title` 超 500 → `40004`"，实测发现 `doc_no` 重复会被 `IdGen.generateWithRetry` 吸收（重播种 3 次后抛 `40901`），`title` 属 T05 才建的表，而所有写入口都有前置守卫（DTO `@Size` / 解析器 `requireFitsColumn` / 文件名 `truncate`）。这不代表代码有问题——**它们本来就是"最后一道网"**，可达性低正是设计目标；但验收方式必须改：单测构造真实 MySQL 错误消息（8 条）+ 一条真实并发 HTTP 断言，而不是假装能"插重复值"。
8. **并发撞 1062 第一版跑了 5 轮一次都没撞上**：两个事务都快到可能完全串行，后到的那个读到新 `MAX` 就排到下一个版本号。改成用外部 MySQL 会话对该 `code` 加行锁（`SELECT ... FOR UPDATE`），把两个 HTTP 事务同时卡在 `deactivateByCode` 上——此时它们的 `MAX(version)` 都已算完、都认为是 2，插入必然撞。等于把毫秒级窗口放成秒级，与单测里用 `CountDownLatch` 撑开线程竞态是同一个手法。
9. **验收脚本自己没打扫**：`llm_call_log` 上一轮的行还在（`trace_id` 相同），"一次调用 = 恰好 1 行"数到 2，看上去像记账重复。凡是"计数类断言"，脚本开头必须先清自己的数据。

### 验证

- **单测 32 条全绿**（`mvn test`）：坏 JSON 4 类 + "完整 JSON 不被误伤" + 值里含 `}` + schema 路径 `[0].priority` + 对象解包；渲染器缺变量列全 / 值不二次展开 / `$` 与 `\` 转义；1406→`40004`、1062→`40902`（三种 `conflictOn`）、1452→`50000` 双向断言；`LlmException` 五个码各自映射 + `retryable` + 文案兜底。
- **端到端 41 条全绿**（mock，8097）：4 个模板 seed 且都生效、试跑 `provider=mock` + `schemaValid`、缺变量列出全部 3 个、四类坏输出 `repaired=true`、截断只保留完整元素、schema 错误带 `[1].category`、**一次调用恰好 1 行台账**、上游异常也落 1 行且 `E_LLM_UPSTREAM`、3 次重试 `attempt_no=1/2/3` 不覆盖、并发新建同版本 `[0, 40902]`、幂等 seed 4 行无重复。
- **缺 Key 分支 8 条全绿**（`openai-compatible` + 空 `AI_API_KEY`）：`50001`（不是 `50000`）、`retryable=false`、文案点名 `AI_API_KEY`、台账 1 行 `FAILED/E_LLM_UPSTREAM`、`latency_ms<1000`（**没发起网络请求**）。
- **前端 `tsc --noEmit` 通过**，`/prompts` 路由已注册。

### 下一步

- T05：抽点与用例生成（`testpoint` / `generation` 两个模块），提示词模板与记账装饰器直接复用 `PromptTemplateService.render()` 与 `loggingLlmProvider.chat()`
- `docs/01-architecture.md` §2.2 目录树与 §4.2 端点表已同步（D-24 试跑端点、D-25 异常翻译）

---

## 2026-11-07 · 阶段 7 · T05 测试点抽取与评审

### 做了什么

1. `testpoint` 模块落地：`TestPoint`（14 字段）/ `TestPointHasher` / `TestPointMapper` / 8 个 DTO / `TestPointService` / `ExtractPlanner` / `TestPointExtractService` / `TestPointController`（6 个端点）。
2. **幂等身份定为 `content_hash = sha256(doc_id + title + category)`**，分隔符用 `\0`；`description` / `riskLevel` **刻意不进哈希**。
3. **`ExtractPlanner` 独立成类**：OpenAPI 按 `api_operation` 逐个切、纯文本按 `doc_section` 逐个切，二者不混合。
4. **去重四态处置表**：`ACTIVE` 一字不动 / `DISABLED` 一字不动（人停用的 AI 说了不算）/ `SUPERSEDED` 复活为 `ACTIVE` / 本轮没抽到的 `ACTIVE` 置 `SUPERSEDED`（不删除）。
5. `sourceRef` **服务端权威生成**，模型写的不落库；`category` 白名单由 `CaseType` 枚举现算。
6. 前端 `pages/testpoints/TestPointReviewPage.tsx` + `types/testpoint.ts` + `api/testpoint.ts` + `/testpoints/:docId` 路由 + `DocDetail` 加"测试点评审"按钮。
7. 单测 **28 条**（`TestPointHasherTest` 9 + `ExtractPlannerTest` 6 + `TestPointServiceTest` 10 + `TestPointExtractServiceTest` 3）；全量 **67 条** 0 失败。
8. 端到端 **36 条全绿**（`t05e2e.py`，mock，8097）。

### 关键决策与理由

| 决策 | 理由 | 被否决的方案 |
|---|---|---|
| 幂等身份 = `doc_id + title + category`，`description`/`riskLevel` 不进哈希 | **人会改描述**。掺进去之后，一条被人工润色过的点在下一轮抽取里永远对不上原哈希，于是"插入一条新的 + 旧的被置 SUPERSEDED"，评审页上凭空多一条、人写的内容还被顶掉 | 用全文哈希（`title+category+description`）——精确但把"人的编辑"当成了"内容变化" |
| 哈希分隔符用 `\0` | 用字符串拼接会边界碰撞：`docId=1, title="2x"` 与 `docId=12, title="x"` 拼出同一个串 | `docId + "-" + title + "-" + category` |
| **去重是单元级的，取代是文档级的**（D-26） | 两者**时间尺度不同**。`mergeExtraction` 按单元合并（去重、插入、复活），`supersedeMissing` 在**全单元完成后恰好调用一次**。写在一起时第 1 个单元处理完就把后面 N-1 个单元的产出标成 `SUPERSEDED`，第 2 个再救回来并反手标掉第 1 个的——互相踩，ACTIVE 数随机 | 单方法 `applyExtraction` 一次做完（**这个方案实现过，端到端验收抓出来的**） |
| **抽取失败的单元，它已有的点必须算进 `kept`** | 取代的语义是"原文里已经没有这个点了"，而**一次模型超时并不能证明这一点**。漏掉这步，一次上游抖动就把该单元的测试点整批静默废掉——和"提前取代"是同一类事故 | 失败就不管（点会被误取代）；或"任一单元失败则整篇跳过取代"（一个永久抽挂的接口会让整篇文档永远无法清理） |
| 失败收成 `failed(...)` 而不抛异常（验收 ⑤） | 批量要把 N 个单元的结果汇总成一份报告，抛异常只能表达"第一个失败"，后面 N-1 个的去向就没了 | 抛 `BizException` |
| `sourceRef` 服务端生成，不采信模型写的 | 它的用途是**反查回原文**，真源是数据库外键。模型可能给 A 接口的点写上 B 接口的 `operationId` | 用模型输出的 `sourceRef` |
| `supersedeMissing` **不删除**，只置 `SUPERSEDED` | 这些点可能已被生成的用例引用着，删了外键就断了 | 物理删除 |

### 踩坑记录

1. **"半个编辑"会造出编译不过但看起来改完了的文件**：`SENSITIVE_CONTENT_UNAVAILABLE` 屏蔽了那次 Edit 的返回，实际只落了 `extractDocument` 那半、`doExtract` 那半没改。**教训**：任何工具返回不可见的写操作，事后必须用 `grep` 核实关键符号是否真的落地，不能假设成功。
2. **失败隔离的 `try/catch` 在那次半个编辑里被顺手删掉了**，类注释却还写着"失败返回 `failed(...)` 不抛异常"。结果是模型返回一次坏 JSON，整篇文档的抽取报告变成一个错误码抛给前端——前面已落库的单元白干，用户只看到"请重试"，看不出是哪个接口抽挂了。**契约写在注释里、实现在代码里，就必须有跑得快的单测盯着**（`TestPointExtractServiceTest` 即为此而生）；端到端要起服务连库，容易被跳过。
3. **端到端脚本自己会污染自己**：`DocService` 对同名同哈希文件"命中缓存秒回"复用 `docId`，于是第二轮跑脚本时，上一轮的 `test_point`（手补点 `source_ref=''`、幽灵点 `id=900000000000000001`）全串进本轮断言——`GROUP_CONCAT(source_ref)` 冒出空前缀、主键撞车。**任何依赖"新建数据"的验收脚本，开头必须清掉自己上一轮的产物**，否则断言的是历史残留。
4. **断言写成"每节都必须产出新点"是错的**：mock 按调用轮次循环返回内容，第三个单元抽出来的点与第一个同名同分类，被去重键判成"已存在"——**这正是设计语义**（去重身份不含来源）。该断言的应该是"每条点的 `sourceRef` 能反查回它挂的那节"，即 `source_ref = CONCAT('section:', s.seq_no)`。**关系断言 > 硬编码计数**：写死 `unitCount == 2` 的地方，改一次 fixture 就误报一片。
5. `TestPointServiceTest` 的 `updateById(any())` 撞上 MyBatis-Plus 3.5.9 `T` / `Collection<T>` 两个重载，必须写 `any(TestPoint.class)`；断言 `never()` 的依赖不要 stub，否则 `UnnecessaryStubbingException`。

### 下一步

- T06 异步任务引擎：`POST /api/testpoints/extract` 现在是**同步返回报告**，验收 ⑦ 要求的 `taskId` + SSE 进度要等 T06 才能补（已在 §11.1 D-26 登记为临时契约）
- T06 之后补：用例生成 `generation` 模块、`test_point` 与 `test_case` 的外键引用
- 前端评审页目前只做了列表 + 编辑/启停，批量采纳与"跳到原文"待 T07

---

## 2026-11-07 · 阶段 8 · T06 异步任务引擎与 SSE 进度

### 做了什么

1. `generation` 模块落地 **15 个后端文件**：`controller/GenTaskController`、`domain/{GenTask,GenTaskItem}`、`dto/{GenTaskCreateReq,GenTaskVO}`、`mapper/{GenTaskMapper,GenTaskItemMapper}`，以及 `service/` 下 8 个类（`GenTaskService` / `GenTaskDispatcher` / `GenTaskWorker` / `ProgressPublisher` / `SseEmitterRegistry` / `TaskItemHandler` / `TaskStateMachine` / `TestPointExtractHandler`）。
2. **数据库当队列**：`V5__gen_task_item_unit_ref.sql` 给 `gen_task_item` 补 `unit_ref` / `kept_hashes` 与唯一键 `uk_gen_task_item_task_unit`；`GenTaskItemMapper.xml` 手写 `FOR UPDATE SKIP LOCKED` 认领；`GenTaskMapper.xml` 手写原子 `bumpCounters` 与带状态守卫的 `markTerminal`。
3. **SSE 进度**：首帧 `snapshot` 从 DB 现读，之后 `progress` / `item` / `done`（失败走 `error`）；进度事件 ≥200ms 节流，**终态事件不节流**；`SseEmitterRegistry` 管连接生命周期与断线清理。
4. **任务状态机集中到 `TaskStateMachine`**：合法流转 `PENDING→RUNNING→{SUCCESS,PARTIAL,FAILED,CANCELED}`，非法流转抛 `E_STATE_ILLEGAL(40901)`；终态由 `terminalOf(total, success)` 推导，部分失败为 `PARTIAL`。
5. **抽点接入异步引擎**：新增 `TestPointExtractHandler`（按 `unitRef` 定位单元 → `extractUnit` → 写 `kept_hashes`），任务收尾时汇总 `kept` 并**恰好一次**调用 `supersedeMissing`。
6. **闭合 T05 的临时契约**：`POST /api/testpoints/extract` 从"同步返回 `TestPointExtractReport`"改为立即返回 `{taskId, taskNo}`，进度经 `GET /api/tasks/{id}/stream` 订阅（§6 验收⑦、§11.1 D-26 的临时契约）。
7. **SSE 鉴权换位置不减量**：`/api/tasks/*/stream` 进 `WebMvcConfig.AUTH_WHITELIST`，改由 `GenTaskController` 自己调 `TokenService.verify(token)` 并校验任务存在。
8. 改动 4 个既有文件：`WebMvcConfig`（白名单）、`TestPointController`（端点契约）、`TestPointExtractService`（`unitRefsOf`）、`TestPointService`（`keptHashesOfUnitRef`）。
9. **冒烟已跑通**（`t06smoke.py`，mock，8097）：抽取端点返 `taskId` / 任务终态 `SUCCESS` / 2 个 item 全部成功 / 测试点落库 / `sourceRef` 为 `operation:` 形态 / 重复抽取不翻倍 / 取消终态任务返 `40901`。**全量单测与端到端回归正在由其他 worker 补，结果待补**。

### 关键决策与理由

| 决策 | 理由 | 被否决的方案 |
|---|---|---|
| **不引 MQ，用 `gen_task` / `gen_task_item` 当队列表** | 单机部署 + 万级数据量下，MySQL 8 的行锁已经足够；多一个中间件就多一套部署、监控与"消息丢了但任务表还在"的双真源。任务与业务数据同库同事务，进度可直接 SQL 查 | RabbitMQ / Redis Stream（引入运维面 + 两套状态） |
| **认领时间参数化 `nowUtc`，SQL 禁用 `NOW()` / `CURRENT_TIMESTAMP` / `DATE_ADD` / 时间字面量** | `next_run_at` 的默认值取自**会话时区**，比较基准若也在 SQL 里求，"写"和"读"都跟着会话漂；应用连接钉了 UTC 时碰巧一致，一旦有人用 CLI 排查或连接池配置被改，认领窗口整体偏移 8 小时，表现为"任务迟迟不被认领 / 一次认领过量"，**日志里看不出来** | 在 SQL 里写 `next_run_at <= NOW(3)`（少一个参数，多一个隐性时钟） |
| **认领方法 `claimBatch` 必须 `@Transactional`** | `FOR UPDATE SKIP LOCKED` 的行锁在**语句结束即释放**。不在事务里，"认领"与"标记 RUNNING" 之间锁已经没了，两个 worker 会拿到同一批 id，`SKIP LOCKED` 形同虚设；`markRunning` 带 `status='PENDING'` 条件与"更新条数 != 入参数量"的 WARN 是这条边界的探针 | 只给 SQL 加锁、不加事务注解（**看起来锁了，实际没锁**） |
| **状态机集中到 `TaskStateMachine`，非法流转抛 `40901`** | 流转规则散在 service / dispatcher / worker 三处时，每处只写了一半且单看都合理，会出现"worker 置 FAILED、dispatcher 又推回 RUNNING"；静默吞掉非法流转的状态机等于没有状态机 | 各处 `if (status == ...)` 自行判断；或非法流转静默忽略 |
| **终态写入用带状态守卫的条件更新抢占** | 8 个 worker 可能同时判定"所有 item 都收尾了"。`markTerminal` 带 `AND status IN ('PENDING','RUNNING')`，`won > 0` 才推 `done` / 调 `onTaskFinalize`；否则会重复收尾、重复推 SSE、抽点场景还会重复跑一次 `supersedeMissing` | 先 `selectById` 判状态再无条件 `update`（读-改-写竞态） |
| **进度用 `bumpCounters` 原子累加 `col = col + n`** | 8 个 worker 同时收尾时"读-改-写"互相覆盖，`done_items` 比实际少、进度条卡住，而每条日志都说"更新成功" | 应用层算好 `setDoneItems(n)` 再整体写回 |
| **SSE 首帧推 DB 快照，不存内存事件历史** | 断线重连时浏览器自带 `Last-Event-ID`，但服务端若靠内存副本恢复，它与 DB 漂了前端就会显示 98% 而任务其实已结束。快照一律 `snapshotOf()` 从 DB 现读，单机不保存事件历史是**刻意的简化** | 进程内 `List<Event>` 回放环（多实例必失效，且要定容量与淘汰） |
| **`EventSource` 带不了 header，token 只能走 `?token=`，controller 自己验** | 浏览器的原生 `EventSource` 无法自定义请求头，所以该路径必须在白名单里、改由 `GenTaskController` 调 `TokenService.verify`；**校验没少，只是换了地方**。把它从白名单摘掉会得到"连上就 401"，而控制台只报一个不带上下文的 `EventSource error` | 把 `Authorization` 塞给 `EventSource`（浏览器不支持）；或整条 `/api/tasks/**` 加白名单（把创建/取消一起放开） |
| **`POST /api/testpoints/extract` 改为 `{taskId, taskNo}`** | T05 阶段同步返回抽取报告是"引擎还没建"时的临时契约（§11.1 D-26），与 §6 验收⑦ 冲突。T06 落地后由 `unitRefsOf(docId)` 建任务、立即返回，抽取语义仍全部由 `TestPointExtractService` / `TestPointService` 保证，`TestPointExtractHandler` **不重写其中任何一环** | 保留同步报告、另加一个异步端点（两套入口、两套去重边界） |
| **异步化后 `kept` 必须持久化到 `gen_task_item.kept_hashes`，收尾汇总再调文档级 `supersedeMissing`** | 同步路径里 `kept` 是内存中跨单元累加的；异步下每个 item 各自执行、还可能跨进程重启，收尾时只能从库里汇总。少了它就把"没被产出"误判成"消失了"（D-26 同一类事故）。**失败单元**改用 `keptHashesOfUnitRef(docId, unitRef)` 保全原有点——超时 ≠ 原文里没有了 | 每个 item 跑完就调一次 `supersedeMissing`（回到 D-26 的单元互相踩） |
| **`unit_ref` 用 `sourceRef` 字符串，不新增 `operation_id` / `section_id` 两个外键；可空且取 NULL** | `sourceRef` 已是 T05 的单元规范身份，再加两列是同一概念的第二套表达；重解析后行 id 会变而 `operationId` 字符串稳定。取 NULL 而非 `''` 是因为 MySQL 唯一索引把多个 NULL 视为互不冲突（用例生成场景不填 `unit_ref` 才不会第二条就 1062） | 双 FK + 非空字符串（重解析后失配、同任务多 item 撞唯一键） |
| **显式 VO，禁实体直出；实体时间 `LocalDateTime`(UTC) → VO 转 `Instant`** | §4.4 硬规则："今天没有敏感字段"不是判据，判据的输入会变。`GenTaskVO` 显式列出 18 个字段，泄露在编译期不可能发生；`Instant.toString()` 小数位浮动会让前端 `dayjs` 解析失败，故 VO 用 `Instant` + 固定 3 位毫秒 + `Z` | `@JsonView` / 实体加 `@JsonIgnore`（判据随字段变化而失效） |

### 踩坑记录

1. **调度器兜底 `catch` 把 NOT NULL 异常吞成"任务不收尾"**：`error_code` / `error_msg` 是 `NOT NULL DEFAULT ''`，收尾时"无错误"传了 `null`，MySQL 严格模式直接拒写；异常冒到 `GenTaskDispatcher.poll()` 的兜底 `catch`，只留一条 `[SCHED] 调度轮询异常` 的 ERROR 日志。症状是 **item 全 SUCCESS、任务却卡在 PENDING**，离根因（数据库列约束）非常远。修正为显式写空串 `""`，`cancel()` 与 `maybeFinalize()` 两处同改。教训：**"保证调度线程不死"的兜底 catch 会同时消灭唯一的证据链**，这类 catch 必须打印完整堆栈（本类已打 `ex`）。
2. **`WebMvcConfig` 的 Javadoc 里 `{@code /api/tasks/*/stream}` 提前结束了注释块**：`*/` 被编译器当成注释终止符，后面 34 个字符变成裸 Java 代码，报一串 `\u9519\u8bef: \u975e\u6cd5\u5b57\u7b26` 编译错误，报错位置与真正原因完全对不上。改成 `{@code /api/tasks/{@literal *}/stream}` 表述或直接写"任务流端点"。**教训：Javadoc 里出现 `*/` 字面量本身就是语法炸弹**。
3. **MyBatis-Plus `BaseMapper` 没有 `lambdaUpdate()` 链式**：写成 `genTaskItemMapper.lambdaUpdate()...` 直接编译不过。正确写法是 `genTaskItemMapper.update(null, Wrappers.<GenTaskItem>lambdaUpdate().eq(...).set(...))`——`update(null, wrapper)` 的第一个参数是实体（传 `null` 表示全部用 `set` 子句），`Wrappers` 才是 lambda 更新的入口。
4. **`TestPointExtractHandler` 曾把两个 FK 传 null，失败单元保全集合为空**：`keptHashesOfUnit(docId, operationId, sectionId)` 需要 `api_operation.id` / `doc_section.id` 两个外键，而 `gen_task_item` 只存 `unit_ref` 字符串。传 null 时查询恒返空集 → 失败单元的原有测试点被 `supersedeMissing` 整批置 `SUPERSEDED`，**一次模型超时静默废掉有效测试点**（与 D-26 同病）。改为按 `sourceRef` 匹配：`TestPointService.keptHashesOfUnitRef(docId, unitRef)`。
5. **`@AssertTrue` 包路径写错 / `AuthContext` API 名写错 / `ErrorCode.code()` 与 `getCode()` 不一致**：三者都是编译期问题，但报错分散在 DTO、controller、异常分支三处，容易被误判成"某处业务逻辑写错了"。实际都是**照着记忆写 API 名**：`jakarta.validation.constraints.AssertTrue`（不是 `javax`）、`AuthContext.requireUserId()`、`ErrorCode` 的 `code()` 由 `CodedEnum` 提供而枚举自身是 `name()` / `getCode()`。凡涉及枚举与上下文 API，先 `grep` 一次定义再写。
6. **终态抢占与任务级收尾的顺序**：`onTaskFinalize` 必须在 `markTerminal` **之前**跑，但它本身失败只打 ERROR 不阻断；若顺序反过来，`supersedeMissing` 会在任务已终态后再改测试点，前端 `done` 事件已经发出，用户看到的是"完成了但列表又变了"。
7. **冒烟脚本必须自己清数据**：与 T05 同一个坑——`DocService` 对同名同哈希文件"命中缓存秒回"复用 `docId`，上一轮的 `test_point` 会串进本轮"重复抽取不翻倍"的计数断言。`t06smoke.py` 每次上传用独立的 OpenAPI 内容并以新 `doc_id` 做作用域。
8. **`produces = text/event-stream` 下抛异常 → 500 空 body**：SSE 端点声明 `produces` 后，异常一旦由 `@RestControllerAdvice` 按 JSON 输出，内容协商失败抛 `HttpMediaTypeNotAcceptableException`。真实浏览器 `EventSource` 发的 `Accept: text/event-stream` 命中这条路，而 `curl`（`Accept: */*`）走正常路径，于是"本地 curl 看着 401 正常、浏览器却拿 500 空 body"。**教训：`produces` 收紧的不只是成功响应，异常响应也被约束；凡是 `produces` 非 `*/*` 的端点，必须单独验"异常时前端到底收到什么"。**
9. **`ResponseBodyEmitter` 的 early send + `complete()` 只有容器 `initialize` 时才真正 flush**：`emitter.send()` 在尚未 `initialize` 时进 `earlySendAttempts` 缓冲、`complete()` 只置标志，真正把缓冲刷出去、再关流发生在容器 `initialize` 时。所以"在 controller `return` 之前写完 error 帧"是可行的，但**必须用真 Tomcat + `curl` 实测确认帧真的刷出来了**——只做字节码推断会得出"可能丢失"的错误结论。这是"推断不能替代实测"的又一例。
10. **验收脚本里对已解析的 `data` 再调 `_json()` 会走兜底分支、对 dict 切片抛 `KeyError`**：`parse_frame` 已经把 `data:` 解析成对象，脚本再调一次 `_json()` 且该分支按字符串切片处理，就会对 dict 抛 `KeyError: slice(...)`。**教训：解析函数要幂等或显式分层，"已解析对象"和"原始字符串"不能混用。**（这条是 QA 脚本自己的坑，留档备查。）

### 下一步

- ~~补 T06 的**全量单测与端到端回归**~~ **→ 已闭合**：见下方「验收实录」，全量单测 170 条 0 失败、`t06smoke`/`t06sse`/`t05e2e_async`/`t06negative`/`t06deadlock_stress` 全跑完（`t06negative` 3 条 INCONCLUSIVE 已由确定性单测替代，1 条 WARN 登记 T07）。
- **T07 用例生成编排**（**T07-1 已交付，见阶段 9**；T07-2/T07-3 仍未开始，仍是下一阶段主体）：`generation/orchestrator/{CaseGenerateOrchestrator,RetryPolicy,DegradePolicy,GenerationContext}`，复用 `TaskItemHandler` 注册 `CASE_GENERATE` 场景；`test_point → test_case` 外键与三档降级（坏 JSON 修复 / 宽松解析 `confidence=0.5` / 上游 500 重试后置 FAILED）。
- 前端（**未闭合，进 T07**）：评审页补**批量采纳**与"跳到原文"；任务列表页 / 任务详情页接 SSE（`frontend/src/api/{task.ts,sse.ts}`、`hooks/useTaskStream.ts` 这三个文件 T06 尚未交付）。
- 回改 §6 T06 的 ⑧⑨⑩（**未闭合，见 §11.1 D-33**）：当前 `nowUtc` 由 `LocalDateTime.now(ZoneOffset.UTC)` 硬取，尚未注入 `Clock` bean。
- **进 T07 的遗留清单**（本次验收新增/加强）：**终态之后新订阅的 emitter 无人 `complete`（现居首位）**（`ProgressPublisher.finish()` 只关"当时那批连接"，30 分钟超时兜底）、D-36 `test_point` 并发插入死锁（观测项）；以及既有 P1 重复取消幂等短路（会重放终态事件）、P2 `create` 缺二选一校验、`shouldPushProgress` 非原子、手工补点 `source_ref=""`、`created_by` 历史 NULL 放行、D-33 `Clock` 未注入；**T07-1 新增 D-42**（任务级 `error_code` 恒 `E_INTERNAL`，观测项）。**D-38~D-41 是已定版的决策，不进遗留清单。**~~D-35~~ **已闭合**：SSE 错误响应改为「`200` + `event: error`」，见下方「D-35 真容器验证实录」与 §11.1 D-35/D-37。

### 验收实录（阶段 8 收口数据）

环境：`AI_PROVIDER=mock` + `-Dserver.port=8097`，Flyway 停在 **v6**。

| 项目 | 结果 |
|---|---|
| `t06preflight.py`（GATE 0） | **8/8 PASS**（V6 已 apply / `unit_ref=varchar(200)` / `produced_items` 存在 / `error_msg` NOT NULL / `source_ref` 200 / `path` 500 / 唯一索引在 / **生效 provider=mock**） |
| `t06smoke.py` | **12/12 PASS**（终态 SUCCESS、item 数=接口数、落库、`operation:` sourceRef、重复抽取不翻倍、取消终态 40901） |
| `t06sse.py` | **46/46 PASS**（首帧 snapshot 14 字段、`total`=接口数、progress/item 帧、`done` 终态帧、`Last-Event-ID` 重连拿 DB 快照、无 token 不挂死；**④ 段重写后新增 7 条契约断言**：无 token / 过期 token / 任务不存在 / owner 不匹配四类失败均 `200` + 单帧 `event:error` + 立即关流、`data` 为 JSON 对象且含 `code/message/traceId`） |
| `t05e2e_async.py` | **106/106 PASS**（T05 语义全迁移：去重不翻倍、幽灵点置 SUPERSEDED 不删除、人工修订/停用重抽后保住、`done=success+failed+skipped` 从子表现读） |
| `t06negative.py` | **61 PASS / 0 FAIL / 1 WARN / 3 INCONCLUSIVE**，退出码 **2**（NEG-7 的 ③④ 改按新契约后 +2 条） |
| `t06deadlock_stress.py`（新增） | **8/8** 与 **16/16** 并发建任务全成功、全部收敛、死锁日志 0 |
| 全量单测 | **170 / 0 fail / 0 error / 7 skipped**（`BUILD SUCCESS`） |

**3 条 INCONCLUSIVE 及确定性替代**（均为「未验证」而非通过，不得据此写"测试全绿"）：

1. NEG-1 in-flight 竞态黑盒未复现 → 由 `CancelInFlightRaceTest` 的 `CountDownLatch` 确定性覆盖（含正对照）。
2. NEG-3 `getMessage()==null` 黑盒不可达 → 新增 `GenTaskWorkerTest` 用例（stub handler 抛无 message 的 `NullPointerException`，断言 `error_msg` 非空、item 不卡 RUNNING）。
3. NEG-7 owner 不匹配分支 → 库里 `gen_task.created_by` 有外键 `fk_gen_task_created_by → app_user.id` 且只有 admin 一个用户，构造"别人的任务"需插 `app_user` 行或关 FK 检查，两条都不走 → 新增 `GenTaskControllerStreamAuthTest` 覆盖（9 例：空 token 短路（`null` 与空白各一例，且 `verify` 零调用）/ owner 不匹配 40301 且 register/pushSnapshot/complete 全部 never / owner 匹配 / `createdBy=NULL` 放行 / 终态任务推完快照即 complete / 终态 `CANCELED` 的 `InOrder` 顺序（register→pushSnapshot→complete，防快照被关流吞掉）/ 新增 `invalidToken…`（40102 走 error 帧）/ `missingTask…`（40401 走 error 帧））。

**1 条 WARN**：NEG-5「终态之后新订阅的 emitter 无人 `complete`」，属 `ProgressPublisher.finish()` 只关"当时那批连接"的已知窗口，30 分钟超时兜底，登记 T07。

### 新偏差（本轮验收抓出，明细见 §11.1 D-34~D-37）

- **D-34（已修复 + 已压测验证）**：建任务子项插入的 InnoDB 死锁 —— `create()` 逐条 `insert`（60 条 = 60 个提交点）与 `claimBatch()` 在两张表上锁序交叉，实测修复前同一实例 **30 次建任务 2 次失败（≈6.7%）**。修复三件套（`TransactionTemplate` 独立事务 / `PessimisticLockingFailureException` 有界重试 / 去掉 `UPDATE` 子查询）后自然流量 19 次 0 失败、8×60 与 16×60（960 插入）压测全成功、死锁日志 0。
- **D-35（已闭合，2026-11-08 修复）**：`@GetMapping(produces=text/event-stream)` 下异常响应内容协商失败 → 浏览器式 `Accept` 得到 **HTTP 500 + 空 body**，token 过期时前端拿不到 body、无法区分"鉴权失败"与"服务崩了"。**已按用户裁决改为「`200` + `event: error`」并在真容器 `curl` 验过四分支**，契约变更见 §11.1 **D-37**（D-29 的「无 token 必须 401」被其取代）。**注意口径：此改动不是为了修重连**——`EventSource` 对非 200 本就是 `fail the connection`（不重连），改的是"让前端能拿到 `code`/`message`/`traceId`"。
- **D-36（观测项，未专门处理）**：修复后实例仍有 3 次 `Deadlock found` 落在 `INSERT INTO test_point`，由 item 级 `attempts` 重试吸收，未观察到任务失败。**不写成"已解决"。**
- **D-37（已实施，契约变更）**：SSE 鉴权/归属/找不到失败的响应形态从「抛异常 → HTTP 4xx + JSON」改为 **「HTTP 200 + `event: error` 帧」**。D-29 的「无 token 必须 401」被本条**取代**（保留 D-29 编号，其行注明"已被 D-37 取代"）。前端契约：`data.code ∈ {40101,40102}` → 跳登录页并置空 token；`40301` → 提示无权限；`40401` → 提示任务不存在；其余 code → 展示 `data.message` 并按"任务执行失败"处理（与 `ProgressPublisher` 推的执行失败 `error` 帧同形，靠 `code` 区分）。

### D-35 真容器验证实录（2026-11-08）

**契约（用户拍定）**：失败改走「`200` + `event: error`」，删掉 `produces` 的 4xx 分支。**口径**：此改动**不是为了修重连**（`EventSource` 对非 200 本就 `fail the connection`），而是让前端能拿到 `code`/`message`/`traceId`。

**`curl -i` 实测（新 jar，同一 taskId）**：
```
HTTP/1.1 200
Content-Type: text/event-stream;charset=UTF-8
Transfer-Encoding: chunked

event:error
data:{"code":40101,"message":"缺少 token，无法订阅任务进度","data":null,"traceId":"f91bd9ca8ed44435"}
```
四条拒绝分支逐条验过（全部 `200` + 单帧 `event:error` + 立即关流）：

| 场景 | code | message |
|---|---|---|
| 无 token / `?token=` 空 | `40101` | 缺少 token，无法订阅任务进度 |
| 伪造 / 过期 token | `40102` | 登录状态异常，请重新登录 |
| 任务不存在 | `40401` | 任务不存在 |
| owner 不匹配 | `40301` | 无权订阅他人的任务进度（**仅单测覆盖**，库内只有 admin 一个用户） |

`Accept: */*` 与 `Accept: text/event-stream` 输出完全一致（不再有"JSON 401 / SSE 500"两套行为）。

**实现要点**：新增 `generation/service/SseErrorResponder#writeError(SseEmitter, ErrorCode, String)`（载荷复用 `ApiResponse.fail(...)`：`code/message/data=null/traceId`，traceId 同源 `MDC`；事件名 `error`；**不发 `id:` 行**；`data(payload, APPLICATION_JSON)` 序列化成 JSON 对象，前端 `JSON.parse(e.data)` 直接可用；`IOException` 改 `completeWithError` 不外抛）；`GenTaskController#stream()` 新增私有 `reject(id, code, message)` 作**唯一拒绝出口**（空 token 短路连 `verify` 都不做 → `verify`/`require`/归属校验包进一个 `try`、`catch (BizException ex) → reject`；`register`/`pushSnapshot`/终态 `complete` 严格在 `try` 之后，拒绝路径**零注册**，否则 emitter 会挂到 30 分钟超时）。**`E_TOKEN_INVALID(40102)` 也走 error 帧**——`tokenService.verify()` 抛的也是 `BizException`，漏掉它就等于"token 过期仍 500 空 body"，而那是线上最常见的鉴权失败。

---

## 2026-11-08 · 阶段 9 · T07-1 用例生成（`CASE_GENERATE` 首次跑通）

### 做了什么

1. **`testcase` 模块落地第一层**（包名不是 `case`，理由见下方决策）：`testcase/domain/TestCase.java`（112 行，19 列与 `V1__schema.sql` 的 `test_case` 一一对应）、`testcase/mapper/TestCaseMapper.java`（67 行，`upsertBatch` / `deleteStaleSeqNo`）、`backend/src/main/resources/mapper/TestCaseMapper.xml`（64 行）、`testcase/dto/CaseDraft.java`（38 行，6 字段）。
2. **`generation/service/CaseGenerateHandler.java`（623 行）**：实现 `TaskItemHandler` 的 `CASE_GENERATE` 场景——读测试点 → 渲染 `case_generate` 模板 → 调模型 → schema 校验 → 幂等落库 → 回写 item。`TaskItemHandler` 是 T06 留的接缝，这次第一次被真正实现（`CASE_GENERATE` 与 `TESTPOINT_EXTRACT` 共用同一套调度/重试/SSE）。
3. **`common/ErrorCode` 新增 `E_LLM_EMPTY(50006)`**：区分"结构合法但模型一条都没产出"与"结构不合规"。
4. **`generation/service/CaseGenerateHandlerTest`（383 行 / 6 例）**：正常落库 / 测试点不存在 / `unit_ref` 缺失 / 空产出 `E_LLM_EMPTY` / `attemptNo = attempts+1` / 重跑裁尾。
5. **新增两个验收脚本**：`t07smoke.py`（24 条）、`t07upsert.py`（7 条，专治真 MySQL 撞键分支）。

### 关键决策与理由

| 决策 | 理由 | 被否决的方案 |
|---|---|---|
| 包名用 `testcase` 而不是 `case` | `case` 是 Java 保留字，`package com.aittest.case.domain;` 编译期直接报「需要<标识符>」，不是"不推荐"而是**编译不过** | 没有替代写法：包名本身非法，不能转义、不能靠类名规避（D-38） |
| `confidence` 语义 = **解析保真度**（`1.000` 正常 / `0.500` 降级 / `NULL` 未评估） | `schema/testcase.schema.json` 的 `items` 是 `additionalProperties: false` 且**没有** `confidence` 字段，模型在结构上写不出来 → "用 draft 自带值"这个分支永远为空 | 在 `CaseDraft` 里加一个恒 `null` 的字段"占位"：只会让人以为"模型会给"（D-39） |
| `steps` 有损映射，`data` 恒空串 | 让模型编"请求体/入参"只会得到**幻觉数据**（它没有真实接口的字段定义）；要 `data` 应从 `api_operation` 的 parameters 派生或前端补录 | 放宽 `testcase.schema.json` 加 `data` 字段：把幻觉固化进用例库，且 T10 执行导入会当真（D-40） |
| 新增 `E_LLM_EMPTY(50006)` 而不是复用 `40003` | `40003` 属 4xxxx 段（请求侧"没有可处理的数据"），归错段会让**上游模型问题显示成用户操作问题**，并且这类在监控里消失 | 记 `ok(0)`：任务显示"跑完了但没产出"，真实原因彻底丢失（D-41） |
| 幂等用 `ON DUPLICATE KEY UPDATE` 而不是 `INSERT IGNORE` | worker 在 `attempts<3` 时会重跑同一 item，撞 `uk_test_case_item_seq` 是**必然**的；`IGNORE` 会静默保留上一次内容，"重试成功"与"内容更新了"变成两件事，日志里没有线索 | `INSERT IGNORE` + 事后比对：多一次读，且仍要判断"该不该覆盖" |
| upsert 与 `deleteStaleSeqNo` **不加事务** | `execute()` 内有 LLM 调用（分钟级），套事务会把连接按分钟级占住 → 池子很快枯竭（§8.7 第 4 条） | 一个大事务包住两步：用"连接池耗尽"换"极小窗口的一致性"，代价不对称 |
| `attemptNo = item.attempts + 1`，**不**沿用抽点侧恒写 `1` | 恒 `1` 会让 `llm_call_log.attempt_no` 永远只有 1，**首次通过率永远虚高**、重试成本无法归因 | 与抽点"保持写法一致"：一致性不能高于指标正确性 |
| `LlmException` 在 handler 里**不 catch** | 瞬态失败（超时/429/上游 5xx）交给 `GenTaskWorker` 的退避重试 3 次；在 handler 里 catch 等于把"可重试"降级成"这条 item 直接失败"，还绕过 `next_run_at` 退避 | handler 内自己重试：重试逻辑写两遍，且与引擎的重试计数互相打架 |

### 踩坑记录

1. **mock 下 `cost_micro=0` 不是"没回写"**。一度按缺陷查，实测根因：`MockLlmProvider` 固定回显 `model="mock-model"`，而 `model_price` 里这一行的**单价种子就是 0/0**（注释写明"成本才能算成 0 而不是查不到单价"）。**判别方法**：先看 `llm_call_log.model` 有没有回显，再看 `model_price` 对应行的单价——两者都在而 `cost_micro=0` 才是问题。`prompt_tokens` / `completion_tokens` / `latency_ms` 本次实测全部非 0（803 / 236 / 1ms 等），是真的。
2. **直连 mysql CLI 写 `DATETIME` 会按本机时区，与应用 UTC 口径差 8 小时**。在 `t07upsert.py` 里手写 `UPDATE ... status='ACCEPTED'` 后发现 `created_at=07:38`（应用写的 UTC）与 `updated_at=15:40`（CLI 写的本地时间）**并存于同一行**。修法：mysql CLI 加 `--init-command=SET time_zone='+00:00'`。**任何直连库改数据的验收脚本都要带这个**，否则时间列比较是跨时区对比，结论不可信。
3. **新建验收脚本会连踩四个坑**（都不是产品缺陷，但每写一次都要再踩一遍）：① 数据库口令必须从 `.env` 读、走 `MYSQL_PWD` 传给 `D:/mysql/bin/mysql.exe`（裸 `mysql` 会命中另一份客户端，命令行 `-p` 还会打印不安全告警）；② `DELETE FROM doc_source WHERE id IN (SELECT id FROM doc_source ...)` 触发 MySQL **1093**，要套一层派生表 `IN (SELECT id FROM (...) t)`；③ 上传端点是 `POST /api/docs`（`consumes=multipart/form-data`），**不是** `/docs/upload`；④ `test_point` **没有 `seq_no` 列**，排序要用 `ORDER BY operation_id, id`。

### 验收实录（T07-1）

环境：`AI_PROVIDER=mock`（覆盖 `.env` 里的 `openai-compatible`，`t06preflight` 有硬门禁）+ `-Dserver.port=8097`。

| 项目 | 结果 |
|---|---|
| `mvn -o test` | **176 / 0 fail / 0 error / 7 skipped**（基线 170，+6 = `CaseGenerateHandlerTest`） |
| `t06preflight.py` | **8 PASS / 0 FAIL**，`provider=mock`（硬门禁） |
| `t07smoke.py` | **24 PASS / 0 FAIL / 0 INCONCLUSIVE**，退出码 0。1 篇 OpenAPI（3 接口）→ 抽出 **9 个测试点** → 建 `CASE_GENERATE` 任务 → 终态 `SUCCESS done=9 total=9 produced=17` → `test_case` **17 行** |
| `t07upsert.py`（本轮新写） | **7 PASS / 0 FAIL** |
| `t06negative.py` | **63 PASS / 0 FAIL / 1 WARN / 3 INCONCLUSIVE**，退出码 **2**（绝不折叠成通过） |

**`t07smoke.py` 的断言链**：状态全 `DRAFT`；`prompt_version` 形如 `case_generate.v1`；`content_hash` 64 位 hex；同 item 内 `seq_no` 从 1 连续；每 item `prompt_tokens>0` / `latency_ms>0`；首次执行 `attempts=1`；`item.case_count` 之和 = 行数 = `gen_task.produced_items`；台账 9 行 `CASE_GENERATE` 且 `attempt_no` 从 1 起。

**已知边界（T07-2）**：三档降级未接（`parsed.truncated()` 只打 WARN，`confidence` 由服务端定 `1.000`）、`{{few_shot}}` 传空串（素材库未建）。

**`t07upsert.py` 为什么必须存在**：`CaseGenerateHandlerTest#rerunTrimsStaleSeqNo` 是纯 Mockito——mapper 被 mock 掉，`ON DUPLICATE KEY UPDATE` 的**撞键分支从未在真 MySQL 上跑过**，而 `t07smoke` 首跑只走 INSERT 分支。本脚本用与 `TestCaseMapper.xml` **逐字一致**的语句跑第二遍，实测：行数不变、`id` 不变、`created_at` 不变、`status` 保持 `ACCEPTED`（**评审结果没被打回 DRAFT**）、`title` 被覆盖、`updated_at` 递增、`deleteStaleSeqNo` 只删 `seq_no > 本次条数` 且**不波及同任务其它 item**（实测其它 item 的 13 行未受影响）。

**NEG-2a 换语义后通过**：非法 `unitRef` → 3 个 item 全部 `E_PARAM_INVALID` 逐条隔离、任务仍收敛到 `FAILED`、无 item 卡在 `PENDING/RUNNING`。

### 新偏差（明细见 §11.1 D-38~D-42）

- **D-38（被动变更）**：包名 `case` → `testcase`。`case` 是 Java 保留字，`package com.aittest.case.domain;` 编译期即报「需要<标识符>」。三层与类名不变，纯命名、无行为差异。
- **D-39（语义定版）**：`confidence` = **解析保真度**（`1.000` 正常 / `0.500` 降级 / `NULL` 未评估），**不是模型自评置信度、更不是内容质量分**——不得用它做用例排序或抽检依据。`V1__schema.sql` 里那行列注释已过时。
- **D-40（有意有损）**：`steps` 落库 `[{"no":n,"action":"…","data":""}]`，**`data` 恒空串**。断言它非空会永远红（这是设计）。
- **D-41（新码 + 线上不可达）**：`E_LLM_EMPTY(50006)` 归"模型输出"族；挂了 schema（`minItems: 1`）时空数组**先**被判 `50005`，**线上看到的是 `50005`**，`50006` 只在无 schema 可校验时兜底。
- **D-42（观测项，未修）**：任务级 `error_code` 恒 `E_INTERNAL`——item 三条都是 `E_PARAM_INVALID`，任务级仍是 `E_INTERNAL`，前端显示"系统内部错误"。**与 D-25 同类：账本与用户看到的两套码对不上，只看台账的监控正常。**

### 下一步

- **T07-2**：`generation/orchestrator/{CaseGenerateOrchestrator,RetryPolicy,DegradePolicy,GenerationContext}` —— 三档降级（repair 重试 / 宽松解析落 `confidence=0.500` / 上游 500 重试后置 FAILED）、幂等编排、few-shot 素材库。
- **T07-3**：前端 4 页面（`TaskList` / `TaskCreateDialog` / `TaskDetail` / `ProgressTimeline`）+ `api/{task.ts,sse.ts}` + `hooks/useTaskStream.ts`（这三个 SSE 文件从 T06 顺延至今**仍未交付**）。
- **从阶段 8 顺延、仍未闭合**：终态之后新订阅的 emitter 无人 `complete`（**现居遗留清单首位**）；D-33 `Clock` 未注入；D-36 `test_point` 并发插入死锁（观测项）；D-42 任务级 `error_code`（观测项）。
- **D-41 的边界**：`50006` 线上不可达是设计，但"没有 schema 可校验"那条兜底路径**没有实测**——目前没有任何调用方会走到它。

---

## 2026-11-08 · 阶段 10 · T07-2 / T08 / T07-3（降级落地 + 评审闭环 + 前端合批）

### 做了什么
1. **T07-2 三档降级**（`c995250` / `9334731`）：`CaseGenerateOrchestrator` + `RetryPolicy` + `DegradePolicy` + `GenerationContext`；repair 重试走 `scene="CASE_REPAIR"` 单列成本，`attemptNo` 不递增（恒 `item.attempts + 1`）、`request.degraded` 恒 `false`；宽松解析落 `confidence=0.500`。
2. **T08 评审闭环**（`e3cf9c2` / `c0f19eb`）：三分支（`ACCEPT` / `REJECT` / 编辑走 `PUT`）+ `old_content` / `new_content` 快照 + 批量（`ids[]`、上限 200、只处理 `DRAFT`）+ 台账只追加不覆盖；补 `GET /api/tasks` 与 `/api/tasks/{id}/items` 两个前端必需端点。
3. **T07-3 前端合批**（`3c6f86c`）：SSE 地基（`api/sse.ts` + `hooks/useTaskStream.ts`）+ 任务页 + 评审看板；45s 看门狗 = 2×20s 心跳周期。
4. **文档收口**（`2bdb6ba`）+ 列表默认排序修正（`5f73481`，补齐 10 例回归）。

### 关键决策与理由
| 决策 | 理由 | 被否决的方案 |
|---|---|---|
| `/items` 作为明细唯一权威来源 | 任务详情 VO 与 SSE `snapshot` 字段集不一致时，明细只能有一处真源 | 让前端按 `snapshot` 自己拼明细（字段漂移后无法排查） |
| `progress` 是否还在来 **不能**当存活判据 | 长耗时的单个 item 期间 `progress` 本来就不动，据此判死会误杀正常任务 | 用 `progress` 间隔做超时 |
| 45s 看门狗写成 `2 * HEARTBEAT_MS` 而非字面量 | 字面量与后端 20s 心跳是两处真源，改一处就静默失配 | 硬编码 `45000` |

### 踩坑记录
- **D-47 / D-48**：`done` 帧实际推完整 `snapshot`（无 `summary` 子对象）、`error` 帧有**两种形状**（订阅拒绝 = `ApiResponse.fail` 同形 / 执行失败 = `{taskId,errorCode,errorMsg,summary}`）。照文档写 `payload.summary.totalCases` 恒 `undefined`，`status` 判不出 → 前端无限重连，而 `tsc` 不报错、`t06sse` 也不红。
- **D-49 / D-50 / D-51**：建任务入参多必填 `scene` 且无 `templateCode`；用例四行 CRUD 的筛选维度 / HTTP 方法 / 字段名（`PATCH`→`PUT`、`caseIds`→`ids`）全对不上；任务详情 VO 无 tokens/cost。这 5 条的共同特征是**表象在网络层、根因在契约层**。
- **D-46 仍未解**：`t07degrade.py` E 段 few-shot 探针的 `max(20, 15%)` 阈值在 p1=774 时要求 +116 token，而单条素材实测约 36 token，**结构上永远判不出来**，结果 INCONCLUSIVE，**未折算成 PASS**。

### 下一步
- T09 用例导出（`cases.xlsx` 双 sheet + `junit.zip`）、T11 指标看板。`#48 /api/metrics/execution` 依赖 T10，届时单独裁决。

---

## 2026-11-12 · 阶段 11 · T09/T11 导出与指标看板（验收 2026-11-13）

### 做了什么
1. **T09 导出后端**（`23a5781` / `b29f5aa`）：`GET /api/export/cases.xlsx`（「用例」「统计」双 sheet，单次查询同一批快照）+ `GET /api/export/junit.zip`（用例 `*.java` + zip 内自带 `Test`/`DisplayName` 注解桩与极简 runner + `README.txt`）。
2. **T11 指标后端**（`5ae1165`）：`MetricsMapper.xml`（仓库首个 `<if>` 动态 SQL，11 个 `<select id>`）+ 5 个端点（#43~#47）+ 16 例单测。
3. **契约对齐**（`151dd9a`）：`long` → JSON string（camelCase VO 计数改 string + `Number` 转换），7 例回归。
4. **前端**（`425fad1` / `3f0454b` / `7f9591b`）：`CaseExportDialog` + 指标看板 4 Tab（A/B/C/D 0 值分层）+ 首页过渡期三入口。
5. **文档**（`3aadcca`）：增量 PRD v1.2（521 行）+ `docs/07` 增量设计（1340 行）+ 架构文档契约同步。

### 关键决策与理由
| 决策 | 理由 | 被否决的方案 |
|---|---|---|
| **#48 `/api/metrics/execution` 本批跳过、待 T10** | `execution_run` / `execution_result` 两表已建但 **0 行**、T10 导入链路未交付，端点交付即空态且不满足 §6 T11 验收 ①–⑦ 任何一条 | 写占位实现 / 放假数据（违反「不写永久占位」纪律） |
| **JUnit classpath 方案 A（纯 JDK + 极简 runner）** | fat-jar 运行时不含 `junit-jupiter-api`；方案 A 无外部依赖且能真调 JDK17 `JavaCompiler` 编译全部 `.java`。zip 内自供注解桩，编译期零外部 jar | 依赖 `~/.m2` 的 classpath（环境不可控）/ 打包 junit jar（体积与版本双负担） |
| **导出端点不声明固定二进制 `produces`** | 失败要返回 `application/json` 的 `ApiResponse`（HTTP 200 + `code!=0`）；声明固定 `produces` 后 `@RestControllerAdvice` 吐 JSON 会内容协商失败 → **500 空 body**（同 D-35 的 SSE 陷阱） | `produces = {json, binary}` 二选一（Spring 仍会按 `Accept` 先选错） |
| **成本严格 `SUM(llm_call_log.cost_micro)`，不在读取侧现算** | 台账是唯一真源；读取侧按 `model_price` 现算会拆出第二套口径 | 查询时 join 单价表实时乘算 |
| **指标聚合 SQL 不加通用 `deleted=0`** | 五张指标源表（`gen_task` / `gen_task_item` / `test_case` / `case_review` / `llm_call_log`）**均无 `deleted` 列**；只有 `doc_source` 带软删 | 统一加 `deleted=0`（直接 `Unknown column`） |
| **`from/to` 用固定 `+8` 偏移算术，不引入 `ZoneId`/`Clock`** | 与 SQL `DATE(created_at + INTERVAL 8 HOUR)` 同源，避免出现第二条时间真源；全链路存储与传输仍 UTC | `ZoneId.of("Asia/Shanghai")`（引入 DST 语义与第二条真源） |
| **`ChartCard` 不 import recharts**（D-53） | 看板 4 个 Tab 只有 2 个真画图；壳若引 recharts 会让「打开看板」无条件拉起整个 `charts` chunk | 壳内统一渲染图表（懒加载边界失效） |
| **`Home.tsx` 补过渡期三入口**（D-54） | 原设计只覆盖「导出入口挂在哪」，漏了「页面怎么到达」——T07/T08/T11 三页**无导航可达** | 维持「零改动」（真实用户只能手敲地址） |

### 踩坑记录
- **D-52**：`#36 junit.zip` 原设计无 `statuses`，而 xlsx 默认只导 `ACCEPTED,EDITED` 4 条；zip 若默认全量会生成 10 个含 `DRAFT`/`REJECTED` 的 `.java`，**同一任务两份产物条数对不上且不报错**（静默口径分叉）。已增可选 `statuses` 与 #35 同源，共用同一段白名单校验。
- **D-55（列表默认排序方向，修复只覆盖一半）**：`GenTaskService.java:429`、`CaseService.java:190` 已在 `5f73481` 改为 `boolean isAsc = !desc` 并补 10 例回归（MP 的 `orderBy(condition, isAsc, column)` 第二参是 `isAsc`，直传 `isDesc()` 会把默认排序取反成 `created_at ASC`）；`DocService.java:727-731`、`TestPointService.java:251-255` **仍未修**——属已验收代码，本批不纳入（会翻转既有黑盒脚本的列表顺序断言），**留作独立技术债**。
- **`docs/01:834` 的 `doc_id` 表述与 DDL 不符**（已在本批订正）：`llm_call_log` **没有 `doc_id` 列**（只挂 `gen_task_id`），按 docId 归集成本必须走 `llm_call_log → gen_task → doc_source` 两级 JOIN；照文档写 `l.doc_id` 直接 `Unknown column`。
- **`ONLY_FULL_GROUP_BY` 的「假绿」风险**：用 `SET SESSION sql_mode=''` 绕过时，开发机绿、策略更严的托管实例报 `1055`；而关掉 `sql_mode` 的会话里验收**同样绿**。本批的验收脚本加了会话闸——`@@SESSION.sql_mode` 含 `ONLY_FULL_GROUP_BY` 与 `STRICT_TRANS_TABLES`（六项，`@@GLOBAL` 一致、全程无 `SET`），`MetricsMapper.xml` 的 11 个 `<select id>` × 2 个 `<if>` 分支 = **22 条 SQL 同会话实跑** rc=0、均返回结果集、无 `1055`；并加了**负例对照**（同会话执行违规 `GROUP BY` 确实抛 `ERROR 1055 (42000)`），否则分不清「真绿」与「宽松模式下的假绿」。
- **成本 `0` 有三种成因**，不能混为一谈：`MOCK_PRICE_ZERO`（mock 单价为 0）、`FAILED_CALL_NO_TOKEN`（失败调用无 token）、`MODEL_PRICE_MISSING`（`deepseek-flash` **没有单价行**——这是**配置缺口**，不是代码缺陷）。把配置缺口误判成代码缺陷会把排查方向引向 `MockLlmProvider`，而真正方向是 `.env` 的 `AI_MODEL` 或历史验收脚本传参。
- **分母 0 不能渲染成 0%**：「人还没评审」被渲染成「采纳率 0%」会被读成「模型全错了」，与 §8.3「未评审不代表模型失败」的立场直接相反——口径正确但表达误导，且不报错。前端改为置 `hasReviewData=false`、显示「暂无评审数据」。

### 验收事实（2026-11-13）
- **黑盒**：`t09t11.py`（仓库外，1945 行）→ **200 PASS / 0 FAIL / 0 WARN / 0 INCONCLUSIVE，exit 0，VERDICT PASS**（mock provider）。此前汇报的 192 已作废。
- **后端单测**：**265 / 0 fail / 0 error / 7 skipped**（7 skip 是 `IdGenSeedingTest` 需真库口令）。
- **前端**：5 files / 57 tests 全绿、`tsc --noEmit` 0 错、`vite build` 通过（`charts` chunk 403.87 kB / gzip 110.68 kB、`OverviewDashboard` 10.62 kB）。
- **范围边界**：`#48` 未交付（待 T10）；`§6 T11` 依赖列保持 `T07, T08, T10` **不变**（那是设计依赖，不是缺陷）。

### 下一步
- **T05 收口（本批）**：`docs/01` §4.2 七端点契约、§2.2 目录树 `junit.zip`、§12 追加 13 条依赖（74 → 87）、§11.1 登记 D-52~D-54（51 → 54）、`docs/01:834` 订正、`CHANGELOG` 补 `### Changed`。
- **T12**：真 `AppShell` 上线后统一收口 `Home.tsx` 的三入口过渡导航（D-54）。
- **技术债**：`DocService.java:727-731` / `TestPointService.java:251-255` 的列表顺序问题（D-55 未修部分）；D-42 任务级 `error_code`（观测项）；D-46 few-shot 探针改二值判别。

---

## 2026-11-13 · 阶段 12 · T12 前端集成与交互打磨（T12-1 / T12-2a / T12-2b）

### 做了什么
1. **T12-1 布局壳**（`4ae14fd` 7 文件 +586/−124、`19c2eb3`、`f75b36c`、`de76216`）：`components/layout/{AppShell,SideNav,TopBar}.tsx` + `constants/nav.ts`。导航改成**单一真源**——`NAV_ITEMS` 同时驱动侧栏渲染、选中态判定与顶栏标题，路由表不再各自硬编码字符串。`routes/index.tsx` 改为 `AppShell` 布局路由套 10 条业务路由，`PageBoundary` 逐条叶子包。
2. **T12-2a 测试点评审落地页**（`d73e0ba` 8 文件 +464/−35）：`pages/testpoints/TestPointPicker.tsx` + `/testpoints` 静态路由 + 侧栏第 6 项 + `constants/docStatus.ts`。至此「6 个模块 + 概览 = 侧栏 7 项」，验收① 的结构性缺口闭合。
3. **T12-2b 状态映射收口**（`4024747` 4 文件 +278/−13）：`DocDetail` 删本地拷贝表改引共享函数、`StatusChip` 未知码加显式告警、补 4 例测试。
4. **README 重写并推送**（`9151e0d` +121/−32）：原来停在 T01、落后 6 天；补默认账号、11 个核心端点、进度表与「已实现的关键设计」。Gitee 与 GitHub 双端同步。

### 关键决策与理由
| 决策 | 理由 | 被否决的方案 |
|---|---|---|
| `Suspense` 边界**只放叶子**，`AppShell` 不包 `PageBoundary` | 否则任一页面 chunk 未就绪时整个壳（含侧栏顶栏）被替换成转圈，用户点一下菜单整页闪白 | 在壳上包一层统一兜底——省事但把「局部加载」放大成「整页重绘」 |
| 壳内兜底高度用 `height:100%`，`/login` 保持 `minHeight:100vh` | `main` 是 `h-screen` flex 列里的 `flex-1 + min-h-0`，used height 确定，`100%` 能解析且与 `main` 等高 → 偏移 0。`/login` 父级是 `body`（无确定高度），`100%` 会塌成 0 | 统一用一个值——两边父级完全不同，统一必然有一边错 |
| 解析状态**与三域并列**而非并入 `ALL_STATUS`（D-56） | 并入会让 `isStatusKey('PARSED')` 对任务/用例/执行结果三个域都返回 `true`，语义污染 | 塞进 `ALL_STATUS` 让 `StatusChip` 直接可用——一行代码换来三个域的判定失真 |
| `StatusChip` 去重放 module 级 `Set<string>` | `useRef` 是每实例一份，一屏 50 行脏状态就是 50 条 warn；被刷屏的护栏等于没有护栏 | `useRef` / 组件内 state |
| 未知码**显示仍是裸 code** | 那是排障唯一的线索；换成「未知状态」等于把线索删掉让人去翻控制台 | 统一显示「未知状态」——界面干净了，问题变难查了 |
| 告警**不放** `getStatusMeta()` | 它是纯查表函数，未知返回 `null` 是写进 `status.test.ts:74` 的契约；塞副作用会污染所有潜在消费者 | 在查表函数里打 warn——一次调用链会打出多条，且测试断言被打乱 |
| `StatusChip` 告警**不做** `import.meta.env.DEV` 门控 | vitest 的 mode 是 `test`、`DEV` 恒真，门控在 CI 上买不到任何东西；而生产恰恰是「后端加枚举、前端没同步」最先暴露的地方 | 只在 DEV 告警——等于上线后彻底静默 |
| 测试三层：正例 / 负例 / **哨兵** | 前两例分工是「守既有行为」，对「引用 vs 拷贝」**零判别力**；只有哨兵能证明真在调用共享函数 | 只写正例——两份实现都过，测了等于没测 |
| 哨兵值用 `__SHARED_FN__` 而非中文 | 详情页中文文案密集，中文哨兵有撞车误绿风险；全下划线英文串全仓唯一 | 中文占位串 |
| **撤掉** `vi.resetModules()` | `vitest.config.ts` 默认 `isolate: true`，每文件全新模块注册表，本就不需要；而带 hooks 的组件 + resetModules 会出 `Invalid hook call` 或 emotion style cache 分裂。且它对**静态 import 是 no-op**——要真生效必须 reset 后动态 `await import()`，那会连带重求值 `react`/`@mui/material` | resetModules + 动态 import——为了一条测试引入双 React 风险 |
| QA 按**提交 diff** 判，忽略脏工作区 | 工作区被下一批改动占住时，红绿无法归因到提交；按 diff 判同样可归因，且不用重跑 | 要求 QA 等干净工作区——串行等待，成本换不到额外信息 |

### 踩坑记录
- **`PageBoundary` 兜底高度改了两轮才对**：第一轮 `100vh`，比 `main` 高 49px，闪出多余滚动条；第二轮改 `40vh`，溢出归零但**转圈偏上**（1280 下 −187px、1920 下 −271px）。**我第一轮只验了「有没有滚动条」，没验「还居不居中」** —— 修溢出的时候顺手把居中也测一遍，否则修掉一个症状换来另一个。最终 `height:100%`，真 Chrome 实测两个断点偏移都是 0。
- **我一度断言「`h-full` 拿不到参照」，这话只在 `/login` 成立**：壳内 `main` 是 `flex-1 + min-h-0`，高度确定，`100%` 能解析。当时把「`/login` 的特殊性」泛化成了全局结论，已更正。
- **`f75b36c` 误带他人探针的临时 `export function PageBoundary`**：另一个 worker 的临时改动挂在工作区时我 `git add routes/index.tsx` 提交，把半成品打包进仓库（`de76216` 收回）。规矩：`git add` 前对非本人改动先 `git diff`；worker 做探针实测一律改用工作区外副本。
- **`DocDetail` 漏收，根因是我的规格**：我把「渲染解析状态的地方」数成 2 个（`DocList` + 新落地页），实际 3 个（还有 `DocDetail`）；同时又下了一条「除 `DocList` 外不动其他业务页面」的死约束。**数量数错 + 禁改范围写死，两条叠加必然漏**。后来是 grep `PARSE_STATUS_META[` 数出 2 处下标才发现的。
- **我给 `StatusChip` 的规格本身是错的**：让新页面「状态用 `StatusChip`，传 `code={row.parseStatus}`」，但 `PARSED` 不在 `ALL_STATUS`（`grep -c` = 0）→ 会渲染成灰色裸英文，与 `/docs` 页绿色「已解析」不一致。改用 `Chip` + `getParseStatusMeta`。
- **「负例测同源」这个说法是假的**：未知 code 恰好是两份实现**唯一逐字重合**处（都是 `?? { label: code, color: 'default' }`），改前改后都过。改为「负例守**既有行为**」（后端加枚举时详情页别白屏），并明确写「本例不是用来防拷贝的」。
- **`grep -c` 计数为 0 时退出码是 1**：读数时不能只看退出码，`0` 仍会打印。
- **Vite dev server 反复崩 4 次**：新增 MUI 图标（`ChecklistRounded`）触发依赖重优化，要删 `deps_temp_*`（108/168/174/175/269 个文件），撞本机批量删除保护（阈值 50）→ Vite 未兜住异常直接退出 → 5173 变 502。**修法是不删、移出**到仓库外的临时目录（可回溯）再重启。属环境限制，但**每次有人加新图标都会复发**。
- **探针页首次量出 `mainClientHeight: 28`**：探针没 import `@/index.css`，Tailwind 的 `h-screen`/`flex-1`/`min-h-0` 全部失效。补 import 后 `main=656`（1280）/`936`（1920），数据才有效。
- **Vite 只监听 IPv6**：`http://localhost:5173` 正常、`127.0.0.1:5173` 是 000；curl 必须加 `--noproxy '*'`，否则走环境代理拿 502。

### 验收事实（2026-11-13）
- **前端**：**10 files / 86 tests 全绿**（基线 82 + 本轮新增 4）；`tsc --noEmit` **0 错**；`vite build` 通过，`charts` 仍是独立 chunk（403.87 kB / gzip 110.68 kB）。
- **映射表收敛**：`grep -c 'PARSE_STATUS_META\['` 从 **2 → 1**（只剩 `docStatus.ts:59` 函数本体）。
- **变异自证**：把 `DocDetail` 退回本地拷贝表后复跑，**正例与负例仍绿、哨兵 `__SHARED_FN__` 变红** —— 与设计意图一致（前两例本就不承担判别职责）。
- **浏览器层（仅 T12-1）**：QA 用 Playwright + 本机 Chrome 实跑 **9 路由 × 1280/1920 = 18 组全 PASS**（`scrollWidth === clientWidth`、`body.scrollHeight === innerHeight`、Drawer 恰好 240px）。
- **范围边界**：`/testpoints` 落地页（T12-2a）**只有 jsdom 覆盖，没进过浏览器**；`getParseStatusMeta` 的未知码分支**没有直接单测**（在 `DocDetail.test.tsx` 里被 mock 包了一层，只间接走到）。这两条如实记账，不写成「验过了」。
- **未完成的眼验项**：`/metrics` 四个 Tab 的真实渲染、`/cases` 导出弹窗、xlsx 用 Excel 与 WPS **各打开一次**验中文不乱码。

### 下一步
- **T12-3**：其余页面的三态全量盘查 + `grep -rn 'alert('` 清零核对 + `enqueueSnackbar` 统一收口验证；补 `getParseStatusMeta` 未知码直接单测。
- **T12-1 遗留**：`Home.tsx` 的三入口过渡导航待 `AppShell` 稳定后删除（D-54）。
- **技术债**：`DocService.java:727-731` / `TestPointService.java:251-255` 列表默认排序方向（D-55 未修部分）；D-42 任务级 `error_code`；D-46 few-shot 探针改二值判别。
- **待接入**：仓库当前**没有 CI**（无 `.github/workflows`），接入时建议对「测试文件数」加下限断言——本轮就出现过用例数从 82 涨到 86 而无人核对的窗口。

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
