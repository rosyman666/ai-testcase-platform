/**
 * 执行结果批次详情页的回归（T10-2 + 归因纠正 UI 骨架）。
 *
 * 锁三件事：
 * 1. **错误态优先级**：`isLoading → isError → 无数据 → 内容`。请求失败时
 *    `detailQuery.data` 是 `undefined`，若先判 `!detail` 就会把「加载失败」
 *    显示成「没有找到这个批次」——用户会以为批次被删了。
 * 2. **分类下拉是「本批分布 ∪ 字典」的合成**（团队口径 a）：只取字典会漏掉
 *    "已停用但本批有记录"的分类，只取分布会丢掉"本批没出现但可选"的分类。
 * 3. **归因纠正入口的可见性**：只有 `FAILED` 且 `triageSource !== 'MANUAL'` 的行
 *    才有铅笔图标；`MANUAL` 行显示「已人工」Chip。这一批**没有 mutation**，
 *    弹窗里的「确定」必须不可点（端点 T10-3 才交付），否则用户点了没有任何反馈。
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import type { ReactElement } from 'react';
import theme from '@/theme';
import type { CodeLabel, PageResult } from '@/types/common';
import type {
  ExecutionCategoryCountVO,
  ExecutionResultVO,
  ExecutionRunDetailVO,
} from '@/types/execution';
import { buildCategoryOptions } from '@/pages/execution/ExecutionDetail';
import { ApiError } from '@/api/http';

// 页面（含导入弹窗之外的图表壳）一共用到 8 个 execution API，少 mock 会报「未导出」
vi.mock('@/api/execution', () => ({
  listExecutions: vi.fn(),
  getExecution: vi.fn(),
  listExecutionResults: vi.fn(),
  importExecution: vi.fn(),
  fetchExecutionSourceTypes: vi.fn(),
  fetchFailureCategories: vi.fn(),
  triageResult: vi.fn(),
  remapExecution: vi.fn(),
}));

/**
 * toast 打桩。
 *
 * `utils/toast.ts` 是"注册表 + console 降级"的两段式：页面没挂 `ToastBridge` 时
 * 走 `console.error`。这里打桩是为了**断言文案本身**，而不是断言它被打印了——
 * 「重跑完成：映射 8 / 8 条」这句话里，数字对不对是用户判断"到底映射上没"的唯一信号。
 */
vi.mock('@/utils/toast', () => ({ toast: vi.fn(), registerToastHandler: vi.fn() }));

import {
  fetchExecutionSourceTypes,
  fetchFailureCategories,
  getExecution,
  listExecutionResults,
  remapExecution,
  triageResult,
} from '@/api/execution';
import ExecutionDetail from '@/pages/execution/ExecutionDetail';
import { toast } from '@/utils/toast';

const toastMock = vi.mocked(toast);

/** 造一份批次详情。 */
function buildDetail(overrides: Partial<ExecutionRunDetailVO> = {}): ExecutionRunDetailVO {
  return {
    id: '9001',
    runNo: 'R20261116-000001',
    name: '登录模块回归',
    taskId: '7001',
    taskNo: 'T20261116-000007',
    sourceType: 'JUNIT_XML',
    fileName: 'login-report.xml',
    totalCount: 3,
    passedCount: 1,
    failedCount: 2,
    skippedCount: 0,
    mappedCount: 3,
    mappedRate: 1,
    durationMs: '5297',
    createdAt: '2026-11-16T10:00:00Z',
    statusCounts: [
      { status: 'PASSED', label: '通过', count: 1 },
      { status: 'FAILED', label: '失败', count: 2 },
      { status: 'SKIPPED', label: '已跳过', count: 0 },
    ],
    // 空分布 → ChartCard 走空态，不渲染 recharts（jsdom 里没必要去碰图表库）
    categoryDistribution: [],
    ...overrides,
  };
}

