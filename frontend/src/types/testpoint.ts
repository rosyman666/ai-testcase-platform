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

/**
 * 抽取任务的受理回执。
 *
 * `POST /api/testpoints/extract` 自 T06 起是**异步**的：立即返回 `{taskId, taskNo}`，
 * 真正的抽取由后台任务执行，进度走 `GET /api/tasks/{taskId}/stream`（`useTaskStream`）。
 *
 * **这不是抽取结果**。原先这里有一个 `TestPointExtractReport`（含 `unitCount` /
 * `results[]` 等逐单元明细），是 T05 同步时代的产物；后端改异步后那个结构再也不会返回，
 * 页面却仍按它渲染 `report.results.map(...)`，于是线上表现为**一提交就整页崩**
 * （`Cannot read properties of undefined (reading 'map')`）。
 *
 * 要逐单元明细请去 `/tasks/{taskId}` 的任务详情页——那里才有 item 级数据。
 */
export interface TestPointExtractAck {
  taskId: string;
  taskNo: string;
}
