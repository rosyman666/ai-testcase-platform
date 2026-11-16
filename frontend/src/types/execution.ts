/**
 * 执行结果域类型。对应后端 `ExecutionRunVO` / `ExecutionRunDetailVO` /
 * `ExecutionResultVO` / `ExecutionImportResult` 的 record 字段。
 *
 * **字段名一律与后端一致，前端不改名去迁就展示**：改名在类型层看不出来
 * （`get<T>()` 的 `T` 只是断言，HTTP 返回什么它不校验），运行时只会得到
 * `undefined`，表现为"列全是空的"而控制台没有任何报错。
 *
 * **本文件所有 `Long` 来源字段（`id` / `taskId` / `runId` / `durationMs`）都是 `string`**。
 * 后端 `JacksonConfig` 对 `Long` 与 `long` 都注册了 `ToStringSerializer`（全项目统一：
 * 雪花 ID 超过 JS `MAX_SAFE_INTEGER`，Long 一律序列化成字符串）。
 * **改这里的类型之前先去 `backend/src/main/java/com/aittest/config/JacksonConfig.java`
 * 确认**——D-58 的教训就是「`post<T>` 的泛型是断言不是契约」：
 * `tsc` 只保证"你按自己声明的形状用"，**不保证"你声明的形状等于服务端返回的形状"**。
 * 具体到本文件：
 * - `id` / `runId` / `caseId` / `taskId` → `string`；
 * - **`durationMs` 也是 `string`**（后端 `Long`，实测返回 `"5297"`）。这是最容易被
 *   写成 `number` 的一个字段：写错了 TS 不报错，运行时拿到 `"5297"` 去做算术只会得到
 *   `NaN` 或字符串拼接，界面上表现为耗时列变成 `NaNms` 或一串粘起来的数字。
 *   展示必须走 `pages/execution/format.ts` 的 `formatDurationMs()`（内部 `Number()` 归一），
 *   **也不要在 JSX 里直接 `row.durationMs < 1000`**——字符串比较靠隐式转换，
 *   今天能跑，明天后端换成 `"1.2s"` 就静默错；
 * - `totalCount` / `passedCount` / `failedCount` / `skippedCount` / `mappedCount` / `count`
 *   是 `Integer` → **数字**；`mappedRate` 是 `BigDecimal` → **数字**（0~1，四位小数）。
 *
 * ⚠️ `mappedRate` 是**后端算好的 0~1 小数**（与指标看板同一 SQL 口径），
 * 前端只做 `× 100` 展示，**不许自己用 `mappedCount / totalCount` 重算**——
 * 重算等于第二套口径，一旦后端把"`case_id` 非空但用例已删除"也算作未映射，
 * 列表页与详情页就会各说一套数字。
 */

// ==================== 枚举（字符串联合） ====================

/** 结果文件来源格式（`ExecutionSourceType.java`）。 */
export type ExecutionSourceTypeCode = 'JUNIT_XML' | 'CSV';

/** 单条执行结果状态（`ExecutionStatus.java` 的 3 个常量）。 */
export type ExecutionStatusValue = 'PASSED' | 'FAILED' | 'SKIPPED';

/** 归因来源：规则 / 大模型 / 人工纠正。 */
export type TriageSourceCode = 'RULE' | 'LLM' | 'MANUAL';

// ==================== REST VO ====================

/**
 * 一次导入批次（列表行）出参。对应 `ExecutionRunVO`。
 *
 * `passedCount + failedCount + skippedCount === totalCount`（后端保证），
 * 页面不再自己求和，直接把三个数并列展示。
 */
export interface ExecutionRunVO {
  /** 批次主键（Long 序列化为字符串）。 */
  id: string;
  /** 业务单号，形如 `R20261116-000001`（`R` + 日期 + `-` + 6 位流水）。用户报障时报的是它。 */
  runNo: string;
  /**
   * 关联任务 ID（Long → string）；`null` 有两种成因：
   * 1. 任务被删（`execution_run.task_id` 是 `ON DELETE SET NULL`）；
   * 2. **V7 迁移前导入的历史批次**——当时表里没有这一列，不是"被删"。
   *
   * 所以文案不要写成斩钉截铁的"任务已被删除"之外的断言，`null` 只代表"关联不上"。
   * 注意与 `id` 区分：`id` 是批次主键，`taskId` 是这批结果属于哪个生成任务。
   */
  taskId: string | null;
  /**
   * 关联任务的业务单号（形如 `T20261108-000100`）；任务已被删或历史批次未记录时为 `null`。
   * 有它就展示成可点链接（跳 `/tasks/{taskId}`），`null` 只能显示占位文案——
   * 不要用空串兜底，空串与 null 在后端是两种不同语义。
   */
  taskNo: string | null;
  /** 批次名称；导入时留空则由后端取文件名。 */
  name: string;
  /** 来源格式。 */
  sourceType: ExecutionSourceTypeCode;
  /** 原始文件名（排障时对上"用户传的到底是哪个文件"）。 */
  fileName: string;
  /** 结果总条数。 */
  totalCount: number;
  /** 通过条数。 */
  passedCount: number;
  /** 失败条数。 */
  failedCount: number;
  /** 跳过条数。 */
  skippedCount: number;
  /** 已映射回用例的条数。 */
  mappedCount: number;
  /** 映射率，**0~1 的小数**（后端算好的，前端不做除法）。 */
  mappedRate: number;
  /**
   * 批次耗时（毫秒）。**JSON 里是字符串**（后端 `Long` → String，如 `"5297"`）。
   * 展示走 `formatDurationMs()`，不要直接参与算术。
   */
  durationMs: string;
  /** 导入时间（ISO-8601 字符串）。 */
  createdAt: string;
}

