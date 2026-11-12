/**
 * T09 导出端点 #35 / #36（docs/07 §4.1 / §4.2）。
 *
 * <p><b>GET + `responseType: 'blob'`</b>，走 `http.ts` 扩展后的第三参 `config`。
 * 拦截器对 blob 请求返回完整 `AxiosResponse`，这里再交给 `downloadOrThrow()` 判别
 * 「错误 JSON」与「二进制」，**绝不盲设 blob 直接下载**。</p>
 *
 * <p>两个端点共用同一个 `statuses`，保证「同一任务下 xlsx 与 zip 的用例条数一致」
 * （T09-P1-2）。`statuses` 未传/空串时后端回落 `ACCEPTED,EDITED`，不是全量。</p>
 */

import type { AxiosResponse } from 'axios';
import { get } from '@/api/http';
import type { ExportParams, ExportDownloadResult, ExportExtension } from '@/types/export';
import {
  buildFallbackFilename,
  downloadOrThrow,
  unwrapBlobRejection,
} from '@/utils/download';

/** 把导出参数转成查询串对象；只发送有值的字段，避免 `undefined` 被 axios 拼成字符串。 */
function toQuery(params: ExportParams, includePackageName: boolean): Record<string, unknown> {
  const query: Record<string, unknown> = { taskId: params.taskId };
  if (params.docId) {
    query.docId = params.docId;
  }
  if (params.statuses) {
    query.statuses = params.statuses;
  }
  if (includePackageName && params.packageName) {
    query.packageName = params.packageName;
  }
  return query;
}

/**
 * 统一处理 axios 拒绝：若 body 其实是 JSON 错误体，解包成带业务码的 `ApiError`；
 * 否则原样抛出，保留 `http.ts` 错误拦截器给出的网络/超时提示。
 *
 * @param error 未知错误
 * @returns 永不为正常返回，始终抛出
 */
async function rethrowExportError(error: unknown): Promise<never> {
  const unwrapped = await unwrapBlobRejection(error);
  throw unwrapped ?? error;
}

/**
 * #35 GET /api/export/cases.xlsx —— 导出 Excel（「用例」+「统计」双 sheet）。
 *
 * @param params taskId 必填；docId / statuses 可选
 * @returns 实际下载使用的文件名
 * @throws ApiError 40001（statuses 非法）/ 40002（缺 taskId）/ 40003（空集或超限）/ 40401（任务不存在）
 */
export function exportCasesXlsx(params: ExportParams): Promise<ExportDownloadResult> {
  const ext: ExportExtension = 'xlsx';
  const fallback = buildFallbackFilename(params.taskId, ext);
  return get<AxiosResponse<Blob>>('/export/cases.xlsx', toQuery(params, false), {
    responseType: 'blob',
  })
    .then((response) => downloadOrThrow(response, { fallbackFilename: fallback }))
    .catch((error: unknown) => rethrowExportError(error));
}

/**
 * #36 GET /api/export/junit.zip —— 导出 JUnit 骨架 zip（方案 A：纯 JDK + 注解桩 + runner）。
 *
 * <p>`packageName` 只在本端点生效；非法 Java 包名后端返回 40001。</p>
 *
 * @param params taskId 必填；docId / statuses / packageName 可选
 * @returns 实际下载使用的文件名
 */
export function exportJUnitZip(params: ExportParams): Promise<ExportDownloadResult> {
  const ext: ExportExtension = 'zip';
  const fallback = buildFallbackFilename(params.taskId, ext);
  return get<AxiosResponse<Blob>>('/export/junit.zip', toQuery(params, true), {
    responseType: 'blob',
  })
    .then((response) => downloadOrThrow(response, { fallbackFilename: fallback }))
    .catch((error: unknown) => rethrowExportError(error));
}