/** 造一条明细。 */
function buildResult(overrides: Partial<ExecutionResultVO> = {}): ExecutionResultVO {
  return {
    id: '1',
    runId: '9001',
    className: 'com.demo.LoginTest',
    caseName: 'shouldLogin',
    status: 'FAILED',
    durationMs: '123',
    failureMessage: 'expected true but was false',
    stackTrace: 'at com.demo.LoginTest.shouldLogin(LoginTest.java:42)',
    caseId: '501',
    failureCategory: 'ASSERTION',
    failureLabel: '断言失败',
    triageSource: 'RULE',
    createdAt: '2026-11-16T10:00:00Z',
    ...overrides,
  };
}

/** 造一页明细。 */
function resultPage(records: ExecutionResultVO[]): PageResult<ExecutionResultVO> {
  return { records, total: records.length, page: 1, size: 20, totalPages: 1, hasNext: false };
}

/**
 * 渲染详情页（必须走 `<Routes>` 才能拿到 `useParams().id`）。
 *
 * `retry: 0`：默认重试三次会让拒绝态用例多等两轮退避，CI 上表现为随机超时。
 *
 * @returns RTL 的 render 结果
 */
function renderPage(): ReturnType<typeof render> {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: 0, refetchOnWindowFocus: false } },
  });

  const ui = (): ReactElement => (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={theme}>
        <MemoryRouter initialEntries={['/executions/9001']}>
          <Routes>
            <Route path="/executions/:id" element={<ExecutionDetail />} />
          </Routes>
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>
  );

  return render(ui());
}

describe('pages/execution/ExecutionDetail — 错误态优先级', () => {
  beforeEach(() => {
    vi.mocked(getExecution).mockReset();
    vi.mocked(listExecutionResults).mockReset().mockResolvedValue(resultPage([]));
    vi.mocked(fetchExecutionSourceTypes)
      .mockReset()
      .mockResolvedValue([{ code: 'JUNIT_XML', label: 'JUnit XML', sortNo: 1 }]);
    vi.mocked(fetchFailureCategories).mockReset().mockResolvedValue([]);
  });

  afterEach(() => {
    cleanup();
  });

  it('批次详情请求失败时显示「批次详情加载失败」，不得被「没有找到这个批次」吞掉', async () => {
    vi.mocked(getExecution).mockRejectedValue(new Error('boom'));

    renderPage();

    expect(await screen.findByText('批次详情加载失败')).toBeTruthy();
    // 关键断言：它红了说明 `!detail` 分支被挪到了 isError 之前
    expect(screen.queryByText('没有找到这个批次')).toBeNull();
  });

  it('40401（HTTP 404）时展示后端 message，而不是前端自己猜的原因', async () => {
    // 实测：GET /api/executions/999 → HTTP 404 + body {code:40401,message:"执行批次不存在或已被删除"}
    // http.ts 的 error 分支会从 error.response.data 里取 message 并包成 ApiError
    vi.mocked(getExecution).mockRejectedValue(
      new ApiError(40401, '执行批次不存在或已被删除', 'trace-404'),
    );

    renderPage();

    expect(await screen.findByText('批次详情加载失败')).toBeTruthy();
    expect(screen.getByText('执行批次不存在或已被删除')).toBeTruthy();
  });

  it('详情与明细都成功时渲染批次信息、任务链接与明细行', async () => {
    vi.mocked(getExecution).mockResolvedValue(buildDetail());
    vi.mocked(listExecutionResults).mockResolvedValue(resultPage([buildResult()]));

    renderPage();

    expect(await screen.findByText('登录模块回归')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'T20261116-000007' })).toBeTruthy();
    expect(await screen.findByText('com.demo.LoginTest')).toBeTruthy();
    expect(screen.getByText('断言失败')).toBeTruthy();
  });
});

