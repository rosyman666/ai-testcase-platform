/**
 * 用例看板的错误态优先级回归（T12-3）。
 *
 * 本文件锁的是一条**顺序**，不是一条文案：
 * `isLoading → isError → length === 0 → 列表` 这个 if 链里，`isError` 必须排在
 * `length === 0` 之前。原因是请求失败时 `data?.records ?? []` 同样得到空数组，
 * 顺序反了就会把「加载失败」显示成「暂无用例」，还配一句「先运行用例生成任务」
 * —— 用户会去做一件刚失败过的事，而真正的错误一次都不会出现在界面上。
 *
 * 所以下面两例必须成对存在：
 * 1. 拒绝态 → 出现「加载失败」，且**不得**出现「暂无用例」（这一条挡顺序）
 * 2. 成功空态 → 出现「暂无用例」（这一条防"把 error 分支写成恒真"蒙混过关）
 * 只写第 1 例的话，把整个列表分支无条件替换成错误态也能通过。
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import theme from '@/theme';
import type { PageResult } from '@/types/common';
import type { TestCaseVO } from '@/types/cases';

// 组件同时 import 了三个 API，只 mock 一个会报「未导出」
vi.mock('@/api/cases', () => ({
  listCases: vi.fn(),
  reviewCase: vi.fn(),
  batchReviewCases: vi.fn(),
}));

import { listCases } from '@/api/cases';
import CaseBoard from '@/pages/cases/CaseBoard';

/**
 * 造一页空结果。
 *
 * @returns PageResult<TestCaseVO>
 */
function emptyPage(): PageResult<TestCaseVO> {
  return { records: [], total: 0, page: 1, size: 10, totalPages: 0, hasNext: false };
}

/**
 * 渲染用例看板。
 *
 * `retry: 0` 是必须的：默认重试三次会让拒绝态用例多等两轮退避，
 * 在 CI 上表现为随机超时而不是断言失败。
 *
 * @returns RTL 的 render 结果
 */
function renderBoard(): ReturnType<typeof render> {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: 0, refetchOnWindowFocus: false } },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={theme}>
        <MemoryRouter initialEntries={['/cases']}>
          <CaseBoard />
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('pages/cases/CaseBoard — 错误态优先级', () => {
  beforeEach(() => {
    vi.mocked(listCases).mockReset();
  });

  afterEach(() => {
    cleanup();
  });

  it('请求失败时显示「加载失败」，且不得被空态吞掉', async () => {
    vi.mocked(listCases).mockRejectedValue(new Error('network down'));

    renderBoard();

    expect(await screen.findByText('用例列表加载失败')).toBeTruthy();
    // 关键断言：错误态必须压住空态。它红了说明 isError 分支被挪到了 length===0 之后
    expect(screen.queryByText('暂无用例')).toBeNull();
  });

  it('请求成功但无数据时显示空态，而不是错误态', async () => {
    vi.mocked(listCases).mockResolvedValue(emptyPage());

    renderBoard();

    expect(await screen.findByText('暂无用例')).toBeTruthy();
    expect(screen.queryByText('用例列表加载失败')).toBeNull();
  });
});
