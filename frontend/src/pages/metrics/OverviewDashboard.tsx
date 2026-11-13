/**
 * T11 指标与成本看板（`/metrics`）页面壳。
 *
 * <p>4 个 Tab：概览 / 采纳趋势 / 成本 / 质量。<b>非激活 Tab 不挂载</b>：质量页（P1）
 * 只在点击后加载，避免首屏拖入 `QualityPanel`；两个图表页也各自按需 import recharts。
 * 概览 Tab 的查询抽到 `OverviewPanel` 子组件，同样只在激活时执行。</p>
 *
 * <p><b>范围标签与请求同源</b>：`range.from` / `range.to` 既用于标签文案，也直接作为
 * 请求参数发出；缺省也显式传 `from`/`to`，不靠后端默认值，避免「标签写近 7 天、实际
 * 不传参数」的口径漂移（§8 T04 完成判据 ⑤）。</p>
 *
 * <p><b>0 值</b>：只调 `resolveZeroDisplay()`（`@/types/metrics` 的唯一实现），
 * 页面内不另写 A/B/C/D 判定。</p>
 */

import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Tab from '@mui/material/Tab';
import Tabs from '@mui/material/Tabs';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import PageHeader from '@/components/common/PageHeader';
import EmptyState from '@/components/common/EmptyState';
import ChartCard from '@/components/charts/ChartCard';
import AcceptanceChart from '@/pages/metrics/AcceptanceChart';
import CostChart from '@/pages/metrics/CostChart';
import QualityPanel from '@/pages/metrics/QualityPanel';
import { fetchOverview, fetchRejectReasons } from '@/api/metrics';
import {
  COST_ZERO_REASON_LABEL,
  resolveZeroDisplay,
  type CostZeroReason,
  type MetricsRangeQuery,
  type OverviewVO,
  type RejectReasonPointVO,
} from '@/types/metrics';

/** 东八区固定偏移（毫秒）。与后端 `MetricsQuery.SHANGHAI_OFFSET_HOURS = 8` 同源，无 DST。 */
const SHANGHAI_OFFSET_MS = 8 * 3600 * 1000;

/** 一天毫秒数。 */
const DAY_MS = 24 * 3600 * 1000;

/** 近 7 天起始偏移（含今天共 7 天 → 往前 6 天）。 */
const SEVEN_DAYS_BACK = -6;

/** 近 30 天起始偏移（含今天共 30 天 → 往前 29 天）。 */
const THIRTY_DAYS_BACK = -29;

/**
 * 把时间戳格式化成东八区民用日 `yyyy-MM-dd`。
 *
 * <p>实现：把时刻平移到 UTC+8 后取 UTC 年月日——恒定 +8h、不依赖
 * `toLocaleDateString` / `Intl` / 时区 API（与后端固定偏移算术一致）。</p>
 *
 * @param epochMs 时间戳毫秒，默认当前时刻
 * @returns `yyyy-MM-dd`
 */
