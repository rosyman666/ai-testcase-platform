/**
 * 用例域 API。全部走 `http.ts` 统一出口。
 *
 * ⚠️ **动词以代码为准**（D-50）：编辑是 `PUT` 不是 `PATCH`（PATCH 会 405）；
 * 批量字段名是 `ids[]` 不是 `caseIds[]`；列表筛选只有 3 个。
 */

import { get, post, put } from '@/api/http';
import type {
  TestCaseVO,
  CaseUpdateReq,
  ReviewReq,
  BatchReviewReq,
  BatchReviewResult,
  CaseListQuery,
} from '@/types/cases';
import type { PageResult, CodeLabel } from '@/types/common';

/**
 * 用例列表（分页）。
 *
 * ⚠️ **只有 3 个筛选**：`taskId` / `status` / `keyword`（D-50）。
 * `docId`/`priority`/`caseType` 未实现，做了就是"筛选条件不生效"。
 * 列表里 `reviews` 恒为空数组（详情才带评审历史）。
 *
 * @param query 查询参数
 * @returns 分页结果
 */
export function listCases(query?: CaseListQuery): Promise<PageResult<TestCaseVO>> {
  return get<PageResult<TestCaseVO>>('/cases', query as Record<string, unknown>);
}

/**
 * 用例详情（带评审历史）。
 *
 * @param id 用例 ID
 * @returns `TestCaseVO`，`reviews` 按 `created_at` 升序
 */
export function getCase(id: string): Promise<TestCaseVO> {
  return get<TestCaseVO>(`/cases/${id}`);
}

/**
 * 编辑用例（**PUT**，不是 PATCH）。
 *
 * **只提交用户真正改过的字段**（传 `null`/省略 = "不动"）。
 * 全量回传会把服务器上别人刚改的字段覆盖回去。
 * 成功后状态自动置 `EDITED` 并写 old/new 快照。
 *
 * @param id 用例 ID
 * @param req 更新参数（全部可选）
 * @returns 更新后的 `TestCaseVO`
 */
export function updateCase(id: string, req: CaseUpdateReq): Promise<TestCaseVO> {
  return put<TestCaseVO>(`/cases/${id}`, req);
}

/**
 * 单条评审（ACCEPT / REJECT）。
 *
 * **返回 `TestCaseVO`（不是状态字符串）**——用返回的 `.status` 直接替换本地行。
 * `REJECT` 缺 `rejectReasonCode` → `40002`；码不存在/停用 → `40001`。
 * 允许"改主意"重复评审，台账只追加不覆盖。
 *
 * @param id 用例 ID
 * @param req 评审参数
 * @returns 评审后的 `TestCaseVO`
 */
export function reviewCase(id: string, req: ReviewReq): Promise<TestCaseVO> {
  return post<TestCaseVO>(`/cases/${id}/review`, req);
}

/**
 * 批量评审。
 *
 * ⚠️ 字段名是 **`ids`** 不是 `caseIds`（D-50）；上限 200 条；**只处理 `DRAFT`**。
 * 返回 `{updated, skipped:[{id, reason}]}`，`skipped` 永不 `null`。
 * 按 `skipped[].id` 把跳过的行标黄（下标对不上）。
 *
 * @param req 批量参数
 * @returns 批量结果
 */
export function batchReviewCases(req: BatchReviewReq): Promise<BatchReviewResult> {
  return post<BatchReviewResult>('/cases/batch-review', req);
}

/**
 * 驳回原因字典。
 *
 * @param enabledOnly 是否只返回启用项（默认 true）
 * @returns 字典项列表
 */
export function fetchRejectReasons(enabledOnly = true): Promise<CodeLabel[]> {
  return get<CodeLabel[]>('/dict/reject-reasons', { enabledOnly });
}
