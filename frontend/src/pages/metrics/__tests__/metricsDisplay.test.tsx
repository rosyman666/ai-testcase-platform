/**
 * T11 前端 0 值分层 A/B/C/D 与范围一致性的展示回归（docs/07 §4.8 / §8 T04）。
 *
 * <p>用 `vi.mock` 造 API 数据，不起 msw、不发真网络。断言 `textContent` 而不是
 * 具体 DOM 结构，避免把测试焊死在样式上。</p>
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement, ReactNode } from 'react';

vi.mock('@/api/metrics', () => ({
  fetchOverview: vi.fn(),
  fetchAcceptance: vi.fn(),
  fetchRejectReasons: vi.fn(),
  fetchCost: vi.fn(),
  fetchQuality: vi.fn(),
}));

vi.mock('@/api/auth', () => ({
  fetchPublicConfig: vi.fn(),
}));

/**
 * recharts 打桩。
 *
 * <p>为什么必须打桩而不是靠真实渲染来断言"柱子出现了"：jsdom 里没有布局，
 * `ResponsiveContainer` 量到 0×0 就干脆不渲染内部图表，于是"有数据"和"没数据"
 * 在 DOM 上几乎一样——这正是假绿的温床。桩件让"BarChart 是否被挂载"变成
 * 可以直接查的元素，与布局无关。注意 <b>不 mock `ChartCard`</b>：D-53 要求它
 * 永不 import recharts，图表库只在这三个图表组件里出现。</p>
 */
vi.mock('recharts', () => {
  /** 容器桩：把子节点原样渲染出来（真实 ResponsiveContainer 会量尺寸，这里不能）。 */
  const Passthrough = ({ children }: { children?: ReactNode }) => <div>{children}</div>;
  return {
    ResponsiveContainer: Passthrough,
    BarChart: () => <div data-testid="cost-bar-chart" />,
    LineChart: () => <div data-testid="acceptance-line-chart" />,
    PieChart: () => <div data-testid="failure-pie-chart" />,
    Bar: () => null,
    Line: () => null,
    Pie: () => null,
    Cell: () => null,
    CartesianGrid: () => null,
    XAxis: () => null,
    YAxis: () => null,
    Tooltip: () => null,
    Legend: () => null,
  };
});

import { fetchAcceptance, fetchCost, fetchOverview, fetchQuality, fetchRejectReasons } from '@/api/metrics';
import OverviewDashboard, { shanghaiToday, shiftDays } from '@/pages/metrics/OverviewDashboard';
import CostChart from '@/pages/metrics/CostChart';
import type { CostVO, OverviewVO, QualityVO } from '@/types/metrics';

/**
 * 造一份 OverviewVO：`long` 字段为字符串，比率 number，符合 JSON 序列化契约。
 *
 * @param overrides 覆盖字段
 * @returns OverviewVO
 */
function buildOverview(overrides: Partial<OverviewVO> = {}): OverviewVO {
  return {
    generatedCount: '10',
    reviewedCount: '6',
    draftCount: '4',
    acceptedCount: '3',
    editedCount: '1',
    rejectedCount: '2',
    acceptRate: 4 / 6,
    editedRate: 1 / 6,
    rejectRate: 2 / 6,
    strictAcceptRate: 3 / 6,
    totalCostMicro: '3500000',
    totalPromptTokens: '1000',
    totalCompletionTokens: '500',
    unitCostMicro: '875000',
    p50Ms: '1200',
    p95Ms: '4800',
    costZeroReasons: [],
    hasReviewData: true,
    ...overrides,
  };
}

/** 造一份 CostVO：`long` 字段一律字符串，符合后端 JSON 契约。 */
function buildCost(overrides: Partial<CostVO> = {}): CostVO {
  return {
    groupBy: 'task',
    points: [],
    totalCostMicro: '0',
    totalPromptTokens: '0',
    totalCompletionTokens: '0',
    unitCostMicro: '0',
    acceptedCaseCount: '0',
    costZeroReasons: [],
    ...overrides,
  };
}

/** 造一份 QualityVO（质量面板直接读字段，不打桩会拿到 undefined 而炸）。 */
function buildQuality(overrides: Partial<QualityVO> = {}): QualityVO {
  return {
    firstPassRate: 0.5,
    generateSuccessRate: 1,
    parseFailRate: 0,
    p50Ms: '1200',
    p95Ms: '4800',
    totalItems: '10',
    totalCalls: '20',
    ...overrides,
  };
}

