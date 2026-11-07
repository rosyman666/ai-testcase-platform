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
- **T03 文档接入与解析**：四种格式上传解析（Markdown 按标题切章节 / TXT 按空行切块 / DOCX 抽段落与表格 / OpenAPI 按 operation 切分，`$ref` 深度 ≤3 且环引用不展开）；`doc` 模块 19 个后端文件（domain / mapper / parser / service / VO / controller）；`config/TimezoneGuard` 启动时区自检（只告警不阻断）；`doc_source` 查重秒回与软删恢复；前端 `pages/docs/{DocList,DocUploadDialog,DocDetail}` 与 `api/doc.ts`
- **T04 模型接入层与提示词模板**：`aicore` 抽象（`LlmProvider` / `LlmRequest` / `LlmResponse` / `LlmMessage` / `LlmUsage` / `LlmException`）；两个 provider（`mock` 确定性无 Key 可跑、`openai-compatible` 走 OkHttp）+ `LlmProviderRouter` 别名路由；**`LoggingLlmProvider` 记账装饰器**（成功/异常/Error 三条路径各落 1 条 `llm_call_log`，`attempt_no` 追加不覆盖）；`ModelPrice` 单价表 + 5 分钟 TTL 缓存（成本以"微元/1M token"计）；结构化输出三件套 `{JsonRepairer,JsonSchemaValidator,StructuredOutputParser}`（四类坏输出可修、schema 错误带字段路径）；提示词模板版本化（`code × version × is_active`，启动幂等 seed 4 个 `.md`，`{{var}}` 渲染）；**`POST /api/prompt-templates/{id}/preview` 试跑端点**（返回 `schemaValid` / `repaired` / `truncated` 三个独立质量信号）；`ErrorCode` 新增 `E_FIELD_TOO_LONG(40004)` / `E_DUPLICATE(40902)` 并由 `DataIntegrityViolationException` 分支分流（`ColumnWhitelist` 只回显白名单字段名，不吐 SQL 原文、不返回唯一键值）；前端 `api/prompt.ts` + `pages/prompts/PromptTemplateList.tsx` + `/prompts` 路由 + 首页业务入口
- **单测**：`StructuredOutputParserTest`(12) / `PromptRendererTest`(7) / `GlobalExceptionHandlerTest`(8) / `LlmExceptionHandlerTest`(5)，全部不依赖网络与数据库
- **T05 测试点抽取与评审**：`testpoint` 模块（`TestPoint` 14 字段 / `TestPointHasher` / `TestPointMapper` / 8 个 DTO / `TestPointService` / `ExtractPlanner` / `TestPointExtractService` / `TestPointController` 6 个端点）；**幂等身份 `content_hash = sha256(doc_id + title + category)`**（分隔符 `\0` 防边界碰撞，`description`/`riskLevel` 刻意不进哈希——人会改）；`ExtractPlanner` 把一篇文档切成 N 个待分析单元（OpenAPI 按 `api_operation`、纯文本按 `doc_section`，调用量 = 单元数，可预测可计费可隔离失败）；去重四态处置（`ACTIVE`/`DISABLED` 一字不动、`SUPERSEDED` 复活、消失的置 `SUPERSEDED` 不删除）；`sourceRef` 服务端权威生成、`category` 白名单由 `CaseType` 枚举现算、schema 校验是硬闸门；前端 `pages/testpoints/TestPointReviewPage.tsx` + `/testpoints/:docId` 路由；**单测 28 条**（`TestPointHasherTest` 9 / `ExtractPlannerTest` 6 / `TestPointServiceTest` 10 / `TestPointExtractServiceTest` 3），全量 67 条 0 失败；**端到端 36 条全绿**

### Fixed

- **去重合并与取代判定写在同一个方法里**（`D-26`）：`applyExtraction` 按**单元**调用，可它内部那段"本轮没再产出的点置 `SUPERSEDED`"是按**整篇文档**写的。多单元文档下单元互相踩——第 1 个单元处理完就把后面 N-1 个单元的产出标成 `SUPERSEDED`，第 2 个再救回来并反手标掉第 1 个的，最终 ACTIVE 数随机。拆成 `mergeExtraction`（单元级去重合并）+ `supersedeMissing`（文档级取代，全单元完成后**恰好一次**）。**根因是两个动作的时间尺度不同**：去重跟着单元走，取代跟着"一整轮抽取"走
- **抽取失败的单元会被误判成"测试点消失了"**（`D-26`）：单元抽取失败时它的产出不在 `kept` 里，`supersedeMissing` 会把该单元的活动点整批置 `SUPERSEDED`——**一次上游超时就静默废掉有效测试点**，与"提前取代"是同一类事故。失败单元已有的点现在算进 `kept`：取代的语义是"原文里已经没有这个点了"，而一次超时给不出这个确认
- **失败隔离丢失，模型一次坏输出炸掉整篇抽取**（`D-26`）：`TestPointExtractService` 的 `try/catch` 在一次编辑中被删掉，类注释却仍写着"失败返回 `failed(...)` 不抛异常"。结果是模型返回坏 JSON 时 `LlmException` 直接冒到全局处理器，整篇文档的抽取报告变成一个错误码——**前面已落库的单元白干，用户只看到"请重试"，看不出是哪个接口抽挂了**。补回 `safeExtract` 边界并新增 `TestPointExtractServiceTest` 3 条锁住该契约

