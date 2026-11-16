import { get, patch, post } from '@/api/http';
import type { PageResult } from '@/types/common';
import type {
  BatchStatusReq,
  TestPointCreateReq,
  TestPointExtractAck,
  TestPointListQuery,
  TestPointUpdateReq,
  TestPointVO,
} from '@/types/testpoint';

/**
 * 测试点模块 API（唯一出口）。
 *
 * 纪律（§8.5）：组件里**不得**直接 `axios.get` 或自己拼 `API_BASE`。
 * 走 `api/http.ts` 才能拿到统一的 401 处理、`code !== 0` 归一化与 traceId 透出。
 */

/**
 * 触发抽取。
 *
 * **自 T06 起是异步的**：只立即返回 `{taskId, taskNo}`，抽取在后台任务里执行，
 * 进度经 `GET /api/tasks/{taskId}/stream` 订阅（`useTaskStream`）。
 *
 * **不要期待这里有抽取结果**：T05 时代它同步返回 `TestPointExtractReport`
 * （含 `unitCount` / `results[]`），那是后端改异步后**再也不会出现**的结构。
 * 曾经因为页面还按旧结构渲染而整页崩过一次（`undefined.map`）——见
 * `types/testpoint.ts` 的 `TestPointExtractAck` 注释。
 *
 * @param docId 文档 ID
 * @returns 任务受理回执
 */
export function extractTestPoints(docId: string): Promise<TestPointExtractAck> {
  return post<TestPointExtractAck>(`/testpoints/extract?docId=${encodeURIComponent(docId)}`);
}

/**
 * 分页查询测试点。
 *
 * @param query 查询条件（docId 必填）
 * @returns 分页结果
 */
export function listTestPoints(query: TestPointListQuery): Promise<PageResult<TestPointVO>> {
  // 逐字段展开而不是整体 cast：`get` 的 params 是 `Record<string, unknown>`，
  // 接口类型没有索引签名，强转会掩盖"传了后端不认识的字段"这类错误
  return get<PageResult<TestPointVO>>('/testpoints', {
    docId: query.docId,
    status: query.status || undefined,
    category: query.category || undefined,
    page: query.page,
    size: query.size,
    sort: query.sort || undefined,
  });
}

/**
 * 测试点详情。
 *
 * @param id 主键
 * @returns 测试点
 */
export function getTestPoint(id: string): Promise<TestPointVO> {
  return get<TestPointVO>(`/testpoints/${id}`);
}

/**
 * 手动补点。
 *
 * @param req 入参
 * @returns 新建的测试点（重复点会返回 code=40902）
 */
export function createTestPoint(req: TestPointCreateReq): Promise<TestPointVO> {
  return post<TestPointVO>('/testpoints', req);
}

/**
 * 更新测试点。
 *
 * @param id  主键
 * @param req 可选字段更新
 * @returns 更新后的测试点
 */
export function updateTestPoint(id: string, req: TestPointUpdateReq): Promise<TestPointVO> {
  return patch<TestPointVO>(`/testpoints/${id}`, req);
}

/**
 * 批量启停。
 *
 * @param req 入参
 * @returns 实际变更条数
 */
export function batchTestPointStatus(req: BatchStatusReq): Promise<number> {
  return post<number>('/testpoints/batch-status', req);
}