describe('pages/execution/ExecutionDetail — 归因纠正入口', () => {
  beforeEach(() => {
    vi.mocked(getExecution).mockReset().mockResolvedValue(buildDetail());
    vi.mocked(fetchExecutionSourceTypes).mockReset().mockResolvedValue([]);
    vi.mocked(fetchFailureCategories).mockReset().mockResolvedValue([
      { code: 'ASSERTION', label: '断言失败', sortNo: 1 },
      { code: 'ENV', label: '环境问题', sortNo: 2 },
      { code: 'UNKNOWN', label: '未知', sortNo: 3 },
    ]);
    vi.mocked(triageResult).mockReset();
    vi.mocked(remapExecution).mockReset();
    toastMock.mockClear();
    // 必须 reset：调用次数是本 describe 里几条用例的核心断言，留了上一条的历史就会串味
    vi.mocked(listExecutionResults).mockReset();
  });

  afterEach(() => {
    cleanup();
  });

  it('仅 FAILED 且未人工纠正的行有铅笔按钮；MANUAL 行显示「已人工」', async () => {
    vi.mocked(listExecutionResults).mockResolvedValue(
      resultPage([
        buildResult({ id: '1', caseName: 'failedByRule', triageSource: 'RULE' }),
        buildResult({ id: '2', caseName: 'failedByHuman', triageSource: 'MANUAL' }),
        buildResult({ id: '3', caseName: 'passedCase', status: 'PASSED', failureCategory: null, failureLabel: null }),
      ]),
    );

    renderPage();

    expect(await screen.findByText('failedByRule')).toBeTruthy();
    // RULE 行可纠正
    expect(screen.getByRole('button', { name: '纠正 failedByRule 的失败分类' })).toBeTruthy();
    // MANUAL 行不再给入口（避免反复覆盖自己的判断），并显式打标
    expect(screen.queryByRole('button', { name: '纠正 failedByHuman 的失败分类' })).toBeNull();
    expect(screen.getByText('已人工')).toBeTruthy();
    // 通过的行没有失败归因可改
    expect(screen.queryByRole('button', { name: '纠正 passedCase 的失败分类' })).toBeNull();
  });

  it('点击铅笔打开纠正弹窗，下拉排除 UNKNOWN', async () => {
    vi.mocked(listExecutionResults).mockResolvedValue(resultPage([buildResult()]));

    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: '纠正 shouldLogin 的失败分类' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('纠正失败归因')).toBeTruthy();
    // 说明文案：人工纠正后来源变 MANUAL
    expect(within(dialog).getByText(/归因来源会变为/)).toBeTruthy();
    // 预选了当前分类，所以「确定」可点（点下去才发请求）
    expect((within(dialog).getByRole('button', { name: '确定' }) as HTMLButtonElement).disabled).toBe(
      false,
    );

    // 打开下拉，确认 UNKNOWN 不在选项里（选它等于没归因）
    fireEvent.mouseDown(within(dialog).getByLabelText('失败分类'));
    expect(await screen.findByRole('option', { name: '环境问题' })).toBeTruthy();
    expect(screen.queryByRole('option', { name: /未知/ })).toBeNull();
  });

  it('纠正成功后该行分类文案就地更新，并出现「已人工」', async () => {
    vi.mocked(listExecutionResults).mockResolvedValue(resultPage([buildResult()]));
    vi.mocked(triageResult).mockResolvedValue(
      buildResult({
        failureCategory: 'ENV',
        failureLabel: '环境问题',
        triageSource: 'MANUAL',
      }),
    );

    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: '纠正 shouldLogin 的失败分类' }));
    const dialog = await screen.findByRole('dialog');

    // 改成「环境问题」（当前预选是「断言失败」）
    fireEvent.mouseDown(within(dialog).getByLabelText('失败分类'));
    fireEvent.click(await screen.findByRole('option', { name: '环境问题' }));
    fireEvent.click(within(dialog).getByRole('button', { name: '确定' }));

    // 只提交分类码；id 是明细行 id，不是批次 id
    await waitFor(() =>
      expect(vi.mocked(triageResult)).toHaveBeenCalledWith('1', { failureCategory: 'ENV' }),
    );

    // 就地替换：表格里那一行从「断言失败」变「环境问题」，且出现「已人工」Chip
    expect(await screen.findByText('已人工')).toBeTruthy();
    expect(screen.getByText('环境问题')).toBeTruthy();
    // 弹窗关闭（不留在页面上让用户以为没提交成功）
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    // 纠正后铅笔消失（MANUAL 行不再给入口）
    expect(screen.queryByRole('button', { name: '纠正 shouldLogin 的失败分类' })).toBeNull();
    // 反向断言：筛选是「全部」，改完仍符合筛选 → 明细**不得**被重新拉取（否则滚动位置白丢）
    expect(vi.mocked(listExecutionResults)).toHaveBeenCalledTimes(1);
  });

  it('筛选=断言失败时把该行改成环境问题 → 明细被重新拉取（否则列表会留下不符合筛选的行）', async () => {
    vi.mocked(listExecutionResults).mockResolvedValue(resultPage([buildResult()]));
    vi.mocked(triageResult).mockResolvedValue(
      buildResult({
        failureCategory: 'ENV',
        failureLabel: '环境问题',
        triageSource: 'MANUAL',
      }),
    );

    renderPage();

    // 先把筛选切到「断言失败」（分布为空 → 下拉项来自字典，label 不带次数）
    fireEvent.mouseDown(await screen.findByLabelText('失败分类'));
    fireEvent.click(await screen.findByRole('option', { name: '断言失败' }));

    // 等筛选生效带来的那次重拉完成，再记录基线
    await waitFor(() => expect(vi.mocked(listExecutionResults).mock.calls.length).toBeGreaterThan(1));
    const callsBeforeTriage = vi.mocked(listExecutionResults).mock.calls.length;

    fireEvent.click(await screen.findByRole('button', { name: '纠正 shouldLogin 的失败分类' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.mouseDown(within(dialog).getByLabelText('失败分类'));
    fireEvent.click(await screen.findByRole('option', { name: '环境问题' }));
    fireEvent.click(within(dialog).getByRole('button', { name: '确定' }));

    // 新分类（ENV）≠ 当前筛选（ASSERTION）→ 必须重拉，把不符合筛选的行从列表里清掉
    await waitFor(() =>
      expect(vi.mocked(listExecutionResults).mock.calls.length).toBeGreaterThan(callsBeforeTriage),
    );
    // 重拉时带的是旧的筛选条件（前端只负责让它重新查，不是就地改筛选）
    const lastCall = vi.mocked(listExecutionResults).mock.calls.at(-1);
    expect(lastCall?.[1]).toMatchObject({ category: 'ASSERTION' });
  });

  it('未关联任务（taskId=null）时「重跑映射」禁用，并用 tooltip 说明原因', async () => {
    vi.mocked(getExecution).mockResolvedValue(buildDetail({ taskId: null, taskNo: null }));
    vi.mocked(listExecutionResults).mockResolvedValue(resultPage([buildResult()]));

    renderPage();

    const remapButton = (await screen.findByRole('button', { name: '重跑映射' })) as HTMLButtonElement;
    expect(remapButton.disabled).toBe(true);

    // disabled 的按钮本身不收鼠标事件，tooltip 挂在外层 span 上（真实交互也是悬停 span）
    fireEvent.mouseOver(remapButton.parentElement as HTMLElement);
    const tooltip = await screen.findByRole('tooltip');
    expect(tooltip.textContent).toContain('该批次未关联任务，无法重跑映射');
  });

  it('重跑映射前弹确认，说明"只更新映射关系、不动已有归因"，确认后才发请求', async () => {
    vi.mocked(getExecution).mockResolvedValue(buildDetail());
    vi.mocked(listExecutionResults).mockResolvedValue(resultPage([buildResult()]));
    vi.mocked(remapExecution).mockResolvedValue({ mappedCount: 2, totalCount: 3, mappedRate: 0.6667 });

    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: '重跑映射' }));

    const dialog = await screen.findByRole('dialog');
    // 这句是"用户敢不敢点"的关键：不写清楚会以为要重算归因
    expect(
      within(dialog).getByText(/只更新映射关系，不会改变已有的失败分类与人工纠正/),
    ).toBeTruthy();
    // 点开确认框还没发请求
    expect(vi.mocked(remapExecution)).not.toHaveBeenCalled();

    fireEvent.click(within(dialog).getByRole('button', { name: '确认重跑' }));

    await waitFor(() => expect(vi.mocked(remapExecution)).toHaveBeenCalledWith('9001'));
    // 成功后关闭确认框
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('重跑映射成功后 toast 报出映射条数，详情与明细都被重新拉取', async () => {
    vi.mocked(getExecution).mockResolvedValue(buildDetail());
    vi.mocked(listExecutionResults).mockResolvedValue(resultPage([buildResult()]));
    // 实测形态（后端 T10-3 真库）：`mappedCount` / `totalCount` 是 `int`/`Integer`，
    // 没被全局 `ToStringSerializer` 命中，所以 JSON 里是 number；`mappedRate` 是
    // BigDecimal → number（固定 4 位小数）。
    // 这里**故意不断言**"它是 number"：字符串插值对 "8" 和 8 同形，那条断言对回归
    // 零判别力，写上去反而会让后来者以为这块有保护。
    vi.mocked(remapExecution).mockResolvedValue({ mappedCount: 8, totalCount: 8, mappedRate: 1 });

    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: '重跑映射' }));
    fireEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: '确认重跑' }),
    );

    // toast 是用户判断"到底映射上没"的唯一信号，数字错了他也看不出来
    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith(expect.stringContaining('映射 8 / 8'), 'success'),
    );

    // remap 是同步执行：返回时映射已写完，所以映射率卡片（详情）与「映射到的用例」列
    // （明细）都必须重拉，否则页面会继续显示旧映射率——那比报错更糟，因为它看起来是对的
    await waitFor(() => {
      expect(vi.mocked(getExecution).mock.calls.length).toBeGreaterThan(1);
      expect(vi.mocked(listExecutionResults).mock.calls.length).toBeGreaterThan(1);
    });

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('PASSED 行打开弹窗时不出现「归因」，也不渲染 null / undefined / NaN 字面量', async () => {
    vi.mocked(listExecutionResults).mockResolvedValue(
      resultPage([
        buildResult({
          id: '3',
          caseName: 'passedCase',
          status: 'PASSED',
          failureCategory: null,
          failureLabel: null,
          failureMessage: null,
          stackTrace: null,
          caseId: null,
          // 后端实测：通过行也返回 `'RULE'`（`triage_source NOT NULL DEFAULT 'RULE'` +
          // 批量插入显式带列）。这就是"必须按 status 判、不能按 triageSource 判空"的原因：
          // 用 null 当夹具的话，测试会绿，但绿的理由是假的。
          triageSource: 'RULE',
        }),
      ]),
    );

    renderPage();

    const row = (await screen.findByText('passedCase')).closest('tr') as HTMLElement;
    // 通过的行 DB 里同样是 `triage_source = 'RULE'`（DEFAULT + 批量插入显式带列），
    // 但「归因来源」列必须显示「—」而不是「规则」——显示规则等于替一次没发生过的判定作证。
    // 这条断言比"按 triageSource 判空"强：它锁的是"必须按 status 判"。
    expect(within(row).queryByText('规则')).toBeNull();
    // 「归因来源」是第 8 列（类名/方法名/状态/耗时/失败信息/映射到的用例/失败分类/归因来源）
    const sourceCell = row.querySelectorAll('td')[7];
    expect(sourceCell.textContent).toBe('—');

    fireEvent.click(within(row).getByText('passedCase'));

    const dialog = await screen.findByRole('dialog');
    // 通过的行不存在"归因"这个概念；渲染成「归因：—（—）」等于把"没有这个概念"
    // 说成"值缺失"，用户会以为归因失败了
    expect(within(dialog).queryByText(/归因/)).toBeNull();
    // null 直接插进 JSX 会渲染成字面量 "null"，是这类改动最典型的漏网形态
    expect(dialog.textContent ?? '').not.toMatch(/null|undefined|NaN/);
  });

  /**
   * 与上一例**成对**：同一个"通过行"的两种夹具，各锁一半。
   *
   * - 上一例用 `triageSource: 'RULE'`（今天 DB 的真实值）→ 锁「必须按 status 判」：
   *   若有人把 status 闸门换成 `!triageSource`，那例会红；
   * - 本例用 `triageSource: null`（后端 VO 归一后的真实返回）→ 锁「null 不许被兜成 'RULE'」：
   *   若有人写 `TRIAGE_SOURCE_LABEL[row.triageSource ?? 'RULE']` 这种"看起来等价"的兜底，
   *   或者把 null 直接渲染成字面量 `"null"`，上一例查不出来（它压根没有 null 的数据），
   *   本例会红。
   *
   * 只留一个夹具，就有一类改动能溜过去；这正是"夹具用哪种值决定测试在验证行为还是在验证想象"。
   */
  it('PASSED 行的 triageSource 为 null（归一后的真实形态）时同样渲染「—」', async () => {
    vi.mocked(listExecutionResults).mockResolvedValue(
      resultPage([
        buildResult({
          id: '4',
          caseName: 'passedNullSource',
          status: 'PASSED',
          failureCategory: null,
          failureLabel: null,
          failureMessage: null,
          stackTrace: null,
          caseId: null,
          triageSource: null,
        }),
      ]),
    );

    renderPage();

    const row = (await screen.findByText('passedNullSource')).closest('tr') as HTMLElement;
    // 「归因来源」第 8 列必须是破折号，且不许出现「规则」（那是把 null 兜成 RULE 的症状）
    const sourceCell = row.querySelectorAll('td')[7];
    expect(sourceCell.textContent).toBe('—');
    expect(within(row).queryByText(/规则/)).toBeNull();
    // 也可能被渲染成字面量 "null"（JSX 里 `{maybeNull}` 的老坑），一并钉死
    expect(row.textContent ?? '').not.toMatch(/null|undefined/);
  });
});