- **模型调用异常落成 `50000`**（`D-25`）：`LlmException` 继承 `RuntimeException` 而非 `BizException`，全局处理器不认识它，一路走兜底分支。用户看到"服务器开小差了"，而台账记的是 `E_LLM_UPSTREAM`——**两套观测对不上，且只看台账或只看 HTTP 状态（都是 200）的检查都不会红**。新增 `aicore/LlmExceptionHandler` 把 `50001~50005` 原样映射，并在 `data.retryable` 回传重试判定（前端据此决定要不要显示重试按钮）
- **修复器把完整 JSON 修坏**：`repairWithMeta` 无条件调用 `completeTruncated`，对已配平的 JSON 也会退到"最后一个安全点"把尾字段切掉——输出**仍合法、仍能解析，只是静默少字段**（`failure_triage` 的 `reason` 就是这样消失的）。改为仅在括号不配平时补全，且逗号只有在它是**数组元素分隔符**时才算安全点
- **`latencyMs` 被序列化成字符串**：全局 Jackson 把 `Long` 转字符串（防前端精度丢失），耗时字段因此变成 `"153"`，前端按字典序排序得到 `"90" > "153"`。试跑 VO 改用 `Integer`
- **原始文件落到系统临时目录**（`D-19`）：`java.io.tmpdir` 会被 `systemd-tmpfiles` 之类清理，重启后 `storage_path` 指向不存在的文件，重解析永久失败。改为 `storage.dir`（默认 `./uploads`，可用 `STORAGE_DIR` 覆盖）下的持久目录，并把实际绝对路径在**每进程首次落盘**时打进日志
- **`STORAGE_DIR` 为空时文件库静默搬家**（`D-21`）：`${STORAGE_DIR:uploads}` 的 `:默认值` 只处理"键不存在"，不处理"键存在但值为空"，`Path.of("")` 得到的就是工作目录本身。`StorageProperties.setDir` 补 `isBlank()` 回落 + `trim()`（空白串抛的 `InvalidPathException` 不属 `IOException`，会漏成英文 50000）
- **`storage_path` 存绝对路径**（`D-22`）：把"根在当前机器上的取值"烧进每一行，"文件在哪"出现两个真源，换机器后全部失效。改为存相对 `storage.dir` 的路径（`docs/yyyyMMdd/<uuid>.md`），读取时拼回，并对早期绝对路径行双模式兼容（退役条件写在 §9）
- **"行在、字节丢"永久不可自愈**（`D-23`）：查重命中 `PARSED` 直接秒回、不再落盘，重传同一文件只得 `reused:true` 而路径仍指向空文件。改为秒回前先判文件可读，不可读则用本次字节重新落盘并更新 `storage_path`
- **脏 `storage_path` 冒 50000**（`D-23`）：`Path.of` 抛的 `InvalidPathException` 不是 `IOException`，会落到兜底处理器并带英文文案。统一归为"文件丢失"，返 `42001` + 中文提示
- **`ApiResponse.isSuccess()` 污染响应体**：Jackson 按 bean getter 规则把 `isSuccess()` 序列化成 `"success": true`，使响应体从 4 字段变 5 字段，破坏接口契约。删除该方法，判成功一律用 `code == 0`
- **`NoResourceFoundException` 被映射成 50000**：Spring 6.1 起 404 改抛 `NoResourceFoundException`（不再是 `NoHandlerFoundException`），全局处理器未覆盖，访问不存在路径返回"系统内部错误"。补映射到 `40401`
- **`Instant` 序列化出 9 位纳秒**：`Instant.toString()` 的小数位随精度浮动，无法被前端 `dayjs` 稳定解析。改为固定 3 位毫秒 + `Z`
- **`IdGen` 重启后必撞唯一键**（`D-17`）：进程内计数器重启归零，而 `uk_doc_source_doc_no` 等唯一索引不看逻辑删标记，导致重启后首次上传必然 `DuplicateKeyException` → `50000`。改为「播种 + 重试」：按 `前缀+日期` 隔离计数，用裸 `JdbcTemplate`（**不带 `deleted = 0`**）查当日最大序号播种，撞键后重新播种而非 `++`，连续 3 次撞键或当日达 6 位上限均抛 `40901`
