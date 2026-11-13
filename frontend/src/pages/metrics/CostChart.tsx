/**
 * #46 成本三视角（task / day / model）。
 *
 * <p>严格展示后端 `SUM(llm_call_log.cost_micro)` 落库值，页面不现算、不伪造。
 * C 类（`Number(totalCostMicro) === 0 && tokens != 0`）显示 `0` + 成因文案，
 * 不隐藏、不留一条无说明的贴地 0 线（§4.8）。</p>
 */

import { useState } from 'react';
import Box from '@mui/material/Box';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import MenuItem from '@mui/material/MenuItem';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useQuery } from '@tanstack/react-query';
import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import ChartCard from '@/components/charts/ChartCard';
import EmptyState from '@/components/common/EmptyState';
import { fetchCost } from '@/api/metrics';
import { COST_ZERO_REASON_LABEL, type CostZeroReason } from '@/types/metrics';

/** 图表属性。 */
export interface CostChartProps {
  /** 起始日 `yyyy-MM-dd`。 */
  from: string;
  /** 结束日 `yyyy-MM-dd`。 */
  to: string;
}

/** 分组视角（只允许后端支持的三值，不得出现 template/category 等不可用项）。 */
type CostGroupBy = 'task' | 'day' | 'model';

/** 分组下拉选项。 */
const GROUP_OPTIONS: ReadonlyArray<{ value: CostGroupBy; label: string }> = [
  { value: 'task', label: '按任务' },
  { value: 'day', label: '按天' },
  { value: 'model', label: '按模型' },
];

/**
 * 微元 → 元。
 *
 * @param micro 微元（1 元 = 1_000_000 微元）
 * @returns 形如 `3.50 元`
 */
function formatYuan(micro: number): string {
  return `${(micro / 1_000_000).toFixed(2)} 元`;
}

/**
 * 成本 0 成因文案。
 *
 * @param reasons 成因枚举
 * @returns 中文，多条以「；」连接；未知值原样输出
 */
function formatCostZeroReason(reasons: readonly CostZeroReason[]): string {
  if (reasons.length === 0) {
    return '未知原因';
  }
  return reasons.map((reason) => COST_ZERO_REASON_LABEL[reason] ?? reason).join('；');
}

/**
 * 成本视图。
 *
 * @param props 起止日期
 * @returns 成本图表与卡片
 */
export default function CostChart({ from, to }: CostChartProps): JSX.Element {
  const [groupBy, setGroupBy] = useState<CostGroupBy>('task');
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['metrics', 'cost', groupBy, from, to],
    queryFn: () => fetchCost({ groupBy, from, to }),
  });

  const points = data?.points ?? [];
  const totalCostMicro = Number(data?.totalCostMicro ?? '0');
  const totalTokens =
    Number(data?.totalPromptTokens ?? '0') + Number(data?.totalCompletionTokens ?? '0');
  const unitCostMicro = Number(data?.unitCostMicro ?? '0');
  const acceptedCaseCount = data?.acceptedCaseCount ?? '0';
  const reasons = data?.costZeroReasons ?? [];
  const costZero = totalCostMicro === 0 && totalTokens !== 0;

  const chartData = points.map((point) => ({
    key: point.key,
    label: point.taskNo ?? point.key,
    costMicro: Number(point.costMicro),
  }));

  return (
    <Box className="space-y-4">
      <Box className="flex items-center gap-3">
        <TextField
          select
          size="small"
          label="分组视角"
          value={groupBy}
          onChange={(event) => setGroupBy(event.target.value as CostGroupBy)}
          sx={{ minWidth: 160 }}
        >
          {GROUP_OPTIONS.map((option) => (
            <MenuItem key={option.value} value={option.value}>
              {option.label}
            </MenuItem>
          ))}
        </TextField>
      </Box>

      <Box className="grid gap-4 sm:grid-cols-3">
        <Card variant="outlined">
          <CardContent>
            <Typography variant="caption" color="text.secondary">
              总成本
            </Typography>
            {costZero ? (
              <Typography variant="body1" color="warning.main" className="mt-1">
                成本 0（原因：{formatCostZeroReason(reasons)}）
              </Typography>
            ) : (
              <Typography variant="h6" className="mt-1">
                {formatYuan(totalCostMicro)}
              </Typography>
            )}
          </CardContent>
        </Card>
        <Card variant="outlined">
          <CardContent>
            <Typography variant="caption" color="text.secondary">
              单用例成本
            </Typography>
            <Typography variant="h6" className="mt-1">
              {formatYuan(unitCostMicro)}
            </Typography>
          </CardContent>
        </Card>
        <Card variant="outlined">
          <CardContent>
            <Typography variant="caption" color="text.secondary">
              已采纳用例数
            </Typography>
            <Typography variant="h6" className="mt-1">
              {acceptedCaseCount}
            </Typography>
          </CardContent>
        </Card>
      </Box>

      <ChartCard
        title="成本分布"
        unit="严格 SUM(llm_call_log.cost_micro)，不现算"
        loading={isLoading}
        empty={!isLoading && !isError && chartData.length === 0}
        emptyText="该窗口内没有成本记录"
      >
        {isError ? (
          <EmptyState
            variant="error"
            title="成本数据加载失败"
            description={error instanceof Error ? error.message : undefined}
          />
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={chartData} margin={{ top: 8, right: 16, bottom: 8, left: 0 }}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="label" />
              <YAxis tickFormatter={(value: number) => formatYuan(value)} />
              <Tooltip formatter={(value) => formatYuan(Number(value))} />
              <Bar dataKey="costMicro" name="成本" fill="#1565c0" />
            </BarChart>
          </ResponsiveContainer>
        )}
      </ChartCard>
    </Box>
  );
}
