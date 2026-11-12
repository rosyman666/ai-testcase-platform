/**
 * 与后端契约一致的类型定义（见架构文档 §8.1 / §8.2 / §4.2）。
 *
 * **关键约定**：所有 Long 型 ID 在 JSON 里都是**字符串**（后端 JacksonConfig 全局把
 * Long 序列化成 String，避免超过 JS MAX_SAFE_INTEGER 被静默改写）。
 * 因此这里的 id 一律声明为 `string`，绝不要写 `number`。
 */

/** 后端统一响应体。 */
export interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T;
  traceId: string;
}

/** 通用分页出参。 */
export interface PageResult<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
  hasNext: boolean;
}

/** 字典项：数据库字典与代码枚举统一成这个形状。 */
export interface CodeLabel {
  code: string;
  label: string;
  sortNo: number;
}

/** 公开配置中的应用信息。 */
export interface AppInfo {
  name: string;
  version: string;
  aiProvider: string;
  aiModel: string;
  serverTime: string;
}

/** 公开配置中的只读限制值。 */
export interface Limits {
  pageSizeMax: number;
  uploadMaxMb: number;
  exportMaxRows: number;
}

/** `GET /api/config/public` 出参。 */
export interface PublicConfig {
  app: AppInfo;
  docTypes: CodeLabel[];
  parseStatuses: CodeLabel[];
  caseTypes: CodeLabel[];
  priorities: CodeLabel[];
  caseStatuses: CodeLabel[];
  taskStatuses: CodeLabel[];
  taskScenes: CodeLabel[];
  riskLevels: CodeLabel[];
  testPointStatuses: CodeLabel[];
  executionStatuses: CodeLabel[];
  triageSources: CodeLabel[];
  limits: Limits;
}

/** 当前用户（`GET /api/auth/me`）。 */
export interface UserVO {
  id: string;
  username: string;
  displayName: string;
  enabled: boolean;
  createdAt: string;
}

/** 登录出参。 */
export interface LoginResponse {
  token: string;
  expiresAt: string;
  displayName: string;
  userId: string;
  username: string;
}

/** 失败响应的业务错误码（与后端 ErrorCode 保持同步，只列前端需要分支的）。 */
export const API_CODE = {
  OK: 0,
  PARAM_INVALID: 40001,
  UNAUTHORIZED: 40101,
  TOKEN_INVALID: 40102,
  FORBIDDEN: 40301,
  NOT_FOUND: 40401,
  /** T09 裁决 ⑤：导出空集 / 超上限的业务码，供 blob 判别分支识别「不下载」。 */
  EMPTY_RESULT: 40003,
  INTERNAL: 50000,
} as const;

/** 需要清 token 并跳登录页的码值集合。 */
export const AUTH_EXPIRED_CODES: ReadonlySet<number> = new Set<number>([
  API_CODE.UNAUTHORIZED,
  API_CODE.TOKEN_INVALID,
]);
