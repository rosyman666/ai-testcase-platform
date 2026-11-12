/**
 * T11 指标成本看板（#43–#47）的 VO TS 镜像，逐字对齐 docs/07 §3.2。
 *
 * <p><b>字段命名</b>：一律 camelCase（`acceptRate` / `totalCostMicro` / `hasReviewData` …）。
 * `accept_rate` / `total_cost_micro` 等 snake_case 只出现在 §8.3 的公式描述里，
 * 不是 JSON 字段名。</p>
 *
 * <p><b>数值序列化口径（务必读）</b>：`backend/.../config/JacksonConfig.java:73-74`
 * 同时给 `Long.class` 与 `Long.TYPE` 注册了 `ToStringSerializer`，所以后端 VO 里所有
 * `long` 计数 / 金额字段（`generatedCount` / `reviewedCount` / `totalCostMicro` /
 * `p50Ms` / `count` …）序列化后都是 <b>JSON 字符串</b>，例如
 * `{"totalCostMicro":"3500000","reviewedCount":"6"}`；只有 `double` 比率是 JSON 数字，
 * `boolean` 是 JSON 布尔，`String` / 枚举是字符串。故本文件里 `long` 型字段一律声明为
 * `string`，`double` 比率声明为 `number`。</p>
 *
 * <p><b>前端转换职责</b>：页面（批次 2）凡要做数值比较、求和、格式化，必须先显式
 * `Number(...)` 转换，不能直接拿字符串相加或比大小（`"1" + "2" === "12"`）；
 * `api/metrics.ts` 不做转换，也不把 string 伪装成 number。本文件内的
 * `resolveZeroDisplay()` 已就地示范该转换。</p>
 *
 * <p><b>0 值分层 A/B/C/D</b>：后端只保证「不炸、返回 0」，前端负责「不误导、说清为什么是 0」。
 * 判定集中在页面里的 `resolveZeroDisplay(vo)` 纯函数，VO 提供 `hasReviewData` /
 * `costZeroReasons` 两个显式判定字段，避免出现「采纳率 0%」这种把「人还没评审」
 * 误读成「模型全错了」的展示。</p>
 */

/** 0 值分层展示类别（PRD v1.2）。 */
export type ZeroDisplayKind = 'A_VALUE' | 'B_NO_REVIEW' | 'C_COST_ZERO' | 'D_EMPTY_DIST';

/** 成本为 0 的三成因（后端 `CostZeroReason` 枚举的 `name()`）。 */
export type CostZeroReason =
  | 'MOCK_PRICE_ZERO'
  | 'FAILED_CALL_NO_TOKEN'
  | 'MODEL_PRICE_MISSING';

/** 成本 0 成因的中文文案（与 docs/07 §3.2 第三列一致）。 */
export const COST_ZERO_REASON_LABEL: Readonly<Record<CostZeroReason, string>> = {
  MOCK_PRICE_ZERO: 'mock-model 单价配置为 0/0',
  FAILED_CALL_NO_TOKEN: '失败调用无 token 回显',
  MODEL_PRICE_MISSING: '模型缺少 model_price 单价行（配置缺口）',
};

/** #43–#47 共用的时间窗与过滤参数；`from`/`to` 为 `yyyy-MM-dd`。 */
export interface MetricsRangeQuery {
  /** 起始日（Asia/Shanghai 民用日，含）。 */
  from?: string;
  /** 结束日（含；后端按 `[from, to+1day)` 左闭右开处理）。 */
  to?: string;
  /** 文档 ID（可选）。 */
  docId?: string;
  /** 任务 ID（可选）。 */
  taskId?: string;
}

/** #43 GET /api/metrics/overview 出参。 */
export interface OverviewVO {
  /** 生成用例数（含 DRAFT）；`long` → JSON 字符串。 */
  generatedCount: string;
  /** 已评审数 = ACCEPTED + EDITED + REJECTED，是所有比率的分母；`long` → 字符串。 */
  reviewedCount: string;
  draftCount: string;
  acceptedCount: string;
  editedCount: string;
  rejectedCount: string;
  /** (ACCEPTED + EDITED) / reviewedCount；`double` → JSON 数字。 */
  acceptRate: number;
  /** EDITED / reviewedCount；`double`。 */
  editedRate: number;
  /** REJECTED / reviewedCount，中文标签「驳回率」（即 #43 的「失败率」）；`double`。 */
  rejectRate: number;
  /** ACCEPTED / reviewedCount；`double`。 */
  strictAcceptRate: number;
  /** SUM(llm_call_log.cost_micro)，严格落库值，不在前端现算；`long` → 字符串。 */
  totalCostMicro: string;
  totalPromptTokens: string;
  totalCompletionTokens: string;
  /** totalCostMicro / acceptedCaseCount，分母 0 → `0`；`long` → 字符串。 */
  unitCostMicro: string;
  /** 应用层 nearest-rank 分位数（不用 avg）；`long` → 字符串。 */
  p50Ms: string;
  p95Ms: string;
  costZeroReasons: CostZeroReason[];
  /** reviewedCount > 0；`false` → B 类「暂无评审数据」。 */
  hasReviewData: boolean;
}

