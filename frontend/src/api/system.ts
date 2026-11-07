import { get } from '@/api/http';

/**
 * 健康检查数据（`GET /api/health`）。
 *
 * `time` 是 ISO-8601 UTC 字符串（后端固定 3 位毫秒 + `Z`）。
 */
export interface HealthData {
  /** 应用状态：UP / DEGRADED */
  status: string;
  /** 数据库连通性 */
  dbOk: boolean;
  /** 当前模型供应商 */
  provider: string;
  /** 当前模型名 */
  model: string;
  /** 服务端时间（ISO-8601 UTC） */
  time: string;
}

/**
 * 查询后端健康状态（鉴权白名单接口，未登录也可调用）。
 *
 * 走 `http.ts` 统一出口：401 处理、traceId 归一化、data 解包都由拦截器完成，
 * 组件里不得出现裸 `axios.get`（见 §8.5）。
 *
 * @returns 健康检查数据
 */
export function fetchHealth(): Promise<HealthData> {
  return get<HealthData>('/health');
}
