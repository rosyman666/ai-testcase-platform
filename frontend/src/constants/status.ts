/**
 * 三个状态域的 `code → {label, color}` 映射。**StatusChip 的唯一数据源**。
 *
 * 与 `theme.ts` 的 `STATUS_COLORS`（12 键）**双向覆盖无缺漏**：
 * - 每个 STATUS_COLORS 的键都必须在下面某个域里有 label（否则 Chip 显示裸 code）；
 * - 每个域的键都必须在 STATUS_COLORS 里有 color（否则 Chip 无色）。
 *
 * 三个域与 `STATUS_COLORS` 的对应关系（`theme.ts:22` 注释："任务状态、用例状态、执行结果三类"）：
 * - **任务域**（6 键）：PENDING/RUNNING/SUCCESS/PARTIAL/FAILED/CANCELED —— ⚠️ **没有 SKIPPED**
 * - **用例域**（4 键）：DRAFT/ACCEPTED/EDITED/REJECTED
 * - **执行结果域**（3 键，含 item 状态）：PASSED/FAILED/SKIPPED —— SKIPPED 的归属
 *
 * 为什么 item 状态（PENDING/RUNNING/SUCCESS/FAILED/SKIPPED）不需要独立域：
 * 它的 5 个键全部被任务域 ∪ 执行结果域覆盖，且 label 完全一致
 * （如 `SKIPPED` 在 item 明细里也叫"已跳过"）。StatusChip 按 code 查 label，不按域。
 */

import { STATUS_COLORS, type StatusKey } from '@/theme';

/** 状态展示元数据。 */
export interface StatusMeta {
  /** 中文文案。 */
  label: string;
  /** MUI Chip color（与 `STATUS_COLORS` 同值，冗余存一份避免两处查表）。 */
  color: 'default' | 'info' | 'success' | 'warning' | 'error';
}

/** 任务状态域（6 个，**没有 SKIPPED**——`TaskStatus.java` 只有 6 个常量）。 */
export const TASK_STATUS: Record<string, StatusMeta> = {
  PENDING: { label: '排队中', color: 'default' },
  RUNNING: { label: '执行中', color: 'info' },
  SUCCESS: { label: '成功', color: 'success' },
  PARTIAL: { label: '部分成功', color: 'warning' },
  FAILED: { label: '失败', color: 'error' },
  CANCELED: { label: '已取消', color: 'default' },
};

/** item 状态域（5 个，字符串字面量，无枚举类）。 */
export const ITEM_STATUS: Record<string, StatusMeta> = {
  PENDING: { label: '待执行', color: 'default' },
  RUNNING: { label: '执行中', color: 'info' },
  SUCCESS: { label: '成功', color: 'success' },
  FAILED: { label: '失败', color: 'error' },
  SKIPPED: { label: '已跳过', color: 'default' },
};

/** 用例状态域（4 个）。`CaseStatus.java` 的全部常量。 */
export const CASE_STATUS: Record<string, StatusMeta> = {
  DRAFT: { label: '待评审', color: 'default' },
  ACCEPTED: { label: '已采纳', color: 'success' },
  EDITED: { label: '编辑后采纳', color: 'info' },
  REJECTED: { label: '已驳回', color: 'error' },
};

/** 执行结果域（3 个）。`ExecutionStatus.java`，T10 执行导入时用。 */
export const EXECUTION_STATUS: Record<string, StatusMeta> = {
  PASSED: { label: '通过', color: 'success' },
  FAILED: { label: '失败', color: 'error' },
  SKIPPED: { label: '已跳过', color: 'default' },
};

/** 所有域的合并查找表（StatusChip 的实际查表入口）。 */
export const ALL_STATUS: Record<string, StatusMeta> = {
  ...TASK_STATUS,
  ...ITEM_STATUS,
  ...CASE_STATUS,
  ...EXECUTION_STATUS,
};

/**
 * 按 code 查状态元数据。
 *
 * @param code 状态码
 * @returns 元数据；未知 code 返回 `null`（调用方决定兜底显示裸 code 还是隐藏）
 */
export function getStatusMeta(code: string): StatusMeta | null {
  return ALL_STATUS[code] ?? null;
}

/**
 * 按 code 查颜色（与 `STATUS_COLORS` 严格一致，供需要纯色值的场景用）。
 *
 * @param code 状态码
 * @returns MUI Chip color；未知 code 返回 `default`
 */
export function getStatusColor(code: string): StatusMeta['color'] {
  return (STATUS_COLORS as Record<string, StatusMeta['color']>)[code] ?? 'default';
}

/**
 * 判断是否为已知状态码（类型守卫）。
 *
 * @param code 待检查的字符串
 * @returns 是否为 `StatusKey`
 */
export function isStatusKey(code: string): code is StatusKey {
  return code in STATUS_COLORS;
}
