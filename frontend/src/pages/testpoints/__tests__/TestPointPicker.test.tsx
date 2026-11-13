/**
 * 测试点评审落地页的三态与"进入评审"可用性回归（T12-2a）。
 *
 * <p>用 `vi.mock` 造 `listDocs` 的返回，不起 msw、不发真网络。断言用户能看到的文案与
 * 按钮可用性，不断言 DOM 结构，避免把测试焊死在样式上。</p>
 *
 * <p>注意：本仓库**没有**装 `@testing-library/jest-dom`，所以这里用
 * `(btn as HTMLButtonElement).disabled` 这种原生属性断言，不要写 `toBeDisabled()`。</p>
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import theme from '@/theme';

vi.mock('@/api/doc', () => ({
  listDocs: vi.fn(),
}));

import { listDocs } from '@/api/doc';
import TestPointPicker from '@/pages/testpoints/TestPointPicker';
import type { PageResult } from '@/types/common';
import type { DocSourceVO } from '@/types/doc';

/**
 * 造一条文档列表项。
 *
 * @param overrides 覆盖字段
 * @returns DocSourceVO
 */
function buildDoc(overrides: Partial<DocSourceVO> = {}): DocSourceVO {
  return {
    id: '1001',
    docNo: 'DOC-20260101-001',
    fileName: '登录需求.md',
    docType: 'MD',
    fileSize: 2048,
    parseStatus: 'PARSED',
    parseError: '',
    sectionCount: 5,
    operationCount: 3,
    createdAt: '2026-01-01T00:00:00.000Z',
    updatedAt: '2026-01-02T08:30:00.000Z',
    ...overrides,
  };
}

/**
 * 把记录包成后端分页出参。
 *
 * @param records 记录列表
 * @returns PageResult
 */
function buildPage(records: DocSourceVO[]): PageResult<DocSourceVO> {
  return {
    records,
    total: records.length,
    page: 1,
    size: 10,
    totalPages: 1,
    hasNext: false,
  };
}

/**
 * 渲染被测页面（主题 + 路由 + react-query 三件上下文）。
 *
 * @returns RTL 的 render 结果
 */
function renderPicker(): ReturnType<typeof render> {
  // retry: 0 —— 默认策略继承不到 main.tsx 的 QueryClient，这里显式关掉重试，
  // 否则 error 用例会等满重试间隔才渲染
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: 0, refetchOnWindowFocus: false } },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={theme}>
        <MemoryRouter initialEntries={['/testpoints']}>
          <TestPointPicker />
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('pages/testpoints/TestPointPicker — 三态与可用性', () => {
  beforeEach(() => {
    vi.mocked(listDocs).mockReset();
  });

  afterEach(() => {
    cleanup();
  });

  it('返回空列表时显示「还没有文档」引导', async () => {
    vi.mocked(listDocs).mockResolvedValue(buildPage([]));

    renderPicker();

    expect(await screen.findByText('还没有文档')).toBeTruthy();
    // 空态必须给出口，否则用户在这一页是死路
    expect(screen.getByRole('button', { name: '去上传' })).toBeTruthy();
  });

  it('已解析文档的「进入评审」按钮可点击', async () => {
    vi.mocked(listDocs).mockResolvedValue(buildPage([buildDoc({ parseStatus: 'PARSED' })]));

    renderPicker();

    const button = await screen.findByRole('button', { name: '进入评审' });
    expect((button as HTMLButtonElement).disabled).toBe(false);
    // 顺带钉住状态文案：PARSED 不能被渲染成裸英文 code
    expect(screen.getByText('已解析')).toBeTruthy();
  });

  it('待解析文档的「进入评审」按钮禁用', async () => {
    vi.mocked(listDocs).mockResolvedValue(buildPage([buildDoc({ parseStatus: 'PENDING' })]));

    renderPicker();

    const button = await screen.findByRole('button', { name: '进入评审' });
    expect((button as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('待解析')).toBeTruthy();
  });

  it('请求失败时显示错误态与重试入口', async () => {
    vi.mocked(listDocs).mockRejectedValue(new Error('network down'));

    renderPicker();

    await waitFor(() => {
      expect(screen.getByText('文档列表加载失败')).toBeTruthy();
    });
    expect(screen.getByRole('button', { name: '重试' })).toBeTruthy();
  });
});
