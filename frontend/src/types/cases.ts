/**
 * 用例域类型。对应 `TestCaseVO.java` / `CaseUpdateReq.java` / `ReviewReq.java` /
 * `BatchReviewReq.java` / `BatchReviewResult.java` 的 record 字段。
 *
 * ⚠️ **编辑是 `PUT` 不是 `PATCH`**（D-50），批量字段名是 `ids[]` 不是 `caseIds[]`，
 * 列表筛选只有 `taskId/status/keyword` 三个（D-50）。
 */

// ==================== REST VO ====================

/** 一条可执行步骤（`TestCaseVO.Step`）。`no` 由服务端按数组顺序重排，客户端不传。 */
export interface TestCaseStep {
  no: number;
  action: string;
  data: string;
}

/** 一条评审历史（`TestCaseVO.ReviewItem`）。列表页恒为空数组，只有详情带。 */
export interface CaseReviewItem {
  id: string;
  action: ReviewActionCode;
  rejectReasonCode: string | null;
  reasonText: string;
  reviewerId: string | null;
  createdAt: string;
  oldContent: Record<string, unknown> | null;
  newContent: Record<string, unknown> | null;
}

/**
 * 用例出参（列表与详情共用）。对应 `TestCaseVO` 的 20 个字段。
 *
 * `steps` 后端已解析成对象数组（脏数据降级为空数组），前端不需要 `JSON.parse`。
 * `confidence` 是解析保真度（1.000 正常 / 0.500 宽松降级），**不是内容质量分**。
 */
export interface TestCaseVO {
  id: string;
  taskId: string;
  taskItemId: string | null;
  testPointId: string | null;
  docId: string | null;
  seqNo: number | null;
  title: string;
  precondition: string;
  steps: TestCaseStep[];
  expected: string;
  priority: string;
  caseType: string;
  status: CaseStatusCode;
  confidence: number | null;
  promptVersion: string | null;
  model: string | null;
  contentHash: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  reviews: CaseReviewItem[];
}

// ==================== 请求体 ====================

/**
 * 编辑用例入参（`PUT /api/cases/{id}`）。对应 `CaseUpdateReq`。
 *
 * **全部字段可选，`null` = 该项不改**。只提交用户真正改过的字段——
 * 全量回传会把服务器上别人刚改的字段覆盖回去。
 *
 * ⚠️ `steps` 的元素**不传 `no`**（`CaseUpdateReq.Step` 只有 action/data），
 * 序号由服务端按数组顺序重排 1..N。
 */
export interface CaseUpdateReq {
  title?: string | null;
  precondition?: string | null;
  steps?: Array<{ action: string; data: string }> | null;
  expected?: string | null;
  priority?: string | null;
  caseType?: string | null;
}

/**
 * 单条评审入参（`POST /api/cases/{id}/review`）。对应 `ReviewReq`。
 *
 * **只接受 `ACCEPT` / `REJECT`**；传 `EDIT` → `40001`（编辑走 PUT）。
 * `REJECT` 缺 `rejectReasonCode` → `40002`；码不存在/已停用 → `40001`。
 * `ACCEPT` 时传了原因码 → **忽略并落 NULL**（不报错）。
 */
export interface ReviewReq {
  action: ReviewActionCode;
  rejectReasonCode?: string;
  reasonText?: string;
}

/**
 * 批量评审入参（`POST /api/cases/batch-review`）。对应 `BatchReviewReq`。
 *
 * ⚠️ 字段名是 **`ids`** 不是 `caseIds`（D-50）；上限 **200 条**；
 * `ids` 为空 → `40002`；**不支持 `EDIT`**。
 */
export interface BatchReviewReq {
  ids: string[];
  action: ReviewActionCode;
  rejectReasonCode?: string;
  reasonText?: string;
}

/**
 * 批量评审出参。对应 `BatchReviewResult`。
 *
 * ⚠️ `skipped` **永不为 null**（后端规范化了），用 `.length` 做分支判据是安全的。
 * `skipped[].id` 用来**按 id 把跳过的行标黄**（下标对不上）。
 */
export interface BatchReviewResult {
  updated: number;
  skipped: BatchReviewSkipped[];
}

/** 一条跳过明细。 */
export interface BatchReviewSkipped {
  id: string;
  reason: string;
}

/**
 * 用例列表查询参数。**只有 3 个筛选**（D-50）：taskId / status / keyword。
 * `docId`/`priority`/`caseType` 未实现，做了就是"筛选条件不生效"。
 */
export interface CaseListQuery {
  taskId?: string;
  status?: string;
  keyword?: string;
  page?: number;
  size?: number;
  sort?: string;
}

// ==================== 状态码联合 ====================

/** 用例状态（4 个）。`CaseStatus.java` 的全部常量。 */
export type CaseStatusCode = 'DRAFT' | 'ACCEPTED' | 'EDITED' | 'REJECTED';

/** 评审动作。**只有两个合法值**（`EDIT` 传了会被后端 40001 挡住）。 */
export type ReviewActionCode = 'ACCEPT' | 'REJECT';