/** 状态分布的一项。`statusCounts` 三个值**都出现**，count 为 0 也保留。 */
export interface ExecutionStatusCountVO {
  status: ExecutionStatusValue;
  /**
   * 后端给的中文文案。
   *
   * **已与 `constants/status.ts` 的 `EXECUTION_STATUS` 完全对齐**（`SKIPPED` 两边都是
   * 「已跳过」，T10 后端把枚举文案从「跳过」改成了「已跳过」），以后只改后端这一处。
   * 页面的三张状态卡仍渲染 `StatusChip`（团队裁决：同一屏只出现一套状态文案+颜色），
   * 值相同所以看不出差别；**图表与图例直接用后端 label，不要在前端再套一层映射**。
   */
  label: string;
  count: number;
}

/**
 * 失败分类分布的一项。
 *
 * **这是后端的真实聚合查询结果**（不是前端把明细数组自己 group by），
 * 只是归因能力（T10-3）还没交付，所以当前**正常返回空数组**——
 * 空数组 ≠ 出错，页面必须显示「尚未归因」而不是错误态。
 * 饼图/图例直接用这里的 `label`，已停用分类也会带 label，避免图上出现裸 code。
 */
export interface ExecutionCategoryCountVO {
  /** 失败分类码（`FailureCategory.java` 的 code）。 */
  category: string;
  /** 中文文案（已停用的分类也会带 label，避免图上出现裸 code）。 */
  label: string;
  count: number;
}

/**
 * 批次详情出参。对应 `ExecutionRunDetailVO` = `ExecutionRunVO` + 两块分布。
 *
 * `statusCounts` 与 `categoryDistribution` 都来自**后端聚合**，
 * 页面不得用明细数组自己 `filter().length` 统计——两套口径必然在某天对不上。
 */
export interface ExecutionRunDetailVO extends ExecutionRunVO {
  /** 状态分布（三值都出现，0 也保留，保证卡片数量稳定）。 */
  statusCounts: ExecutionStatusCountVO[];
  /** 失败分类分布；本批恒为空数组。 */
  categoryDistribution: ExecutionCategoryCountVO[];
}

/**
 * 单条执行结果明细。对应 `ExecutionResultVO`。
 *
 * `stackTrace` **后端只返回前 500 字**（截断时末尾带 `…`），`failureMessage` 是完整消息。
 * 前端这两项都**不做数据层二次截断**——表格里折到 80 字 + tooltip 全文只是展示折叠，
 * 点行打开的弹窗必须给出后端原样内容（否则用户永远看不到真正的最后一行断言）。
 */
export interface ExecutionResultVO {
  id: string;
  runId: string;
  /** JUnit 的 `classname`（CSV 对应"类名"列）。 */
  className: string;
  /** 方法名 / 用例名。 */
  caseName: string;
  status: ExecutionStatusValue;
  /** 单条耗时（毫秒）。**JSON 里是字符串**（后端 `Long` → String），展示走 `formatDurationMs()`。 */
  durationMs: string;
  /** 失败信息；通过 / 跳过时为 null。 */
  failureMessage: string | null;
  /** 堆栈；通过 / 跳过时为 null。 */
  stackTrace: string | null;
  /** 映射到的用例 ID；**null = 没映射上**（记录仍然保留，不会被丢弃）。 */
  caseId: string | null;
  /** 失败分类码；未归因时为 null。 */
  failureCategory: string | null;
  /** 失败分类中文；未归因时为 null。 */
  failureLabel: string | null;
  /**
   * 归因来源。**归因只存在于失败结果**，所以这个字段允许 `null`，也不要用非空断言——
   * 它在通过 / 跳过行上不可信。
   *
   * 现状（实测）：库里 `triage_source` 是 `VARCHAR(16) NOT NULL DEFAULT 'RULE'`，批量插入
   * 又显式带这一列，所以**每一行**（含 `PASSED` / `SKIPPED`）回来都是 `'RULE'`。那个值的
   * 意思是"没人判定过它"，不是"规则判定过它"。团队口径是在 VO 层把非 `FAILED` 行归一成
   * `null`（后端是否落地未定），但页面**不依赖**这件事：所有归因展示一律先看 `status`，
   * 只有 `FAILED` 才显示来源。别把 `triageSource !== null` 当成"已归因"的判据，
   * 也别拿 `triageSource === 'RULE'` 统计归因覆盖率——通过的行会被算进去。
   */
  triageSource: TriageSourceCode | null;
  createdAt: string;
}

