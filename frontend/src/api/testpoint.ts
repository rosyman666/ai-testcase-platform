import { get, patch, post } from '@/api/http';
import type { PageResult } from '@/types/common';
import type {
  BatchStatusReq,
  TestPointCreateReq,
  TestPointExtractReport,
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
 * **T05 阶段是同步的**：请求会一直等到抽取完成才返回报告（§11.1 D-26）。
 * 用 mock provider 时是秒级；接真实模型后一篇几十接口的文档会跑几分钟，
 * 网关可能先超时——那正是 T06 异步引擎要解决的问题，不要在这里加"客户端轮询"绕过去。
 *
 * @param docId 文档 ID
 * @returns 抽取汇总报告
 */
export function extractTestPoints(docId: string): Promise<TestPointExtractReport> {
  return post<TestPointExtractReport>(`/testpoints/extract?docId=${encodeURIComponent(docId)}`);
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
