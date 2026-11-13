/**
 * #44 按天采纳 / 驳回率趋势（recharts `LineChart`）。
 *
 * <p><b>分母 0 的点不画、不渲染「0%」</b>：`hasReviewData === false` 的点把比率置
 * `null`，`Line` 用 `connectNulls={false}` 让线断开；`Tooltip` 对该点显示
 * 「暂无评审数据」（§4.8 B 类）。空数组 → 「该窗口内没有可评审数据」。</p>
 *
 * <p>本文件是 `recharts` 的按需 import 点之一；`ChartCard` 刻意不 import，
 * 避免首屏把图表库拖进概览 Tab。</p>
 */

import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import { useQuery } from '@tanstack/react-query';
import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  type TooltipProps,
} from 'recharts';
import ChartCard from '@/components/charts/ChartCard';
import EmptyState from '@/components/common/EmptyState';
import { fetchAcceptance } from '@/api/metrics';

/** 图表属性。 */
export interface AcceptanceChartProps {
  /** 起始日 `yyyy-MM-dd`。 */
  from: string;
  /** 结束日 `yyyy-MM-dd`。 */
  to: string;
}

/** 单点绘图数据；`null` 表示该点无评审数据（线在此断开）。 */
interface AcceptanceChartPoint {
  key: string;
  acceptRate: number | null;
  rejectRate: number | null;
  hasReviewData: boolean;
}

/**
 * 百分比格式化。
 *
 * @param value 比率
 * @returns 文本；`null` → `—`
 */
function formatPercent(value: number | null): string {
  if (value === null) {
    return '—';
  }
  return `${(value * 100).toFixed(1)}%`;
}

/**
 * 自定义 Tooltip：无评审数据的点显示「暂无评审数据」，不显示 0%。
 *
 * @param props recharts 注入的 tooltip 属性
 * @returns tooltip 内容
 */
function AcceptanceTooltip(props: TooltipProps<number, string>): JSX.Element | null {
  const { active, payload, label } = props;
  if (!active || !payload || payload.length === 0) {
    return null;
  }
  const point = payload[0]?.payload as AcceptanceChartPoint | undefined;
  return (
    <Box sx={{ bgcolor: 'background.paper', p: 1, borderRadius: 1, boxShadow: 2 }}>
      <Typography variant="caption">{String(label ?? '')}</Typography>
      {point?.hasReviewData ? (
        <>
          <Typography variant="body2">采纳率：{formatPercent(point.acceptRate)}</Typography>
          <Typography variant="body2">驳回率：{formatPercent(point.rejectRate)}</Typography>
        </>
      ) : (
        <Typography variant="body2" color="text.secondary">
          暂无评审数据
        </Typography>
      )}
    </Box>
  );
}

/**
 * 按天采纳 / 驳回率趋势。
 *
 * @param props 起止日期
 * @returns 图表
 */
export default function AcceptanceChart({ from, to }: AcceptanceChartProps): JSX.Element {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['metrics', 'acceptance', from, to],
    queryFn: () => fetchAcceptance({ groupBy: 'day', from, to }),
  });

  const points: AcceptanceChartPoint[] = (data ?? []).map((point) => ({
    key: point.key,
    acceptRate: point.hasReviewData ? point.acceptRate : null,
    rejectRate: point.hasReviewData ? point.rejectRate : null,
    hasReviewData: point.hasReviewData,
  }));

  if (isLoading) {
    return <ChartCard title="按天采纳 / 驳回率" loading />;
  }
  if (isError) {
    return (
      <ChartCard title="按天采纳 / 驳回率">
        <EmptyState
          variant="error"
          title="采纳趋势加载失败"
          description={error instanceof Error ? error.message : undefined}
        />
      </ChartCard>
    );
  }

  return (
    <ChartCard
      title="按天采纳 / 驳回率"
      unit="分母为当日已评审数；无评审数据的点不绘制"
      empty={points.length === 0}
      emptyText="该窗口内没有可评审数据"
    >
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={points} margin={{ top: 8, right: 16, bottom: 8, left: 0 }}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis dataKey="key" />
          <YAxis domain={[0, 1]} tickFormatter={(value: number) => `${Math.round(value * 100)}%`} />
          <Tooltip content={<AcceptanceTooltip />} />
          <Legend />
          <Line
            type="monotone"
            dataKey="acceptRate"
            name="采纳率"
            stroke="#2e7d32"
            connectNulls={false}
            dot
          />
          <Line
            type="monotone"
            dataKey="rejectRate"
            name="驳回率"
            stroke="#c62828"
            connectNulls={false}
            dot
          />
        </LineChart>
      </ResponsiveContainer>
    </ChartCard>
  );
}
