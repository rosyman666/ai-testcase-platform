/**
 * 状态 Chip。消费 `constants/status.ts` 的映射（唯一数据源）。
 *
 * 5 处使用点（任务列表/任务详情/进度条/item 明细/用例看板），不抽就是 5 份本地映射。
 */

import Chip from '@mui/material/Chip';
import Tooltip from '@mui/material/Tooltip';
import { getStatusMeta } from '@/constants/status';

/** StatusChip 属性。 */
export interface StatusChipProps {
  /** 状态码（如 `RUNNING` / `ACCEPTED`）。 */
  code: string;
  /** 尺寸。 */
  size?: 'small' | 'medium';
  /** 额外 tooltip（如 item 级 errorCode 说明）。 */
  tooltip?: string;
}

/**
 * 状态 Chip。
 *
 * 未知 code 显示裸 code（灰色），不会崩溃。
 *
 * @param props 属性
 * @returns Chip 组件
 */
export default function StatusChip({ code, size = 'small', tooltip }: StatusChipProps): JSX.Element {
  const meta = getStatusMeta(code);
  const chip = (
    <Chip
      label={meta?.label ?? code}
      color={meta?.color ?? 'default'}
      size={size}
      variant={meta ? 'filled' : 'outlined'}
    />
  );
  return tooltip ? <Tooltip title={tooltip}>{chip}</Tooltip> : chip;
}
