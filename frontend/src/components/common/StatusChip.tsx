/**
 * 状态 Chip。消费 `constants/status.ts` 的映射（唯一数据源）。
 *
 * 7 处调用点（5 个文件，`ProgressTimeline` 与 `TaskDetail` 各 2 处），
 * 按语义角色可归为 5 类（任务列表 / 任务详情 / 进度条 / item 明细 / 用例看板）；
 * 不抽就是 5 份本地映射。
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
 * 已告警过的未知状态码。
 *
 * 放 module 级而不是 `useRef`：`useRef` 是每实例一份，一屏 50 行脏状态就是 50 条 warn，
 * 而 warn 被刷屏的后果是**没人再看它**——护栏自己把自己废掉。
 * 放这里意味着同一 code 在应用生命周期内只告警一次；代价是测试里想重复观测必须
 * 重新加载模块（见 `__tests__/StatusChip.test.tsx` 的说明）。
 */
const warnedCodes = new Set<string>();

/**
 * 状态 Chip。
 *
 * 未知 code 显示裸 code（灰色 outlined），不会崩溃——**裸 code 是排障时唯一的线索**，
 * 换成"未知状态"之类的通用文案等于把线索删掉让人去翻控制台。
 * 与之配套：首次遇到未知 code 时打一条 `console.warn`，把"静默变灰"变成"有信号"，
 * 否则后端加了枚举、前端没同步时，界面只是默默变灰，没有任何失败信号。
 *
 * 告警放在组件里而不是 `getStatusMeta()` 里：后者是纯查表函数（契约是未知返回 `null`），
 * 往里塞副作用会污染所有潜在消费者。
 *
 * @param props 属性
 * @returns Chip 组件
 */
export default function StatusChip({ code, size = 'small', tooltip }: StatusChipProps): JSX.Element {
  const meta = getStatusMeta(code);

  if (!meta && !warnedCodes.has(code)) {
    warnedCodes.add(code);
    // 不做 import.meta.env.DEV 门控：vitest 的 mode 是 test、DEV 恒为真，门控在 CI 上
    // 买不到任何东西；而生产恰恰是"后端加了枚举、前端没同步"最先暴露的地方
    console.warn(
      `[StatusChip] 未知状态码 "${code}"：请同步 constants/status.ts，` +
        '或改用对应域的映射（如 getParseStatusMeta）',
    );
  }

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
