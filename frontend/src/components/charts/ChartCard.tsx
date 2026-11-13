/**
 * 图表统一容器：标题 / 单位副标题 / 加载 / 空态 / 高度。
 *
 * <p><b>刻意不 import recharts</b>：图表库若在壳里 import，`OverviewDashboard` 一加载
 * 就会把 recharts 拖进首屏 chunk，4 个 Tab 全部变重。`AcceptanceChart` / `CostChart`
 * 各自 import，由 `vite.config.ts` 的 `charts` manualChunks 单独切块。
 * 这是对 docs/07 §2.4「recharts 在 ChartCard 首次 import」一行措辞的收窄
 * （team-lead 批次 2 明确授权，待 T05 登记偏差）。</p>
 */

import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import type { ReactNode } from 'react';
import EmptyState from '@/components/common/EmptyState';

/** ChartCard 属性。 */
export interface ChartCardProps {
  /** 图表标题。 */
  title: string;
  /** 副标题 / 单位说明（可选）。 */
  unit?: string;
  /** 是否加载中。 */
  loading?: boolean;
  /** 是否空态。 */
  empty?: boolean;
  /** 空态主文案。 */
  emptyText?: string;
  /** 图表区高度（px 或 CSS 长度）。 */
  height?: number | string;
  /** 图表内容。 */
  children?: ReactNode;
}

/**
 * 图表统一容器。
 *
 * @param props 属性
 * @returns 图表卡片
 */
export default function ChartCard({
  title,
  unit,
  loading = false,
  empty = false,
  emptyText = '暂无数据',
  height = 320,
  children,
}: ChartCardProps): JSX.Element {
  return (
    <Box>
      <Box className="mb-2 flex items-baseline justify-between gap-2">
        <Typography variant="subtitle1" component="h2">
          {title}
        </Typography>
        {unit && (
          <Typography variant="caption" color="text.secondary">
            {unit}
          </Typography>
        )}
      </Box>
      {loading ? (
        <EmptyState variant="loading" title="加载中…" />
      ) : empty ? (
        <EmptyState variant="empty" title={emptyText} />
      ) : (
        <Box sx={{ height, width: '100%' }}>{children}</Box>
      )}
    </Box>
  );
}