/**
 * 渲染概览页并包独立 QueryClient（避免缓存串味）。
 *
 * @returns render 结果
 */
function renderDashboard(): ReturnType<typeof render> {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const element: ReactElement = (
    <QueryClientProvider client={client}>
      <OverviewDashboard />
    </QueryClientProvider>
  );
  return render(element);
}

describe('OverviewDashboard 0 值分层与范围标签', () => {
  beforeEach(() => {
    vi.mocked(fetchRejectReasons).mockResolvedValue([]);
  });

  it('B 类：reviewedCount=0 + hasReviewData=false → 不出现 0%，出现「暂无评审数据」', async () => {
    vi.mocked(fetchOverview).mockResolvedValue(
      buildOverview({
        generatedCount: '0',
        reviewedCount: '0',
        hasReviewData: false,
        totalCostMicro: '0',
        totalPromptTokens: '0',
        totalCompletionTokens: '0',
        p50Ms: '0',
        p95Ms: '0',
      }),
    );

    const { container } = renderDashboard();

    await waitFor(() => {
      expect(container.textContent).toContain('暂无评审数据');
    });
    expect(container.textContent).not.toContain('0%');
  });

  it('D 类：rejectReasons 返回 [] → 出现「暂无驳回记录」', async () => {
    vi.mocked(fetchOverview).mockResolvedValue(buildOverview());
    vi.mocked(fetchRejectReasons).mockResolvedValue([]);

    const { container } = renderDashboard();

    await waitFor(() => {
      expect(container.textContent).toContain('暂无驳回记录');
    });
  });

  it('C 类：totalCostMicro=0 且 token 非 0 → 出现「成本 0」+ 成因中文', async () => {
    vi.mocked(fetchOverview).mockResolvedValue(
      buildOverview({
        totalCostMicro: '0',
        totalPromptTokens: '1000',
        totalCompletionTokens: '500',
        costZeroReasons: ['MOCK_PRICE_ZERO'],
      }),
    );

    const { container } = renderDashboard();

    await waitFor(() => {
      expect(container.textContent).toContain('成本 0');
    });
    expect(container.textContent).toContain('mock-model 单价配置为 0/0');
  });

  it('范围一致性：窗口标签同时包含默认 from 与 to 两个日期', async () => {
    vi.mocked(fetchOverview).mockResolvedValue(buildOverview());

    const { container } = renderDashboard();
    const today = shanghaiToday();
    const from = shiftDays(today, -6);

    await waitFor(() => {
      const text = container.textContent ?? '';
      expect(text).toContain(from);
      expect(text).toContain(today);
    });
  });

  /**
   * Tab 的 a11y：`aria-controls` 必须指向**真实存在**的 `role="tabpanel"`。
   *
   * 两种毛病都在这条里挡：
   * 1. 四个 tab 全都没有面板（QA 原始实测 `[role="tabpanel"]` 计数 0）；
   * 2. 四个 tab 都写 `aria-controls` 但只有一个面板存在 —— 未选中那三个指向的 id
   *    在 DOM 里根本不存在（`getElementById` 返回 null），是**悬空引用**，
   *    和"引用不存在的符号/不成立的推理"是同一类问题：看起来完全正常，实则没有目标。
   *
   * 表驱动逐点击是必须的：只测初始状态的话，把 `aria-controls` 写死成
   * `metrics-tabpanel-0` 也能过——那正好是第 2 种毛病。
   *
   * 同时保留"`tabpanel` 恰好 1 个"：非激活 Tab 不挂载是刻意的性能设计
   * （质量页有 P1 性能问题），补齐四个面板会把首屏拖重。
   */
  it('Tab a11y：只有激活的 tab 有 aria-controls，且它指向真实存在的 panel', async () => {
    vi.mocked(fetchOverview).mockResolvedValue(buildOverview());
    // 点其它 Tab 会挂载对应面板，它们各自发请求；不打桩会在 jsdom 里真发网络
    vi.mocked(fetchAcceptance).mockResolvedValue([]);
    vi.mocked(fetchCost).mockResolvedValue(buildCost());
    vi.mocked(fetchQuality).mockResolvedValue(buildQuality());

    const { container } = renderDashboard();

    await waitFor(() => {
      expect(container.querySelectorAll('[role="tab"]')).toHaveLength(4);
    });
    const tabs = Array.from(container.querySelectorAll<HTMLElement>('[role="tab"]'));

    // 逐个激活：每切换一次都要重新验一遍"谁是激活项、谁没有 aria-controls"
    for (const [activeIndex, activeTab] of tabs.entries()) {
      fireEvent.click(activeTab);

      await waitFor(() => {
        expect(container.querySelectorAll('[role="tabpanel"]')).toHaveLength(1);
        expect(container.querySelector('[role="tabpanel"]')?.id).toBe(
          `metrics-tabpanel-${activeIndex}`,
        );
      });

      tabs.forEach((tab, index) => {
        const controls = tab.getAttribute('aria-controls');
        if (index === activeIndex) {
          expect(controls).toBe(`metrics-tabpanel-${activeIndex}`);
          // 悬空引用检测：id 必须真的能取到节点，而不是"写了一个好看的字符串"
          expect(document.getElementById(`metrics-tabpanel-${activeIndex}`)).not.toBeNull();
          expect(
            container.querySelector('[role="tabpanel"]')?.getAttribute('aria-labelledby'),
          ).toBe(`metrics-tab-${activeIndex}`);
        } else {
          // 未激活的 tab 不写 aria-controls：为 null，不是空串、也不是字符串 "undefined"
          expect(controls).toBeNull();
        }
      });
    }
  });
});

