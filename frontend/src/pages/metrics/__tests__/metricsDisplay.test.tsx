/**
 * T11 前端 0 值分层 A/B/C/D 与范围一致性的展示回归（docs/07 §4.8 / §8 T04）。
 *
 * <p>用 `vi.mock` 造 API 数据，不起 msw、不发真网络。断言 `textContent` 而不是
 * 具体 DOM 结构，避免把测试焊死在样式上。</p>
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';

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

import { fetchOverview, fetchRejectReasons } from '@/api/metrics';
import OverviewDashboard, { shanghaiToday, shiftDays } from '@/pages/metrics/OverviewDashboard';
import type { OverviewVO } from '@/types/metrics';

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
});
