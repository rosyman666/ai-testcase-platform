/**
 * T09 用例导出（#35 cases.xlsx / #36 junit.zip）的前端契约类型。
 *
 * <p>所有 ID 一律 `string`：后端 `JacksonConfig` 全局把 `Long` 序列化成字符串
 * （见 `types/common.ts` 顶部约定），写 `number` 会在超过 JS 安全整数时被静默改写。</p>
 *
 * <p>导出上限**不硬编码**：`PublicConfig.limits.exportMaxRows` 是唯一真源，
 * 它对应后端 `export.maxRows`（默认 5000），与分页 `pageSizeMax(100)` 不是一回事。</p>
 */

import type { CaseStatusCode } from '@/types/cases';

/** 状态过滤值（与后端 `CaseStatus` 四值逐字一致，docs/07 §4.1）。 */
export type StatusFilter = CaseStatusCode;

/** 四态全集，顺序与 `CaseStatus` 枚举声明一致，供导出弹窗回显与校验。 */
export const ALL_STATUS_FILTERS: readonly StatusFilter[] = [
  'DRAFT',
  'ACCEPTED',
  'EDITED',
  'REJECTED',
] as const;

/**
 * 默认导出状态：`ACCEPTED` + `EDITED`。
 *
 * <p>后端在 `statuses` 未传 / 空串时也回落同一组；**空串不等于全量**。
 * 前端与后端必须同源，否则「界面显示已勾选两项、实际导出 4 项」会变成无声的口径漂移。</p>
 */
export const DEFAULT_EXPORT_STATUSES: readonly StatusFilter[] = [
  'ACCEPTED',
  'EDITED',
] as const;

/** 导出文件的扩展名（同时用于兜底文件名与 Content-Type 判别）。 */
export type ExportExtension = 'xlsx' | 'zip';

/** 导出格式（与扩展名同值，语义上指「按钮/格式选择」）。 */
export type ExportFormat = ExportExtension;

/** #35 / #36 共用的请求参数。 */
export interface ExportParams {
  /** 任务 ID（必填，后端 `taskId`：Long → string）。 */
  taskId: string;
  /** 文档 ID（可选）。 */
  docId?: string;
  /** 逗号分隔的状态白名单，如 `ACCEPTED,EDITED`；省略/空串回落默认两项。 */
  statuses?: string;
  /** 仅 #36 生效：生成用例 `.java` 的 Java 包名；非法包名后端返回 40001。 */
  packageName?: string;
}

/** 下载结果（由 `utils/download.ts` 产出）。 */
export interface ExportDownloadResult {
  filename: string;
}

/** 把状态数组序列化成后端接受的逗号分隔串。 */
export function toStatusesParam(statuses: readonly StatusFilter[]): string {
  return statuses.join(',');
}