describe('pages/execution/ExecutionDetail — buildCategoryOptions', () => {
  const DICT: CodeLabel[] = [
    { code: 'ASSERTION', label: '断言失败', sortNo: 1 },
    { code: 'ENV', label: '环境问题', sortNo: 2 },
    { code: 'UNKNOWN', label: '未知', sortNo: 3 },
  ];

  it('合成 = 本批分布（按 count 降序）+ 字典独有项（按 sortNo 在后）', () => {
    const distribution: ExecutionCategoryCountVO[] = [
      { category: 'ENV', label: '环境问题', count: 2 },
      // 已停用分类：不在 enabledOnly 字典里，但本批确实有记录
      { category: 'DEPRECATED_FLAKY', label: '历史抖动（已停用）', count: 9 },
    ];

    const options = buildCategoryOptions(DICT, distribution);

    expect(options.map((item) => item.code)).toEqual([
      'DEPRECATED_FLAKY',
      'ENV',
      'ASSERTION',
      'UNKNOWN',
    ]);
    // 分布项带次数，字典独有项为 null
    expect(options[0]).toEqual({
      code: 'DEPRECATED_FLAKY',
      label: '历史抖动（已停用）',
      count: 9,
    });
    expect(options[2]).toEqual({ code: 'ASSERTION', label: '断言失败', count: null });
  });

  it('同码不重复：以分布项为准（label/count 来自本批聚合）', () => {
    const options = buildCategoryOptions(DICT, [
      { category: 'ASSERTION', label: '断言失败（本批文案）', count: 4 },
    ]);

    const assertions = options.filter((item) => item.code === 'ASSERTION');
    expect(assertions).toHaveLength(1);
    expect(assertions[0].count).toBe(4);
    expect(assertions[0].label).toBe('断言失败（本批文案）');
  });

  it('字典与分布都为空时返回空数组（下拉只剩「全部」，不报错）', () => {
    expect(buildCategoryOptions([], [])).toEqual([]);
  });
});