/** #44 GET /api/metrics/acceptance 单点（本批 groupBy 仅 `day`）。 */
export interface AcceptancePointVO {
  /** day 视角格式 `yyyy-MM-dd`。 */
  key: string;
  generatedCount: string;
  reviewedCount: string;
  acceptedCount: string;
  editedCount: string;
  rejectedCount: string;
  acceptRate: number;
  editedRate: number;
  rejectRate: number;
  hasReviewData: boolean;
}

/** #44 请求（本批只提供 `day`，`template`/`model`/`category` 延后，下拉不得出现不可用项）。 */
export interface AcceptanceQuery extends MetricsRangeQuery {
  groupBy?: 'day';
}

/** #45 GET /api/metrics/reject-reasons 单点。 */
export interface RejectReasonPointVO {
  code: string;
  label: string;
  /** 该码下的驳回记录数；`long` → JSON 字符串。 */
  count: string;
  /** count / 全部驳回数；`double` → JSON 数字。 */
  ratio: number;
}

/** #46 GET /api/metrics/cost 单点。 */
export interface CostPointVO {
  key: string;
  /** 仅 `groupBy=task` 时由 `gen_task.task_no` 提供。 */
  taskNo: string | null;
  promptTokens: string;
  completionTokens: string;
  /** SUM(cost_micro)，微元；`long` → 字符串。 */
  costMicro: string;
  /** 台账行数；`long` → 字符串。 */
  callCount: string;
}

/** #46 请求（三视角全 P0）。 */
export interface CostQuery extends MetricsRangeQuery {
  groupBy?: 'task' | 'day' | 'model';
}

/** #46 GET /api/metrics/cost 出参。 */
export interface CostVO {
  groupBy: string;
  points: CostPointVO[];
  totalCostMicro: string;
  totalPromptTokens: string;
  totalCompletionTokens: string;
  unitCostMicro: string;
  acceptedCaseCount: string;
  costZeroReasons: CostZeroReason[];
}

/** #47 GET /api/metrics/quality 出参（P1，本批仍交付）。 */
export interface QualityVO {
  /** count(attempts=1 AND status='SUCCESS') / totalItems；`double` → JSON 数字。 */
  firstPassRate: number;
  generateSuccessRate: number;
  /** count(llm_call_log.error_code LIKE 'E_LLM_PARSE%') / totalCalls；`double`。 */
  parseFailRate: number;
  /** 应用层 nearest-rank 毫秒分位数；`long` → 字符串。 */
  p50Ms: string;
  p95Ms: string;
  totalItems: string;
  totalCalls: string;
}

/** #47 请求（本批 groupBy 仅 `day`）。 */
export interface QualityQuery extends MetricsRangeQuery {
  groupBy?: 'day';
}

/**
 * 0 值分层判定的纯函数（供 3 个指标页共用，避免判定逻辑散落）。
 *
 * <p>优先级：B（没评审）> D（分布 0 行）> C（成本 0 但 token 非 0）> A（有值）。
 * B 必须排在最前：一个「没人评审」的窗口里成本必然也是 0，若先判 C 会盖掉
 * 「暂无评审数据」这条真正需要引导用户去做的事。</p>
 *
 * <p><b>注意</b>：`OverviewVO` 的 `long` 字段是 JSON 字符串，此处在比较 / 求和前
 * 统一 `Number(...)` 转换，页面（批次 2）做任何数值运算都要照此处理。</p>
 *
 * @param args overview（可为 null，用于加载中）、驳回原因列表长度、成本点数量
 * @returns 展示类别
 */
export function resolveZeroDisplay(args: {
  overview: OverviewVO | null;
  rejectReasonCount: number;
  costPointCount: number;
}): ZeroDisplayKind {
  const { overview, rejectReasonCount, costPointCount } = args;
  if (overview === null) {
    return 'A_VALUE';
  }
  // long 字段在 JSON 里是字符串：比较 / 求和前必须显式 Number(...) 转换，
  // 否则 "1000" + "500" 会拼成 "1000500"，"0" === 0 恒为 false。
  const reviewedCount = Number(overview.reviewedCount);
  const generatedCount = Number(overview.generatedCount);
  const totalTokens = Number(overview.totalPromptTokens) + Number(overview.totalCompletionTokens);
  const totalCostMicro = Number(overview.totalCostMicro);
  if (!overview.hasReviewData || reviewedCount === 0) {
    return 'B_NO_REVIEW';
  }
  if (costPointCount === 0 && rejectReasonCount === 0 && generatedCount === 0) {
    return 'D_EMPTY_DIST';
  }
  if (totalTokens !== 0 && totalCostMicro === 0) {
    return 'C_COST_ZERO';
  }
  return 'A_VALUE';
}
