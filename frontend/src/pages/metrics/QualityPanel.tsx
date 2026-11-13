/**
 * #47 质量面板（P1，本批仍交付，不进首屏）。
 *
 * <p>三项必交付：首次通过率 / 解析失败率 / `p50`·`p95` 耗时。
 * <b>全页不得出现「平均耗时」「avg」</b>：§8.3 1484 明确只用分位数
 * （「长尾会骗人」），`avg` 会把长尾摊平导致误判。</p>
 */

import Box from '@mui/material/Box';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Typography from '@mui/material/Typography';
import { useQuery } from '@tanstack/react-query';
import EmptyState from '@/components/common/EmptyState';
import { fetchQuality } from '@/api/metrics';

/** 面板属性。 */
export interface QualityPanelProps {
  /** 起始日 `yyyy-MM-dd`。 */
  from: string;
  /** 结束日 `yyyy-MM-dd`。 */
  to: string;
}

/**
 * 百分比格式化。
 *
 * @param value 比率
 * @returns 文本
 */
function formatPercent(value: number): string {
  return `${(value * 100).toFixed(1)}%`;
}

/**
 * 质量面板。
 *
 * @param props 起止日期
 * @returns 质量卡片
 */
export default function QualityPanel({ from, to }: QualityPanelProps): JSX.Element {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['metrics', 'quality', from, to],
    queryFn: () => fetchQuality({ from, to }),
  });

  if (isLoading) {
    return <EmptyState variant="loading" title="加载中…" />;
  }
  if (isError) {
    return (
      <EmptyState
        variant="error"
        title="质量数据加载失败"
        description={error instanceof Error ? error.message : undefined}
      />
    );
  }
  if (!data) {
    return <EmptyState variant="empty" title="暂无质量数据" />;
  }

  const cards: ReadonlyArray<{ label: string; value: string }> = [
    { label: '首次通过率', value: formatPercent(data.firstPassRate) },
    { label: '解析失败率', value: formatPercent(data.parseFailRate) },
    { label: '生成成功率', value: formatPercent(data.generateSuccessRate) },
    { label: '耗时 p50', value: `${data.p50Ms} ms` },
    { label: '耗时 p95', value: `${data.p95Ms} ms` },
    { label: '条目总数', value: data.totalItems },
    { label: '调用总数', value: data.totalCalls },
  ];

  return (
    <Box className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {cards.map((card) => (
        <Card key={card.label} variant="outlined">
          <CardContent>
            <Typography variant="caption" color="text.secondary">
              {card.label}
            </Typography>
            <Typography variant="h6" className="mt-1">
              {card.value}
            </Typography>
          </CardContent>
        </Card>
      ))}
    </Box>
  );
}
