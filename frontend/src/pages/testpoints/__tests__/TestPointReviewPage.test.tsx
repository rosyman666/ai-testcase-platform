/**
 * 测试点评审页的错误态优先级回归（T12-3）。
 *
 * 与 `pages/cases/__tests__/CaseBoard.test.tsx` 同一类，锁的是 if 链顺序：
 * `isLoading → isError → rows.length === 0 → 表格`。这里的顺序错误比看板更贵——
 * 空态文案写的是「还没有测试点，点右上角『开始抽取』」，而请求失败时 `rows` 也是
 * 空数组，顺序反了就是**在抽取刚失败之后，再引导用户去点一次「开始抽取」**，
 * 界面上一次错误都不会出现。
 *
 * 两例成对存在：一例挡顺序（错误态不得被空态吞掉），一例防「把 error 分支写成
 * 恒真」而让列表永远出不来。
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import theme from '@/theme';
import type { PageResult } from '@/types/common';
import type { TestPointVO } from '@/types/testpoint';

// 组件 import 了五个 API，只 mock 其中一个会报「未导出」
vi.mock('@/api/testpoint', () => ({
  listTestPoints: vi.fn(),
  extractTestPoints: vi.fn(),
  updateTestPoint: vi.fn(),
  createTestPoint: vi.fn(),
  batchTestPointStatus: vi.fn(),
}));

import { listTestPoints } from '@/api/testpoint';
import TestPointReviewPage from '@/pages/testpoints/TestPointReviewPage';

/**
 * 造一页空结果。
 *
 * @returns PageResult<TestPointVO>
 */
function emptyPage(): PageResult<TestPointVO> {
  return { records: [], total: 0, page: 1, size: 20, totalPages: 0, hasNext: false };
}

/**
 * 渲染测试点评审页。
 *
 * 必须走 `<Routes>` 给 `useParams` 提供 `docId`：直接 render 到 `MemoryRouter`
 * 里拿不到路由参数，`listTestPoints` 会因为 `enabled: false` 而永不发起请求，
 * 那样测出来的「空态」根本不是本文件要测的那条路径。
 *
 * @returns RTL 的 render 结果
 */
function renderPage(): ReturnType<typeof render> {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: 0, refetchOnWindowFocus: false } },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={theme}>
        <MemoryRouter initialEntries={['/testpoints/1001']}>
          <Routes>
            <Route path="/testpoints/:docId" element={<TestPointReviewPage />} />
          </Routes>
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('pages/testpoints/TestPointReviewPage — 错误态优先级', () => {
  beforeEach(() => {
    vi.mocked(listTestPoints).mockReset();
  });

  afterEach(() => {
    cleanup();
  });

  it('请求失败时显示「加载失败」，且不得被空态吞掉', async () => {
    vi.mocked(listTestPoints).mockRejectedValue(new Error('network down'));

    renderPage();

    expect(await screen.findByText(/测试点列表加载失败/)).toBeTruthy();
    // 关键断言：它红了说明 error 分支被挪到了 rows.length === 0 之后
    expect(screen.queryByText(/还没有测试点/)).toBeNull();
  });

  it('请求成功但无数据时显示空态，而不是错误态', async () => {
    vi.mocked(listTestPoints).mockResolvedValue(emptyPage());

    renderPage();

    expect(await screen.findByText(/还没有测试点/)).toBeTruthy();
    expect(screen.queryByText(/测试点列表加载失败/)).toBeNull();
  });
});
