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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import type { ReactElement } from 'react';
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

/**
 * 可控的 SSE 流状态。
 *
 * 必须 mock：jsdom 没有 `EventSource`，真调 `useTaskStream` 会在打开连接时抛错，
 * 而那会把「页面自身的渲染缺陷」和「环境缺 EventSource」混成同一个红。
 */
const streamState = vi.hoisted(() => ({
  terminal: false,
  snapshot: null as null | {
    status: string;
    progress: number;
    done: number;
    total: number;
    success: number;
    failed: number;
    produced: number;
  },
}));

vi.mock('@/hooks/useTaskStream', () => ({
  useTaskStream: () => ({
    snapshot: streamState.snapshot,
    items: [],
    terminal: streamState.terminal,
    connection: 'open',
    errorFrame: null,
    reconnect: () => undefined,
  }),
}));

import { extractTestPoints, listTestPoints } from '@/api/testpoint';
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
 * 额外返回 `rerenderSame`：用**同一棵 element 树 + 同一个 QueryClient** 重渲染。
 * 不能自己再拼一个新树去 rerender——新 `QueryClient` 等于换缓存、组件会重新挂载，
 * 页面里「已提交的任务」状态直接丢掉，测出来的终态是假的。
 *
 * @returns RTL 的 render 结果 + 原树重渲染
 */
function renderPage(): ReturnType<typeof render> & { rerenderSame: () => void } {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: 0, refetchOnWindowFocus: false } },
  });

  const ui = (): ReactElement => (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={theme}>
        <MemoryRouter initialEntries={['/testpoints/1001']}>
          <Routes>
            <Route path="/testpoints/:docId" element={<TestPointReviewPage />} />
          </Routes>
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>
  );

  const view = render(ui());
  // 每次 rerender 都要造**新的 element 对象**：传同一个引用 React 会直接 bailout，
  // 页面根本不会重渲染，测出来的就还是旧状态
  return { ...view, rerenderSame: () => view.rerender(ui()) };
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

/**
 * 抽取提交路径：锁「前端按异步契约消费，而不是按 T05 的同步报告结构渲染」。
 *
 * 这是线上真实崩过的那个点：后端自 T06 起 `POST /api/testpoints/extract` 只返回
 * `{taskId, taskNo}`，前端类型却仍是旧的 `TestPointExtractReport`（含 `results[]`），
 * 页面在 `onSuccess` 里 `setReport(data)` 后立刻 `report.results.map(...)` ——
 * `results` 是 `undefined`，渲染期抛错，被 ErrorBoundary 兜成整页「页面渲染出错」。
 *
 * **为什么 tsc 没拦住**：`post<T>()` 里的 `T` 只是类型断言，HTTP 返回什么它不校验；
 * 而类型声明本身是手写的，写错也照样编译通过。这类缺陷只有「真的点一次」才暴露。
 */
describe('pages/testpoints/TestPointReviewPage — 抽取提交走异步契约', () => {
  beforeEach(() => {
    vi.mocked(listTestPoints).mockReset().mockResolvedValue(emptyPage());
    vi.mocked(extractTestPoints).mockReset();
    streamState.terminal = false;
    streamState.snapshot = null;
  });

  afterEach(() => {
    cleanup();
  });

  it('提交后进入「进行中」并显示任务号，不去读不存在的 results', async () => {
    vi.mocked(extractTestPoints).mockResolvedValue({ taskId: '501', taskNo: 'TP-20261116-001' });

    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '开始抽取' }));

    expect(await screen.findByText('抽取进行中')).toBeTruthy();
    // 任务号必须露出来：用户报障时报的是业务单号，不是内部 taskId
    expect(screen.getByText(/TP-20261116-001/)).toBeTruthy();
  });

  it('任务进入终态后展示结束态与汇总', async () => {
    vi.mocked(extractTestPoints).mockResolvedValue({ taskId: '501', taskNo: 'TP-20261116-001' });

    const { rerenderSame } = renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '开始抽取' }));
    expect(await screen.findByText('抽取进行中')).toBeTruthy();

    // 模拟 SSE 推到终态：同一棵树重渲染后应切到结束态
    streamState.terminal = true;
    streamState.snapshot = {
      status: 'SUCCESS',
      progress: 100,
      done: 17,
      total: 17,
      success: 17,
      failed: 0,
      produced: 23,
    };
    rerenderSame();

    expect(await screen.findByText('抽取结束')).toBeTruthy();
    expect(screen.getByText(/17\/17/)).toBeTruthy();
    expect(screen.getByText(/新增 23/)).toBeTruthy();
  });

  /**
   * 重复抽取的真实形态：`produced=0`。
   *
   * 抽取是幂等的（`content_hash = sha256(doc_id + title + category)`），同一篇文档
   * 再抽一遍，全部命中库里已有的测试点，`produced` 就是 0 —— **这不是失败**。
   * 但界面若只说「新增 0 条」，用户读到的意思是「白跑了」，接着就会怀疑
   * 后端没干活、或者自己点错了文档。
   */
  it('重复抽取 produced=0 时不说「新增 0 条」，而是点明幂等去重', async () => {
    vi.mocked(extractTestPoints).mockResolvedValue({ taskId: '502', taskNo: 'TP-20261116-002' });

    const { rerenderSame } = renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '开始抽取' }));
    expect(await screen.findByText('抽取进行中')).toBeTruthy();

    streamState.terminal = true;
    streamState.snapshot = {
      status: 'SUCCESS',
      progress: 100,
      done: 17,
      total: 17,
      success: 17,
      failed: 0,
      produced: 0,
    };
    rerenderSame();

    expect(await screen.findByText(/本轮没有新增/)).toBeTruthy();
    // 反向断言：那句什么都没解释的「抽取完成，列表已刷新」不得同时出现
    expect(screen.queryByText('抽取完成，列表已刷新。')).toBeNull();
  });
});