/**
 * 导入出参。对应 `ExecutionImportResult`。
 *
 * `mappedCount < total` **不是失败**：映射不上的记录同样入库并保留，
 * 这里只是"对上用例的条数"，是映射率的信号而不是错误。
 */
export interface ExecutionImportResult {
  /** 新批次的 ID（导入成功后直接跳详情用）。 */
  runId: string;
  /** 新批次的业务单号。 */
  runNo: string;
  /**
   * 所属任务主键（Long → string）；未关联时为 `null`。
   * 后端两种都给（`taskId` + `taskNo`）：留着是为了以后 toast / 详情能直达任务。
   */
  taskId: string | null;
  /**
   * 关联任务的业务单号；未关联 / 任务已被删时为 `null`。
   * 导入成功的提示要带上它——用户下一步就是去对任务里的用例。
   */
  taskNo: string | null;
  /** 解析出的结果总条数。 */
  total: number;
  passed: number;
  failed: number;
  skipped: number;
  /** 成功映射回用例的条数。 */
  mappedCount: number;
}

// ==================== 请求体 / 查询参数 ====================

/**
 * 重跑映射出参。对应 `POST /api/executions/{id}/remap`（#42）。
 *
 * 只回三个映射相关数字：重跑映射**只改映射关系**，不动已有的失败分类与人工纠正，
 * 所以没必要回整个 VO（回了反而诱导前端拿它整行替换）。
 */
export interface ExecutionRemapResult {
  /** 重跑后映射上的条数。 */
  mappedCount: number;
  /** 结果总条数（分母）。 */
  totalCount: number;
  /** 重跑后的映射率，**0~1 的小数**（后端算好的，前端不做除法）。 */
  mappedRate: number;
}

/**
 * 批次列表查询参数。
 *
 * ⚠️ 后端只支持 4 个参数：`page` / `size` / `sourceType` / `keyword`
 * （`keyword` 同时匹配单号、批次名、文件名）。不要顺手加 `taskId` 之类的
 * "看起来应该有"的筛选项——传了会被静默忽略，表现为"筛选不生效"。
 */
export interface ExecutionListQuery {
  /** 来源格式；空串 / undefined 表示全部。 */
  sourceType?: string;
  /** 关键词（单号 / 批次名 / 文件名）。 */
  keyword?: string;
  /** 页码，**1-based**。 */
  page?: number;
  /** 每页条数。 */
  size?: number;
}

/** 明细列表查询参数（`GET /api/executions/{id}/results`）。 */
export interface ExecutionResultQuery {
  /** 状态过滤；空串 / undefined 表示全部。 */
  status?: string;
  /** 失败分类码过滤；空串 / undefined 表示全部。 */
  category?: string;
  /** 页码，**1-based**。 */
  page?: number;
  /** 每页条数。 */
  size?: number;
}

/**
 * 人工纠正归因入参。对应下一批（T10-3）的 `PATCH /api/executions/results/{id}`
 * （docs/01 §4.2 #41）。
 *
 * ⚠️ **是 `PATCH`，路径到 `/results/{id}` 为止，没有 `/triage` 后缀**；
 * body **只有 `failureCategory`**（没有 `reasonText`），`triage_source` 由后端强制置
 * `MANUAL`——前端传了也不会生效，别顺手加回来。
 *
 * TODO(T10-3)：本批不做纠正 UI，类型先留在这里，等 T10-3 派活时直接接
 * `api/execution.ts`，避免下一批再改一次类型文件。
 */
export interface TriageReq {
  /** 目标失败分类码（必填）。 */
  failureCategory: string;
}

// ==================== 本地文案（后端无字典端点） ====================

/**
 * 归因来源的中文文案。
 *
 * 为什么放在前端：本期契约里**没有** `/dict/triage-sources` 端点
 * （`PublicConfig.triageSources` 只在 `/config/public` 里，本页没有拉全量配置）。
 * 三个值是封闭枚举，且是"给人看的短标签"，本地映射成本最低。
 * 下一批若补了字典端点，这里改成 `CodeLabel[]` 查询，页面无需再动。
 */
export const TRIAGE_SOURCE_LABEL: Record<TriageSourceCode, string> = {
  RULE: '规则',
  LLM: '大模型',
  MANUAL: '人工纠正',
};
