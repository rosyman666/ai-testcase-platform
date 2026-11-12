/**
 * 二进制导出响应的判别与下载（T09 裁决 ⑤ / docs/07 §4.0.4，P0）。
 *
 * <p><b>为什么必须有这一层</b>：导出端点成功时返回 xlsx/zip 二进制流，失败时返回
 * `application/json` 的 `ApiResponse`（业务错误统一 HTTP 200 + `code != 0`）。
 * axios 的 `responseType: 'blob'` 会把**两种响应都包成 Blob**，若直接 `createObjectURL`
 * 下载，用户得到的是一个「能打开但内容为空」的 xlsx，且错误提示永远不出现。
 * 因此先按 `blob.type` / `Content-Type` 判别形态，再决定「解包报错」还是「触发下载」。</p>
 *
 * <p><b>与 http.ts 的分工</b>：拦截器对 `responseType === 'blob'` 的请求返回**完整
 * `AxiosResponse`**（保留 headers），本模块负责消费它。普通 JSON 请求不走这里。</p>
 *
 * <p><b>文件名与文案不硬编码</b>：`message` 来自后端 `BizException`，`filename` 来自
 * `Content-Disposition`；只有两者都缺失时才用兜底名。</p>
 */

import type { AxiosResponse } from 'axios';
import { ApiError } from '@/api/http';
import { API_CODE, type ApiEnvelope } from '@/types/common';
import type { ExportExtension } from '@/types/export';
import { toast } from '@/utils/toast';

/** 下载结果：返回实际使用的文件名，便于上层做「已导出」提示。 */
export interface DownloadResult {
  filename: string;
}

/** `downloadOrThrow` 的可选参数。 */
export interface DownloadOptions {
  /** 响应缺少 Content-Disposition 时的兜底文件名。 */
  fallbackFilename?: string;
}

/**
 * 从 axios 的 headers 中大小写无关地取值。
 *
 * <p>生产环境 axios 1.x 传的是 `AxiosHeaders`（带 `.get()`），单测里常直接造普通对象；
 * 两种形态都要能读出 `content-type` / `content-disposition`，否则判别会静默失效。</p>
 *
 * @param headers 响应头对象（可能是 AxiosHeaders 或普通对象）
 * @param name 头名（大小写无关）
 * @returns 头值字符串；不存在返回 undefined
 */
function headerValue(headers: unknown, name: string): string | undefined {
  if (headers === null || headers === undefined || typeof headers !== 'object') {
    return undefined;
  }
  const holder = headers as { get?: (key: string) => unknown };
  if (typeof holder.get === 'function') {
    const viaGetter = holder.get.call(headers, name);
    if (viaGetter !== undefined && viaGetter !== null) {
      return String(viaGetter);
    }
  }
  const lower = name.toLowerCase();
  const record = headers as Record<string, unknown>;
  for (const key of Object.keys(record)) {
    if (key.toLowerCase() === lower) {
      const value = record[key];
      if (value !== undefined && value !== null) {
        return String(value);
      }
    }
  }
  return undefined;
}

/**
 * 判断某个响应是否是「后端返回的错误 JSON」。
 *
 * <p>两个信号取或：`Blob.type`（浏览器按 Content-Type 填）与响应头的 `Content-Type`。
 * 只看其中一个都不稳：代理可能改写头，jsdom/某些运行时又可能不填 `Blob.type`。</p>
 *
 * @param response 完整 AxiosResponse，data 为 Blob
 * @returns 是否应按 JSON 错误体处理
 */
function isJsonEnvelope(response: AxiosResponse<Blob>): boolean {
  const blobType = response.data?.type ?? '';
  if (blobType.toLowerCase().includes('application/json')) {
    return true;
  }
  const contentType = headerValue(response.headers, 'content-type') ?? '';
  return contentType.toLowerCase().includes('application/json');
}

/**
 * 把 Blob 读成文本。
 *
 * <p>优先用 `Blob.text()`；旧环境（部分 jsdom 版本）降级到 `FileReader`，避免单测里
 * 「读不出内容」被误判成「解包失败」。</p>
 *
 * @param blob 待读取的 Blob
 * @returns 文本内容
 */
async function readBlobAsText(blob: Blob): Promise<string> {
  if (typeof blob.text === 'function') {
    return blob.text();
  }
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ''));
    reader.onerror = () => reject(reader.error ?? new Error('无法读取响应内容'));
    reader.readAsText(blob);
  });
}

/**
 * 解析 `Content-Disposition` 中的文件名。
 *
 * <p>优先 RFC 5987 的 `filename*=UTF-8''...`（后端文件名含中文时唯一正确写法），
 * 其次 `filename="..."`，再其次无引号形式；都没有则用兜底名。</p>
 *
 * @param disposition Content-Disposition 头值
 * @param fallback 兜底文件名
 * @returns 文件名（永不为 undefined）
 */
