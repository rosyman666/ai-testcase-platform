/**
 * 失败分类分布饼图（仓库里**第一个** `PieChart`）。
 *
 * <p><b>本文件是唯一 import `recharts` 的地方（与 `AcceptanceChart` / `CostChart` 并列）。
 * `components/charts/ChartCard.tsx` 绝对不许 import recharts（D-53）</b>：
 * 图表库若在壳里 import，任何加载 `ChartCard` 的页面都会把 recharts 拖进首屏 chunk。
 * recharts 的按需切块由 `vite.config.ts` 的 `manualChunks: { charts: ['recharts'] }` 完成。</p>
 *
 * <p><b>空数组不是错误</b>：本批后端 `categoryDistribution` 恒返回 `[]`
 * （六类归因属下一批 T10-3），此时显示「尚未归因」，不要显示错误态——
 * 错误态会让人以为导入失败。</p>
 */

import { Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts';
import ChartCard from '@/components/charts/ChartCard';
import type { ExecutionCategoryCountVO } from '@/types/execution';

/**
 * 饼图配色。
 *
 * 取 theme 主色系 + `constants/status.ts` 的失败色（`#dc2626`）作为首块：
 * 失败分类的语义核心就是"哪儿疼"，最大的一块通常是最该处理的类别。
 * 颜色**只用于区分相邻扇区**，不表达好坏，所以不做"越红越严重"的映射。
 */
const PIE_COLORS: readonly string[] = [
  '#dc2626',
  '#d97706',
  '#2563eb',
  '#7c3aed',
  '#0891b2',
  '#4d7c0f',
  '#be185d',
  '#475569',
];

/** 组件属性。 */
export interface FailurePieProps {
  /** 失败分类分布（后端聚合结果，前端不再统计）。 */
  data: ExecutionCategoryCountVO[];
  /** 空态主文案（详情页可换成更具体的说法）。 */
  emptyText?: string;
}

/**
 * 失败分类分布饼图。
 *
 * @param props 分布数据与空态文案
 * @returns 图表卡片
 */
export default function FailurePie({
  data,
  emptyText = '尚未归因：本批还没有失败分类数据',
}: FailurePieProps): JSX.Element {
  return (
    <ChartCard
      title="失败分类分布"
      unit="按失败记录的分类聚合，来自后端同一 SQL；空 = 尚未归因"
      empty={data.length === 0}
      emptyText={emptyText}
      height={300}
    >
      <ResponsiveContainer width="100%" height="100%">
        <PieChart>
          <Pie data={data} dataKey="count" nameKey="label" cx="50%" cy="50%" outerRadius={95}>
            {data.map((entry, index) => (
              // key 用 category（稳定业务码），不用 label：分类改名后 key 不应跟着变；
              // 颜色按数组下标轮转，保证相邻扇区不同色
              <Cell key={entry.category} fill={PIE_COLORS[index % PIE_COLORS.length]} />
            ))}
          </Pie>
          <Tooltip />
          <Legend />
        </PieChart>
      </ResponsiveContainer>
    </ChartCard>
  );
}
