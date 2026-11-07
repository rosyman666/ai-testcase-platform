import { del, get, post } from '@/api/http';
import type { PageResult } from '@/types/common';
import type {
  ApiOperationVO,
  DocDetailVO,
  DocListQuery,
  DocSectionVO,
  DocSourceVO,
  DocUploadResult,
} from '@/types/doc';

/**
 * 文档模块 API（唯一出口）。
 *
 * 纪律（§8.5）：组件里**不得**直接 `axios.get` 或自己拼 `API_BASE`。
 * 走 `api/http.ts` 才能拿到统一的 401 处理、`code !== 0` 归一化与 traceId 透出。
 */

/**
 * 上传并解析文档。
 *
 * 注意 Content-Type：`http.ts` 的实例默认是 `application/json`，而 axios 1.x 在
 * 检测到 `Content-Type: application/json` + `FormData` 时会把表单**转成 JSON 字符串**
 * （`transformRequest` 里的 `formDataToJSON`），文件就丢了。这里显式声明
 * `multipart/form-data`，浏览器适配器会自动补上 boundary（axios 内部会 `setContentType(false)`）。
 *
 * @param file 待上传文件
 * @returns 上传结果（`parseStatus` 可能为 FAILED，此时 `code` 仍是 0）
 */
export function uploadDoc(file: File): Promise<DocUploadResult> {
  const formData = new FormData();
  formData.append('file', file);
  return post<DocUploadResult>('/docs', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

/**
 * 分页查询文档列表。
 *
 * @param query 分页与过滤条件
 * @returns 分页结果
 */
export function listDocs(query: DocListQuery): Promise<PageResult<DocSourceVO>> {
  return get<PageResult<DocSourceVO>>('/docs', {
    page: query.page,
    size: query.size,
    keyword: query.keyword || undefined,
    docType: query.docType || undefined,
    parseStatus: query.parseStatus || undefined,
  });
}

/**
 * 文档详情（内嵌章节树与接口列表）。
 *
 * @param id 文档 ID
 * @returns 详情
 */
export function getDoc(id: string): Promise<DocDetailVO> {
  return get<DocDetailVO>(`/docs/${id}`);
}

/**
 * 章节分页。
 *
 * @param id 文档 ID
 * @param page 页码
 * @param size 每页条数
 * @returns 分页章节
 */
export function listSections(id: string, page = 1, size = 20): Promise<PageResult<DocSectionVO>> {
  return get<PageResult<DocSectionVO>>(`/docs/${id}/sections`, { page, size });
}

/**
 * 接口列表分页（不含 schema）。
 *
 * @param id 文档 ID
 * @param page 页码
 * @param size 每页条数
 * @returns 分页接口
 */
export function listOperations(id: string, page = 1, size = 20): Promise<PageResult<ApiOperationVO>> {
  return get<PageResult<ApiOperationVO>>(`/docs/${id}/operations`, { page, size });
}

/**
 * 重新解析（失败时后端返回 42001，拦截器已统一提示，调用方只需处理 reject）。
 *
 * @param id 文档 ID
 * @returns 解析结果
 */
export function reparseDoc(id: string): Promise<DocUploadResult> {
  return post<DocUploadResult>(`/docs/${id}/reparse`);
}

/**
 * 删除文档（默认软删，可被"重传同一文件"恢复）。
 *
 * @param id 文档 ID
 * @param force 是否物理删除
 */
export function deleteDoc(id: string, force = false): Promise<void> {
  return del<void>(`/docs/${id}`, force ? { force: true } : undefined);
}
