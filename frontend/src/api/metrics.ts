/**
 * T11 指标成本看板 #43–#47（docs/07 §4.3–§4.7）。
 *
 * <p>全部是普通 JSON 请求，走 `http.ts` 既有解包分支（成功直接拿 `data`），
 * 不传 `responseType: 'blob'`。业务错误统一 HTTP 200 + `code != 0`，由拦截器 toast
 * 并抛 `ApiError`。</p>
 *
 * <p><b>本批只实现 #44 = `day`、#46 = `{task,day,model}`</b>；`template` / `category`
 * 延后，前端下拉不得出现不可用选项（TS 联合类型已把它们排除）。</p>
 *
 * <p>`from`/`to` 缺省时后端默认「近 7 天」（Asia/Shanghai 民用日）；`to < from` 返回 40001。
 * 空窗口返回全 0 的 VO / 空数组，前端用 `EmptyState` + 显式范围切换展示，**不放假数据**。</p>
 */

import { get } from '@/api/http';
import type {
  AcceptancePointVO,
  AcceptanceQuery,
  CostQuery,
  CostVO,
  MetricsRangeQuery,
  OverviewVO,
  QualityQuery,
  QualityVO,
  RejectReasonPointVO,
} from '@/types/metrics';

/** #43 GET /api/metrics/overview：首屏 6 卡片 + p50/p95。 */
export function fetchOverview(query?: MetricsRangeQuery): Promise<OverviewVO> {
  return get<OverviewVO>('/metrics/overview', query as Record<string, unknown> | undefined);
}

/** #44 GET /api/metrics/acceptance：按天采纳率趋势（分母 0 的点不渲染「0%」）。 */
export function fetchAcceptance(query?: AcceptanceQuery): Promise<AcceptancePointVO[]> {
  return get<AcceptancePointVO[]>('/metrics/acceptance', query as Record<string, unknown> | undefined);
}

/** #45 GET /api/metrics/reject-reasons：0 行返回 `[]`（不是 null），前端 D 类空态。 */
export function fetchRejectReasons(query?: MetricsRangeQuery): Promise<RejectReasonPointVO[]> {
  return get<RejectReasonPointVO[]>('/metrics/reject-reasons', query as Record<string, unknown> | undefined);
}

/** #46 GET /api/metrics/cost：task/day/model 三视角 + 单用例成本 + 成本 0 成因。 */
export function fetchCost(query?: CostQuery): Promise<CostVO> {
  return get<CostVO>('/metrics/cost', query as Record<string, unknown> | undefined);
}

/** #47 GET /api/metrics/quality：首次通过率 / 解析失败率 / p50·p95（不用 avg）。 */
export function fetchQuality(query?: QualityQuery): Promise<QualityVO> {
  return get<QualityVO>('/metrics/quality', query as Record<string, unknown> | undefined);
}