export function formatShanghaiDate(epochMs: number = Date.now()): string {
  const shifted = new Date(epochMs + SHANGHAI_OFFSET_MS);
  const year = shifted.getUTCFullYear();
  const month = String(shifted.getUTCMonth() + 1).padStart(2, '0');
  const day = String(shifted.getUTCDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

/**
 * 东八区「今天」的民用日。
 *
 * @returns `yyyy-MM-dd`
 */
export function shanghaiToday(): string {
  return formatShanghaiDate();
}

/**
 * 对 `yyyy-MM-dd` 纯日期加减天数（东八区民用日，无 DST、无时区 API）。
 *
 * @param dateStr `yyyy-MM-dd`
 * @param delta 天数（负数为向前）
 * @returns 新的 `yyyy-MM-dd`
 */
export function shiftDays(dateStr: string, delta: number): string {
  const [year, month, day] = dateStr.split('-').map(Number);
  const shifted = new Date(Date.UTC(year, month - 1, day) + delta * DAY_MS);
  const y = shifted.getUTCFullYear();
  const m = String(shifted.getUTCMonth() + 1).padStart(2, '0');
  const d = String(shifted.getUTCDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/** 时间范围预设。 */
type RangePreset = '7d' | '30d' | 'custom';

/** 时间范围状态。 */
interface RangeState {
  /** 当前预设（`custom` 表示用户手改过日期）。 */
  preset: RangePreset;
  /** 起始日 `yyyy-MM-dd`。 */
  from: string;
  /** 结束日 `yyyy-MM-dd`。 */
  to: string;
}

/**
 * 构造预设范围（近 7 天 / 近 30 天，含今天）。
 *
 * @param preset 预设
 * @returns 起止日期
 */
function buildRange(preset: Exclude<RangePreset, 'custom'>): { from: string; to: string } {
  const today = shanghaiToday();
  const back = preset === '7d' ? SEVEN_DAYS_BACK : THIRTY_DAYS_BACK;
  return { from: shiftDays(today, back), to: today };
}

/**
 * 百分比格式化（0~1 → `12.3%`）。
 *
 * @param value 比率
 * @returns 文本；`null` / `NaN` → `—`
 */
function formatPercent(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '—';
  }
  return `${(value * 100).toFixed(1)}%`;
}

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
 * 成本 0 的成因文案。
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
 * 单张指标卡。
 *
 * @param props 标签、值、是否弱化显示
 * @returns 卡片
 */
function MetricCard({
  label,
  value,
  muted = false,
}: {
  label: string;
  value: string;
  muted?: boolean;
}): JSX.Element {
  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="caption" color="text.secondary">
          {label}
        </Typography>
        <Typography
          variant="h6"
          color={muted ? 'text.secondary' : 'text.primary'}
          className="mt-1 break-words"
        >
          {value}
        </Typography>
      </CardContent>
    </Card>
  );
}

/**
 * 概览 Tab：6 卡片 + 驳回原因分布（#45）。
 *
 * @param props 起止日期
 * @returns 概览面板
 */
function OverviewPanel({ from, to }: { from: string; to: string }): JSX.Element {
  const rangeQuery: MetricsRangeQuery = useMemo(() => ({ from, to }), [from, to]);

  const overviewQuery = useQuery({
    queryKey: ['metrics', 'overview', from, to],
    queryFn: () => fetchOverview(rangeQuery),
  });
  const rejectQuery = useQuery({
    queryKey: ['metrics', 'reject-reasons', from, to],
    queryFn: () => fetchRejectReasons(rangeQuery),
  });

  const overview: OverviewVO | null = overviewQuery.data ?? null;
  const rejectReasons: RejectReasonPointVO[] = rejectQuery.data ?? [];

  const zeroKind = resolveZeroDisplay({
    overview,
    rejectReasonCount: rejectReasons.length,
    // 概览 Tab 不加载成本分布（成本点属于「成本」Tab），D 类只依赖驳回分布 + 生成数
    costPointCount: 0,
  });

  if (overviewQuery.isLoading) {
    return <EmptyState variant="loading" title="加载中…" />;
  }
  if (overviewQuery.isError) {
    return (
      <EmptyState
        variant="error"
        title="指标加载失败"
        description={overviewQuery.error instanceof Error ? overviewQuery.error.message : undefined}
      />
    );
  }
  if (zeroKind === 'D_EMPTY_DIST') {
    return <EmptyState variant="empty" title="该窗口内没有数据" description="请调整上方时间范围" />;
  }
  if (!overview) {
    return <EmptyState variant="empty" title="暂无数据" />;
  }

  const noReview = zeroKind === 'B_NO_REVIEW';
  const totalCostMicro = Number(overview.totalCostMicro);
  const totalTokens = Number(overview.totalPromptTokens) + Number(overview.totalCompletionTokens);
  const costZero = totalCostMicro === 0 && totalTokens !== 0;

  return (
    <Box className="space-y-6">
      <Box className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <MetricCard label="生成用例数" value={String(overview.generatedCount)} />
        <MetricCard
          label="采纳率"
          value={noReview ? '暂无评审数据' : formatPercent(overview.acceptRate)}
          muted={noReview}
        />
        <MetricCard
          label="编辑率"
          value={noReview ? '暂无评审数据' : formatPercent(overview.editedRate)}
          muted={noReview}
        />
        <MetricCard
          label="驳回率"
          value={noReview ? '暂无评审数据' : formatPercent(overview.rejectRate)}
          muted={noReview}
        />
        <MetricCard
          label="总成本"
          value={
            costZero
              ? `成本 0（原因：${formatCostZeroReason(overview.costZeroReasons)}）`
              : formatYuan(totalCostMicro)
          }
          muted={costZero}
        />
        <MetricCard label="耗时 p50 / p95" value={`${overview.p50Ms} ms / ${overview.p95Ms} ms`} />
      </Box>

      <ChartCard title="驳回原因分布" unit="按 reject_reason.code 聚合，reason_text 不参与">
        {rejectReasons.length === 0 ? (
          <EmptyState variant="empty" title="暂无驳回记录" />
        ) : (
          <Box className="space-y-2">
            {rejectReasons.map((point) => (
              <Box
                key={point.code}
                className="flex items-center justify-between border-b border-gray-100 pb-1"
              >
                <Typography variant="body2">{point.label}</Typography>
                <Typography variant="body2" color="text.secondary">
                  {point.count} 条（{formatPercent(point.ratio)}）
                </Typography>
              </Box>
            ))}
          </Box>
        )}
      </ChartCard>
    </Box>
  );
}

/**
 * 指标与成本看板页面。
 *
 * @returns 页面
 */
export default function OverviewDashboard(): JSX.Element {
  const [tab, setTab] = useState(0);
  const [range, setRange] = useState<RangeState>(() => {
    const { from, to } = buildRange('7d');
    return { preset: '7d', from, to };
  });

  /**
   * 应用一个预设。
   *
   * @param preset 预设值
   */
  const applyPreset = (preset: RangePreset): void => {
    if (preset === 'custom') {
      setRange((prev) => ({ ...prev, preset: 'custom' }));
      return;
    }
    const { from, to } = buildRange(preset);
    setRange({ preset, from, to });
  };

  const rangeLabel = `统计窗口：${range.from} ~ ${range.to}（Asia/Shanghai，含首尾）`;

  return (
    <Box className="mx-auto max-w-7xl p-6">
      <PageHeader
        title="指标与成本看板"
        subtitle={rangeLabel}
        actions={
          <Box className="flex flex-wrap items-center gap-2">
            <Button
              size="small"
              variant={range.preset === '7d' ? 'contained' : 'outlined'}
              onClick={() => applyPreset('7d')}
            >
              近 7 天
            </Button>
            <Button
              size="small"
              variant={range.preset === '30d' ? 'contained' : 'outlined'}
              onClick={() => applyPreset('30d')}
            >
              近 30 天
            </Button>
            <TextField
              type="date"
              size="small"
              label="起始日"
              value={range.from}
              onChange={(event) => setRange({ preset: 'custom', from: event.target.value, to: range.to })}
              InputLabelProps={{ shrink: true }}
              sx={{ width: 160 }}
            />
            <TextField
              type="date"
              size="small"
              label="结束日"
              value={range.to}
              onChange={(event) => setRange({ preset: 'custom', from: range.from, to: event.target.value })}
              InputLabelProps={{ shrink: true }}
              sx={{ width: 160 }}
            />
          </Box>
        }
      />

      <Tabs value={tab} onChange={(_event, next) => setTab(next)} className="mb-4">
        <Tab label="概览" />
        <Tab label="采纳趋势" />
        <Tab label="成本" />
        <Tab label="质量" />
      </Tabs>

      {tab === 0 && <OverviewPanel from={range.from} to={range.to} />}
      {tab === 1 && <AcceptanceChart from={range.from} to={range.to} />}
      {tab === 2 && <CostChart from={range.from} to={range.to} />}
      {tab === 3 && <QualityPanel from={range.from} to={range.to} />}
    </Box>
  );
}
