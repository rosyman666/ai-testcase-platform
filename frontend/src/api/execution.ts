/**
 * 执行结果模块 API（唯一出口）。
 *
 * 纪律（§8.5）：组件里**不得**直接 `axios.get` 或自己拼 `API_BASE`。
 * 走 `api/http.ts` 才能拿到统一的 401 处置、`code !== 0` 归一化与 traceId 透出。
 */

import { get, patch, post } from '@/api/http';
import type { CodeLabel, PageResult } from '@/types/common';
import type {
  ExecutionImportResult,
  ExecutionListQuery,
  ExecutionRemapResult,
  ExecutionResultQuery,
  ExecutionResultVO,
  ExecutionRunDetailVO,
  ExecutionRunVO,
  TriageReq,
} from '@/types/execution';

/**
 * 批次列表（分页）。
 *
 * 空串筛选一律转成 `undefined` 再发：`sourceType=''` 后端按"等于空串"过滤，
 * 结果是空列表——界面上看起来像"这个来源一条都没有"，排查方向完全错。
 *
 * @param query 分页与过滤条件
 * @returns 分页结果
 */
export function listExecutions(query: ExecutionListQuery = {}): Promise<PageResult<ExecutionRunVO>> {
  return get<PageResult<ExecutionRunVO>>('/executions', {
    page: query.page,
    size: query.size,
    sourceType: query.sourceType || undefined,
    keyword: query.keyword || undefined,
  });
}

/**
 * 批次详情（含状态分布与失败分类分布）。
 *
 * @param id 批次 ID
 * @returns 详情
 */
export function getExecution(id: string): Promise<ExecutionRunDetailVO> {
  return get<ExecutionRunDetailVO>(`/executions/${id}`);
}

/**
 * 批次内的执行明细（分页 + 状态 / 失败分类过滤）。
 *
 * @param id 批次 ID
 * @param query 分页与过滤条件
 * @returns 分页明细
 */
export function listExecutionResults(
  id: string,
  query: ExecutionResultQuery = {},
): Promise<PageResult<ExecutionResultVO>> {
  return get<PageResult<ExecutionResultVO>>(`/executions/${id}/results`, {
    page: query.page,
    size: query.size,
    status: query.status || undefined,
    category: query.category || undefined,
  });
}

/**
 * 导入执行结果文件（multipart）。
 *
 * 注意 Content-Type：`http.ts` 的实例默认是 `application/json`，而 axios 1.x 在
 * 检测到 `Content-Type: application/json` + `FormData` 时会把表单**转成 JSON 字符串**
 * （`transformRequest` 里的 `formDataToJSON`），文件就丢了。
 * 这里显式声明 `multipart/form-data`，浏览器适配器会自动补上 boundary
 * （与 `api/doc.ts` 的 `uploadDoc` 同一写法，别自创第二种）。
 *
 * `name` 留空时**不 append**：后端按"字段缺失"取文件名；传空串会落成空批次名。
 *
 * @param params 关联任务 / 结果文件 / 批次名称（可选）
 * @returns 导入结果（含新批次 ID 与单号）
 */
export function importExecution(params: {
  taskId: string;
  file: File;
  name?: string;
}): Promise<ExecutionImportResult> {
  const formData = new FormData();
  formData.append('file', params.file);
  formData.append('taskId', params.taskId);
  const trimmedName = params.name?.trim() ?? '';
  if (trimmedName) {
    formData.append('name', trimmedName);
  }
  return post<ExecutionImportResult>('/executions/import', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

/**
 * 来源格式字典（`JUNIT_XML` / `CSV`）。
 *
 * ⚠️ 调用方必须能容忍它失败：字典接口挂了的时候，筛选下拉退化为只有「全部」，
 * **不能整页报错**——列表数据本身还是能看的。
 *
 * @returns 字典项列表
 */
export function fetchExecutionSourceTypes(): Promise<CodeLabel[]> {
  return get<CodeLabel[]>('/dict/execution-source-types');
}

/**
 * 失败分类字典（明细页的分类下拉）。
 *
 * 同样必须容忍失败：字典挂了时下拉只剩「全部」，明细表照常展示。
 *
 * @param enabledOnly 是否只返回启用项（默认 true，与 `fetchRejectReasons` 一致）
 * @returns 字典项列表
 */
export function fetchFailureCategories(enabledOnly = true): Promise<CodeLabel[]> {
  return get<CodeLabel[]>('/dict/failure-categories', { enabledOnly });
}

/**
 * 人工纠正单条结果的失败归因（T10-3）。
 *
 * ⚠️ **是 `PATCH`，路径到 `/results/{id}` 为止**（docs/01 §4.2 #41），
 * body 只有 `failureCategory`；`triage_source` 由后端强制置 `MANUAL`。
 *
 * **返回更新后的整行 `ExecutionResultVO`（不是 204）**：前端拿它就地替换表格里那一行，
 * 不用为改一格而重拉整页明细（重拉会让用户的滚动位置/筛选上下文一起闪掉）。
 *
 * @param id 结果记录 ID（`ExecutionResultVO.id`，**不是批次 ID**）
 * @param req 目标失败分类
 * @returns 更新后的明细行
 */
export function triageResult(id: string, req: TriageReq): Promise<ExecutionResultVO> {
  return patch<ExecutionResultVO>(`/executions/results/${id}`, req);
}

/**
 * 重跑映射（#42）：按所属任务**最近一次导出**固化的 `junit_ref` 重新匹配用例。
 *
 * 只更新映射关系，不动已有的失败分类与人工纠正，所以返回的是三个映射数字而不是整份 VO。
 * 未关联任务的批次后端会返回 `40001`——UI 侧要提前把按钮禁掉（理由确定，不是"先禁了再说"）。
 *
 * @param id 批次 ID
 * @returns 重跑后的映射结果
 */
export function remapExecution(id: string): Promise<ExecutionRemapResult> {
  return post<ExecutionRemapResult>(`/executions/${id}/remap`);
}
