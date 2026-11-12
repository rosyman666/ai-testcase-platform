import axios, { type AxiosError, type AxiosRequestConfig, type AxiosResponse } from 'axios';
import { useAuthStore } from '@/store/authStore';
import { AUTH_EXPIRED_CODES, API_CODE, type ApiEnvelope } from '@/types/common';
import { toast } from '@/utils/toast';

/** 后端基地址：缺省走 vite dev proxy（`/api` → localhost:8080），生产同源反代。 */
export const API_BASE: string = import.meta.env.VITE_API_BASE ?? '/api';

/** 登录页路径。 */
const LOGIN_PATH = '/login';

/**
 * 归一化后的业务异常：组件与 react-query 的错误分支只需要读 `code` / `message` / `traceId`。
 */
export class ApiError extends Error {
  /** 业务码（非 0）。 */
  readonly code: number;

  /** 链路 ID，报障时提供给后端查日志。 */
  readonly traceId: string;

  constructor(code: number, message: string, traceId: string) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.traceId = traceId;
  }
}

const instance = axios.create({
  baseURL: API_BASE,
  timeout: 60_000,
  headers: { 'Content-Type': 'application/json' },
});

/** 防止并发 401 时重复跳转。 */
let redirecting = false;

/**
 * token 失效的统一处置：清 token → 提示 → 跳登录（带回跳地址）。
 *
 * **必须 export**：`sse.ts` 收到 40101/40102 的 error 帧时要复用同一个处置路径，
 * 两个"token 失效出口"必须同源（§7.4 裁决）。
 *
 * @param message 提示文案
 */
export function handleAuthExpired(message: string): void {
  useAuthStore.getState().clear();
  if (redirecting || window.location.pathname === LOGIN_PATH) {
    return;
  }
  redirecting = true;
  toast(message || '登录已失效，请重新登录', 'warning');
  const redirect = encodeURIComponent(`${window.location.pathname}${window.location.search}`);
  // 用 replace 而不是 assign：避免用户点"后退"又回到失效页面形成死循环
  window.location.replace(`${LOGIN_PATH}?redirect=${redirect}`);
}

// ---------- 请求拦截：注入 Authorization ----------
instance.interceptors.request.use((config) => {
  const token = useAuthStore.getState().token;
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  return config;
});

// ---------- 响应拦截：解包 data / 统一错误 ----------
instance.interceptors.response.use(
  (response: AxiosResponse) => {
    // D-3（T09 裁决 ⑤）：responseType='blob' 的请求返回**完整 AxiosResponse**，
    // 保留 headers 供 utils/download.ts 判别 Content-Type / 解析 Content-Disposition。
    // 只返回 response.data(Blob) 会丢掉文件名；且后端导出端点失败时返回的是
    // application/json 的 ApiResponse，必须拿到完整响应才能按形态分流。
    // 普通 JSON 请求不走这个分支，行为与改动前逐字节一致（现有 5 个 api/*.ts 零回归）。
    if (response.config?.responseType === 'blob') {
      return response as unknown as AxiosResponse;
    }
    const payload = response.data as ApiEnvelope<unknown>;
    // 非统一响应体（如文件下载流）原样返回，交给调用方处理
    if (!payload || typeof payload.code !== 'number') {
      return response.data as unknown as AxiosResponse;
    }
    if (payload.code === API_CODE.OK) {
      // 业务成功后**直接解包**：所有 api/*.ts 拿到的就是业务数据（见 §8.5）
      return payload.data as unknown as AxiosResponse;
    }
    if (AUTH_EXPIRED_CODES.has(payload.code)) {
      handleAuthExpired(payload.message);
      return Promise.reject(new ApiError(payload.code, payload.message, payload.traceId));
    }
    toast(payload.message || '请求失败', 'error');
    return Promise.reject(new ApiError(payload.code, payload.message, payload.traceId));
  },
  (error: AxiosError<ApiEnvelope<unknown>>) => {
    const status = error.response?.status;
    const payload = error.response?.data;

    // 后端用真实 HTTP 401 表示鉴权失败（见 §8.1 的 HTTP 状态码策略）
    if (status === 401) {
      const message = payload?.message || '登录已失效，请重新登录';
      handleAuthExpired(message);
      return Promise.reject(new ApiError(payload?.code ?? API_CODE.TOKEN_INVALID, message, payload?.traceId ?? ''));
    }
    if (error.code === 'ECONNABORTED') {
      const message = '请求超时，请稍后重试';
      toast(message, 'error');
      return Promise.reject(new ApiError(API_CODE.INTERNAL, message, ''));
    }
    if (!error.response) {
      const message = '网络异常，请检查后端服务是否已启动';
      toast(message, 'error');
      return Promise.reject(new ApiError(API_CODE.INTERNAL, message, ''));
    }
    const message = payload?.message || `请求失败（HTTP ${status ?? '未知'}）`;
    const traceId = payload?.traceId ?? '';
    toast(traceId ? `${message}（traceId: ${traceId}）` : message, 'error');
    return Promise.reject(new ApiError(payload?.code ?? API_CODE.INTERNAL, message, traceId));
  },
);

/**
 * GET 请求。
 *
 * <p><b>T09 扩展（D-2）</b>：新增第三个可选参数 {@code config}，让导出端点能传
 * {@code responseType: 'blob'}。这是向后兼容的纯增量——现有 5 个 {@code api/*.ts}
 * 只用前两参，行为完全不变，无需改一行调用代码，也避免为导出把 GET 改成 POST
 * （那会让 §4.2 的 #35/#36 契约漂移）。</p>
 *
 * @param url 相对路径（如 `/docs`）
 * @param params 查询参数
 * @param config 额外 axios 配置（如 `{ responseType: 'blob' }`）；显式 params 优先于 config.params
 * @returns 解包后的业务数据；blob 请求返回完整 {@link AxiosResponse}（见拦截器 D-3 分支）
 */
export function get<T>(url: string, params?: Record<string, unknown>, config?: AxiosRequestConfig): Promise<T> {
  const merged: AxiosRequestConfig = { ...(config ?? {}) };
  if (params !== undefined) {
    merged.params = params;
  }
  return instance.get(url, merged) as unknown as Promise<T>;
}

/**
 * POST 请求。
 *
 * @param url 相对路径
 * @param data 请求体
 * @param config 额外配置
 * @returns 解包后的业务数据
 */
export function post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return instance.post(url, data, config) as unknown as Promise<T>;
}

/**
 * PUT 请求。
 *
 * @param url 相对路径
 * @param data 请求体
 * @returns 解包后的业务数据
 */
export function put<T>(url: string, data?: unknown): Promise<T> {
  return instance.put(url, data) as unknown as Promise<T>;
}

/**
 * PATCH 请求。
 *
 * @param url 相对路径
 * @param data 请求体
 * @returns 解包后的业务数据
 */
export function patch<T>(url: string, data?: unknown): Promise<T> {
  return instance.patch(url, data) as unknown as Promise<T>;
}

/**
 * DELETE 请求。
 *
 * @param url 相对路径
 * @param params 查询参数
 * @returns 解包后的业务数据
 */
export function del<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  return instance.delete(url, { params }) as unknown as Promise<T>;
}

export default instance;
