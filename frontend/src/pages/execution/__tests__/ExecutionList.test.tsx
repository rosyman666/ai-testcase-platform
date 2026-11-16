/**
 * 执行结果列表页的错误态优先级回归（T10-2）。
 *
 * 与 `pages/cases/__tests__/CaseBoard.test.tsx`、`TestPointReviewPage.test.tsx` 同一类：
 * 锁的是 if 链顺序 `isLoading → isError → rows.length === 0 → 表格`。
 * 请求失败时 `data?.records ?? []` 同样是空数组，顺序反了就会把「加载失败」
 * 显示成「还没有导入过执行结果」，还配一句"点右上角『导入结果』"——
 * 用户会去做一件刚失败过的事，而真正的错误一次都不会出现在界面上。
 *
 * 三例必须成对存在：
 * 1. 拒绝态 → 出现「加载失败」，且**不得**出现空态文案（挡顺序）
 * 2. 成功空态 → 出现空态文案，且不得出现错误态（防"把 error 分支写成恒真"）
 * 3. 有数据 → 渲染单号与映射率百分比（防"只测三态、表格其实渲染不出来"）
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import theme from '@/theme';
import type { CodeLabel, PageResult } from '@/types/common';
import type { ExecutionRunVO } from '@/types/execution';

// 组件（含它挂的导入弹窗）一共 import 了 6 个 execution API + 1 个 task API，
// 只 mock 其中一个会报「未导出」（见 TestPointReviewPage.test.tsx 的说明）
vi.mock('@/api/execution', () => ({
  listExecutions: vi.fn(),
  getExecution: vi.fn(),
  listExecutionResults: vi.fn(),
  importExecution: vi.fn(),
  fetchExecutionSourceTypes: vi.fn(),
  fetchFailureCategories: vi.fn(),
}));

// 导入弹窗里用了任务下拉；不 mock 会在 jsdom 里真的发 axios 请求
vi.mock('@/api/task', () => ({
  listTasks: vi.fn(),
}));

import { fetchExecutionSourceTypes, listExecutions } from '@/api/execution';
import { listTasks } from '@/api/task';
import ExecutionList from '@/pages/execution/ExecutionList';

/**
 * 造一页空结果。
 *
 * @returns PageResult<ExecutionRunVO>
 */
function emptyPage(): PageResult<ExecutionRunVO> {
  return { records: [], total: 0, page: 1, size: 20, totalPages: 0, hasNext: false };
}

/**
 * 造一条批次数据。
 *
 * `mappedRate` 刻意用 `0.85`（后端已算好的 0~1 小数），页面只做 ×100 →
 * 期望渲染出 `85.0%`；如果哪天有人改成 `mappedCount / totalCount` 重算，这条也仍然通过
 * （17/20 恰好也是 0.85），所以下面单独断言"前端不做除法"靠的是 `mappedRate` 与
 * 分子分母**不一致**的情况——见 `mappedRateNotFromCounts`。
 *
 * @param overrides 需要覆盖的字段
 * @returns ExecutionRunVO
 */
function buildRun(overrides: Partial<ExecutionRunVO> = {}): ExecutionRunVO {
  return {
    id: '9001',
    runNo: 'R20261116-000001',
    name: '登录模块回归',
    taskId: '7001',
    taskNo: 'T20261116-000007',
    sourceType: 'JUNIT_XML',
    fileName: 'login-report.xml',
    totalCount: 20,
    passedCount: 15,
    failedCount: 4,
    skippedCount: 1,
    mappedCount: 17,
    mappedRate: 0.85,
    durationMs: '1200',
    createdAt: '2026-11-16T10:00:00Z',
    ...overrides,
  };
}

/**
 * 渲染执行结果列表页。
 *
 * `retry: 0` 是必须的：默认重试三次会让拒绝态用例多等两轮退避，
 * 在 CI 上表现为随机超时而不是断言失败。
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
        <MemoryRouter initialEntries={['/executions']}>
          <ExecutionList />
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

/** 来源格式字典（真实请求会返回这两项）。 */
const SOURCE_TYPES: CodeLabel[] = [
  { code: 'JUNIT_XML', label: 'JUnit XML', sortNo: 1 },
  { code: 'CSV', label: 'CSV 结果表', sortNo: 2 },
];

