/**
 * 测试点模块的 TypeScript 契约（与后端 `testpoint/dto/*` 一一对应）。
 *
 * **关键约定**：后端把 Long 型 ID 全局序列化成**字符串**，所以这里的 id 一律 `string`，
 * 绝不要写 `number`——雪花 ID 超过 JS `MAX_SAFE_INTEGER`，用 number 会被静默改写。
 */

/** 测试点状态（与后端 `TestPointStatus` 编码一致）。 */
export type TestPointStatus = 'ACTIVE' | 'DISABLED' | 'SUPERSEDED';

/** 风险等级（与后端 `RiskLevel` 编码一致）。 */
export type RiskLevelCode = 'HIGH' | 'MEDIUM' | 'LOW';

/** 分类（与后端 `CaseType` 编码一致）。 */
export type CaseTypeCode =
  | 'FUNCTIONAL'
  | 'BOUNDARY'
  | 'EXCEPTION'
  | 'SECURITY'
  | 'PERFORMANCE'
  | 'COMPATIBILITY';

/** 测试点（`GET /api/testpoints` 的 records 元素）。 */
export interface TestPointVO {
  id: string;
  docId: string;
  /** 来源接口 ID（纯文本文档为 null）。 */
  operationId: string | null;
  /** 来源章节 ID（OpenAPI 文档为 null）。 */
  sectionId: string | null;
  title: string;
  category: string;
  riskLevel: string;
  description: string;
  /** 可反查回原文的定位，如 `operation:createUser` / `section:3`。 */
  sourceRef: string;
  status: string;
  /** 抽取所用的提示词版本；手动补点为空串。 */
  promptVersion: string;
  contentHash: string;
  createdAt: string;
  updatedAt: string;
}

/** 列表查询参数（`GET /api/testpoints`）。 */
export interface TestPointListQuery {
  docId: string;
  /** 空则后端默认只返回 ACTIVE。 */
  status?: string;
  category?: string;
  page?: number;
  size?: number;
  sort?: string;
}

/** 手动补点入参（`POST /api/testpoints`）。 */
export interface TestPointCreateReq {
  docId: string;
  title: string;
  category: string;
  riskLevel?: string;
  description?: string;
}

/** 更新入参（`PATCH /api/testpoints/{id}`，字段全部可选）。 */
export interface TestPointUpdateReq {
  title?: string;
  category?: string;
  riskLevel?: string;
  description?: string;
  status?: string;
}

/** 批量启停入参（`POST /api/testpoints/batch-status`）。 */
export interface BatchStatusReq {
  ids: string[];
  status: string;
}

/** 一个待分析单元的抽取结果。 */
export interface TestPointExtractResult {
  sourceRef: string;
  success: boolean;
  parsedCount: number;
  insertedCount: number;
  /** 模型输出是否被修复过（为真说明这条提示词的输出约束不够硬）。 */
  repaired: boolean;
  /** 是否发生截断补全（为真应关注 max_tokens 或"一次要太多条"）。 */
  truncated: boolean;
  error: string | null;
}

/**
 * 抽取汇总报告。
 *
 * **注意**：T05 阶段 `POST /api/testpoints/extract` 同步返回本结构；
 * T06 起改为返回 `{taskId}` 并走 SSE（§11.1 D-26），届时本类型退化为进度推送的载荷。
 */
export interface TestPointExtractReport {
  docNo: string;
  unitCount: number;
  successCount: number;
  failedCount: number;
  parsedCount: number;
  insertedCount: number;
  results: TestPointExtractResult[];
}