/**
 * 柱图全 0 的空态（收尾批次补的 A 类边界）。
 *
 * <p>原来的 `empty` 只看 `chartData.length === 0`，于是「有记录但成本全记为 0」
 * ——本地 / mock 模型不计价就是这个形态——渲染成一片没有任何说明的空白图表。
 * 用户看到的是"页面坏了"，而事实是"成本确实被记成了 0"。</p>
 */
describe('CostChart 柱图全 0 时的空态与反向对照', () => {
  /** 渲染成本视图（独立 QueryClient，避免与概览的缓存串味）。 */
  function renderCostChart(): ReturnType<typeof render> {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    return render(
      <QueryClientProvider client={client}>
        <CostChart from="2026-11-10" to="2026-11-16" />
      </QueryClientProvider>,
    );
  }

  const ONE_POINT = {
    key: 'T20261116-000001',
    taskNo: 'T20261116-000001',
    promptTokens: '60',
    completionTokens: '40',
    costMicro: '0',
    callCount: '1',
  };

  beforeEach(() => {
    vi.mocked(fetchCost).mockReset();
  });

  it('有数据点但 costMicro 全为 0 → 显示「成因见上方总成本卡片」，且整块柱图不挂载', async () => {
    vi.mocked(fetchCost).mockResolvedValue(
      buildCost({
        points: [ONE_POINT],
        totalCostMicro: '0',
        // 关键：有 token、没成本 → costZero 分支，文案要指向总成本卡片里的成因
        totalPromptTokens: '60',
        totalCompletionTokens: '40',
        costZeroReasons: ['MOCK_PRICE_ZERO'],
      }),
    );

    const { container, queryByTestId } = renderCostChart();

    await waitFor(() => {
      expect(container.textContent).toContain('调用记录存在但成本记为 0，成因见上方总成本卡片');
    });
    // 空态时 ChartCard 不渲染 children，柱图组件根本不会挂载
    expect(queryByTestId('cost-bar-chart')).toBeNull();
  });

  /**
   * 反向对照。**没有这一条，上面那条在「empty 恒真」的实现下也会绿**——
   * 一个永远显示空态的图表同样会让文案出现、让容器消失，测试看不出来。
   */
  it('反向对照：同一个点有成本 → 渲染柱图，且不出现「成因见上方总成本卡片」', async () => {
    vi.mocked(fetchCost).mockResolvedValue(
      buildCost({
        points: [{ ...ONE_POINT, costMicro: '5000000' }],
        totalCostMicro: '5000000',
        totalPromptTokens: '60',
        totalCompletionTokens: '40',
        unitCostMicro: '5000000',
      }),
    );

    const { container, queryByTestId } = renderCostChart();

    await waitFor(() => {
      expect(queryByTestId('cost-bar-chart')).not.toBeNull();
    });
    expect(container.textContent).not.toContain('调用记录存在但成本记为 0');
    // 总成本卡片走的是数字分支而不是「成本 0（原因：…）」
    expect(container.textContent).not.toContain('未知原因');
  });
});
