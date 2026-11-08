/**
 * 任务域 API。全部走 `http.ts` 统一出口（自动解包 `ApiResponse.data`）。
 *
 * ⚠️ **`retryTask` 的端点 `POST /api/tasks/{id}/retry` 尚未交付**（主动延后）。
 * 调用会得到 404；UI 层应把重试按钮禁用并提示"待交付"，避免用户点到。
 * 端点补上后此函数即刻可用，不需要改代码（§7.1 裁决：不写 throw 占位）。
 */

import { get, post } from '@/api/http';
import type {
  GenTaskVO,
  GenTaskItemVO,
  GenTaskCreateReq,
  GenTaskCreateResult,
  GenTaskCancelResult,
} from '@/types/task';
import type { PageResult } from '@/types/common';

/**
 * 创建任务。
 *
 * ⚠️ **入参必填 `scene`**（D-49），缺了直接 40001「场景不能为空」。
 * `unitRefs` 与 `testPointIds` 二选一。**没有 `templateCode`/`timeoutSec`**，传了被静默忽略。
 *
 * @param req 创建参数
 * @returns `{taskId, taskNo}`
 */
export function createTask(req: GenTaskCreateReq): Promise<GenTaskCreateResult> {
  return post<GenTaskCreateResult>('/tasks', req);
}

/**
 * 获取任务详情。
 *
 * @param taskId 任务 ID
 * @returns `GenTaskVO`（18 字段，**不含 tokens/cost**，D-51）
 */
export function getTask(taskId: string): Promise<GenTaskVO> {
  return get<GenTaskVO>(`/tasks/${taskId}`);
}

/**
 * 任务列表（分页）。
 *
 * @param params 筛选与分页参数
 * @returns 分页结果
 */
export function listTasks(params?: {
  scene?: string;
  status?: string;
  docId?: string;
  page?: number;
  size?: number;
}): Promise<PageResult<GenTaskVO>> {
  // 若返回 40401，先确认 GET /api/tasks 是否已交付，而不是先怀疑筛选条件——
  // "静默空列表"会被误读成"筛选条件写错了"，排查方向完全相反。
  return get<PageResult<GenTaskVO>>('/tasks', params);
}

/**
 * 获取任务的逐 item 明细。
 *
 * **这是明细的唯一权威来源**（SSE 是有损通道），渲染顺序必须"先拉全量再叠 SSE 增量"。
 *
 * @param taskId 任务 ID
 * @returns item 列表（按 `id ASC` = 插入顺序）
 */
export function listTaskItems(taskId: string): Promise<GenTaskItemVO[]> {
  return get<GenTaskItemVO[]>(`/tasks/${taskId}/items`);
}

/**
 * 取消任务。
 *
 * @param taskId 任务 ID
 * @returns 取消结果
 */
export function cancelTask(taskId: string): Promise<GenTaskCancelResult> {
  return post<GenTaskCancelResult>(`/tasks/${taskId}/cancel`);
}

/**
 * 重试任务。⚠️ **端点尚未交付**（`POST /api/tasks/{id}/retry` 主动延后）。
 *
 * UI 层应禁用重试按钮并提示"待交付"。端点补上后此函数即刻可用。
 *
 * @param taskId 任务 ID
 * @returns 重试后的新任务信息
 */
export function retryTask(taskId: string): Promise<GenTaskCreateResult> {
  return post<GenTaskCreateResult>(`/tasks/${taskId}/retry`);
}