export function parseContentDispositionFilename(
  disposition: string | undefined,
  fallback: string,
): string {
  if (!disposition) {
    return fallback;
  }
  const utf8 = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(disposition);
  if (utf8 && utf8[1]) {
    const raw = utf8[1].trim().replace(/^"|"$/g, '');
    try {
      const decoded = decodeURIComponent(raw);
      if (decoded) {
        return decoded;
      }
    } catch {
      // 百分号编码非法时落到 filename="..." 分支，不吞掉文件本身
    }
  }
  const quoted = /filename\s*=\s*"([^"]+)"/i.exec(disposition);
  if (quoted && quoted[1]) {
    return quoted[1];
  }
  const bare = /filename\s*=\s*([^;]+)/i.exec(disposition);
  if (bare && bare[1]) {
    const value = bare[1].trim().replace(/^"|"$/g, '');
    if (value) {
      return value;
    }
  }
  return fallback;
}

/**
 * 构造兜底文件名 `T{label}_{yyyyMMdd-HHmm}.{ext}`（docs/07 §4.0.4）。
 *
 * <p>前端通常只有 taskId（雪花 ID）而没有 `gen_task.task_no`，真实文件名一律以
 * `Content-Disposition` 为准；这里的 label 只用于「头被代理剥掉」时给出可辨认的名字。</p>
 *
 * @param label 任务标识（taskId 或 taskNo）
 * @param ext 扩展名
 * @param now 时间基准，显式传入便于测试稳定
 * @returns 兜底文件名
 */
export function buildFallbackFilename(
  label: string,
  ext: ExportExtension,
  now: Date = new Date(),
): string {
  const pad = (value: number, width = 2): string => String(value).padStart(width, '0');
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
    + `-${pad(now.getHours())}${pad(now.getMinutes())}`;
  const safeLabel = (label || 'export').replace(/[^A-Za-z0-9_-]/g, '_');
  return `T${safeLabel}_${stamp}.${ext}`;
}

/**
 * 把一个「被 axios 拒绝、但 body 其实是 JSON 错误体」的响应解包成 `ApiError`。
 *
 * <p>导出端点若因鉴权或 40401 返回真实 HTTP 4xx，axios 会走**错误拦截器**而不是
 * `downloadOrThrow`。错误拦截器看到 `response.data` 是 Blob，拿不到 `code`，会退化成
 * 通用提示。为避免「40401 的中文 message 丢失」，`exportApi.ts` 在 catch 里调本函数
 * 再解一次；若不是 JSON 错误体则返回 null，交回原始错误。</p>
 *
 * @param error axios 抛出的未知错误
 * @returns 解出的 ApiError；不是可识别的 JSON 错误体时返回 null
 */
export async function unwrapBlobRejection(error: unknown): Promise<ApiError | null> {
  if (error === null || typeof error !== 'object') {
    return null;
  }
  const response = (error as { response?: { data?: unknown; headers?: unknown } }).response;
  const blob = response?.data;
  if (!(blob instanceof Blob)) {
    return null;
  }
  const contentType = headerValue(response?.headers, 'content-type') ?? blob.type ?? '';
  if (!contentType.toLowerCase().includes('application/json')) {
    return null;
  }
  const text = await readBlobAsText(blob);
  let envelope: Partial<ApiEnvelope<unknown>>;
  try {
    envelope = JSON.parse(text) as Partial<ApiEnvelope<unknown>>;
  } catch {
    return null;
  }
  const code = typeof envelope?.code === 'number' ? envelope.code : API_CODE.INTERNAL;
  const message = envelope?.message || '导出失败';
  toast(message, 'error');
  return new ApiError(code, message, envelope?.traceId ?? '');
}

/**
 * 判别导出响应并按需下载；错误 JSON 一律转成 `ApiError` 且**不产生文件**。
 *
 * @param response http.ts 拦截器返回的完整 AxiosResponse（data 为 Blob）
 * @param options 兜底文件名等可选项
 * @returns 成功下载时的文件名
 * @throws ApiError 后端返回业务错误 JSON，或返回了非预期成功 JSON
 */
export async function downloadOrThrow(
  response: AxiosResponse<Blob>,
  options: DownloadOptions = {},
): Promise<DownloadResult> {
  const blob = response.data;
  if (!(blob instanceof Blob)) {
    const message = '导出响应不是二进制内容';
    toast(message, 'error');
    throw new ApiError(API_CODE.INTERNAL, message, '');
  }

  const disposition = headerValue(response.headers, 'content-disposition');

  if (isJsonEnvelope(response)) {
    const text = await readBlobAsText(blob);
    let envelope: Partial<ApiEnvelope<unknown>> | null = null;
    try {
      envelope = JSON.parse(text) as Partial<ApiEnvelope<unknown>>;
    } catch {
      envelope = null;
    }
    const code = typeof envelope?.code === 'number' ? envelope.code : API_CODE.INTERNAL;
    const message = envelope?.message || '导出失败';
    const traceId = envelope?.traceId ?? '';
    if (code === API_CODE.OK) {
      // 导出端点不该返回「成功的 JSON」：那说明后端把二进制分支写错了
      const unexpected = '导出端点返回了非预期的 JSON 响应';
      toast(unexpected, 'error');
      throw new ApiError(API_CODE.INTERNAL, unexpected, traceId);
    }
    toast(message, 'error');
    throw new ApiError(code, message, traceId);
  }

  const fallback = options.fallbackFilename ?? buildFallbackFilename('export', 'xlsx');
  const filename = parseContentDispositionFilename(disposition, fallback);

  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = objectUrl;
  anchor.download = filename;
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  try {
    anchor.click();
  } finally {
    document.body.removeChild(anchor);
    URL.revokeObjectURL(objectUrl);
  }
  return { filename };
}