describe('pages/execution/ExecutionList — 错误态优先级', () => {
  beforeEach(() => {
    vi.mocked(listExecutions).mockReset();
    vi.mocked(fetchExecutionSourceTypes).mockReset().mockResolvedValue(SOURCE_TYPES);
    vi.mocked(listTasks).mockReset().mockResolvedValue({
      records: [],
      total: 0,
      page: 1,
      size: 50,
      totalPages: 0,
      hasNext: false,
    });
  });

  afterEach(() => {
    cleanup();
  });

  it('请求失败时显示「加载失败」，且不得被空态吞掉', async () => {
    vi.mocked(listExecutions).mockRejectedValue(new Error('network down'));

    renderPage();

    expect(await screen.findByText('执行结果加载失败')).toBeTruthy();
    // 关键断言：错误态必须压住空态。它红了说明 isError 分支被挪到了 length===0 之后
    expect(screen.queryByText(/还没有导入过执行结果/)).toBeNull();
  });

  it('请求成功但无数据时显示空态，而不是错误态', async () => {
    vi.mocked(listExecutions).mockResolvedValue(emptyPage());

    renderPage();

    expect(await screen.findByText(/还没有导入过执行结果/)).toBeTruthy();
    expect(screen.queryByText('执行结果加载失败')).toBeNull();
  });

  it('有数据时渲染单号与映射率百分比', async () => {
    vi.mocked(listExecutions).mockResolvedValue({
      records: [buildRun()],
      total: 1,
      page: 1,
      size: 20,
      totalPages: 1,
      hasNext: false,
    });

    renderPage();

    // 单号：用户报障时报的是它，必须能看见
    expect(await screen.findByText('R20261116-000001')).toBeTruthy();
    expect(screen.getByText('登录模块回归')).toBeTruthy();
    // 映射率：0~1 小数 ×100 保留 1 位
    expect(screen.getByText('85.0%')).toBeTruthy();
    // 来源码走字典翻译，不能漏出 JUNIT_XML 裸码
    expect(screen.getByText('JUnit XML')).toBeTruthy();
    // 耗时人性化：1200ms → 1.2s
    expect(screen.getByText('1.2s')).toBeTruthy();
  });

  it('任务列显示任务单号并可跳任务详情', async () => {
    vi.mocked(listExecutions).mockResolvedValue({
      records: [buildRun()],
      total: 1,
      page: 1,
      size: 20,
      totalPages: 1,
      hasNext: false,
    });

    renderPage();

    // 用户排查"结果与用例对不上"第一问就是"这批属于哪个任务"，任务号必须可见且可点
    const taskLink = await screen.findByRole('link', { name: 'T20261116-000007' });
    expect(taskLink.getAttribute('href')).toBe('/tasks/7001');
  });

  it('任务为空（taskId/taskNo 为 null）时显示中性文案「未关联」而不是空白', async () => {
    vi.mocked(listExecutions).mockResolvedValue({
      records: [buildRun({ taskId: null, taskNo: null })],
      total: 1,
      page: 1,
      size: 20,
      totalPages: 1,
      hasNext: false,
    });

    renderPage();

    // 文案必须中性：`task_id` 是 V7 才加的列，null 既可能是"任务被删"，
    // 也可能是"历史批次当时没记录"——断言成「任务已删除」是在说一件无法证明的事
    expect(await screen.findByText('未关联')).toBeTruthy();
    expect(screen.queryByText('任务已删除')).toBeNull();
    // null ≠ 空串：留空会让用户以为"表格没渲染出来"
    expect(screen.queryByRole('link', { name: 'T20261116-000007' })).toBeNull();
  });

  it('映射率只用后端的 mappedRate，不用 mappedCount/totalCount 重算', async () => {
    // 20 条里只映射 19 条，但后端给的 mappedRate 是 0.95：
    // 若前端自己除，会得到 95.0% 恰好相同的巧合没有价值——所以这里让两者不等
    // （19/20 = 95%，而后端给 0.9），断言渲染的是后端那个值
    vi.mocked(listExecutions).mockResolvedValue({
      records: [buildRun({ totalCount: 20, mappedCount: 19, mappedRate: 0.9 })],
      total: 1,
      page: 1,
      size: 20,
      totalPages: 1,
      hasNext: false,
    });

    renderPage();

    expect(await screen.findByText('90.0%')).toBeTruthy();
    expect(screen.queryByText('95.0%')).toBeNull();
  });

  it('0 条结果的批次映射率显示「-」，不显示 0.0%', async () => {
    vi.mocked(listExecutions).mockResolvedValue({
      records: [
        buildRun({
          totalCount: 0,
          passedCount: 0,
          failedCount: 0,
          skippedCount: 0,
          mappedCount: 0,
          mappedRate: 0,
        }),
      ],
      total: 1,
      page: 1,
      size: 20,
      totalPages: 1,
      hasNext: false,
    });

    renderPage();

    expect(await screen.findByText('R20261116-000001')).toBeTruthy();
    // 0 条结果时映射率无定义，显示 0.0% 会被读成"一条都没映射上"
    expect(screen.getByText('-')).toBeTruthy();
    expect(screen.queryByText('0.0%')).toBeNull();
  });

  it('来源格式字典接口失败时，列表照常渲染（下拉退化为只剩「全部」）', async () => {
    vi.mocked(fetchExecutionSourceTypes).mockRejectedValue(new Error('dict down'));
    vi.mocked(listExecutions).mockResolvedValue({
      records: [buildRun()],
      total: 1,
      page: 1,
      size: 20,
      totalPages: 1,
      hasNext: false,
    });

    renderPage();

    // 列表数据本身必须还在（字典挂了不等于列表挂了）
    expect(await screen.findByText('R20261116-000001')).toBeTruthy();
    // 字典不可用时退回裸 code，排障时它是唯一线索
    expect(screen.getByText('JUNIT_XML')).toBeTruthy();
    // 整页不得变成错误态
    expect(screen.queryByText('执行结果加载失败')).toBeNull();
  });

  /**
   * 关键词框的文案是**能力声明**，不是装饰。
   *
   * 后端 `keyword` 现在同时模糊 `run_no` / `name` / `file_name` / `gen_task.task_no`
   * （`20261108-000100` 这种任务号实测能命中），所以文案必须把「任务号」写进去；
   * 反过来，别在这里留「（不含任务单号）」这类限制——能力已经有了还教用户一条假的限制，
   * 比不说更糟。这条用例锁的是"文案承诺 == 后端能力"。
   */
  it('关键词框写明可搜任务号（后端 keyword 已覆盖 task_no）', async () => {
    vi.mocked(listExecutions).mockResolvedValue({
      records: [buildRun()],
      total: 1,
      page: 1,
      size: 20,
      totalPages: 1,
      hasNext: false,
    });

    renderPage();

    await screen.findByText('R20261116-000001');
    expect(screen.getByPlaceholderText('任务号 / 批次号 / 批次名 / 文件名')).toBeTruthy();
    expect(screen.getByText('同时匹配任务号、批次号、批次名与原始文件名')).toBeTruthy();
    expect(screen.queryByText(/不含任务单号/)).toBeNull();
  });
});
