/**
 * 文档详情页的状态标签回归（T12-2b）。
 *
 * 本文件存在的唯一目的：**证明 `DocDetail` 走的是 `@/constants/docStatus` 的共享映射，
 * 而不是自己那份本地拷贝表**。三例各有分工，缺一不可：
 *
 * 1. 正例 `PARSED → 已解析`：守"已知状态正常渲染"这条既有行为（重构后详情页仍能装配）。
 * 2. 负例 `SOMETHING_NEW → 裸 code`：守"后端加了枚举时详情页不能白屏 / 空白"。
 *    注意它**对"引用 vs 拷贝"零判别力**——两份实现的未知兜底逐字相同，本例不是用来防拷贝的。
 * 3. 哨兵 `__SHARED_FN__`：**唯一有判别力的那条**——把共享函数换掉，页面跟着变才说明真在调用它。
 *
 * 哨兵为什么用 `__SHARED_FN__` 而不是中文：详情页中文文案密集，中文哨兵有误绿风险；
 * 带下划线的英文串全仓仅此一处，不可能撞。
 *
 * 目录说明：`pages/docs/__tests__/` 此前不存在，本文件是该目录首例。
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import theme from '@/theme';

/**
 * 哨兵开关。
 *
 * 必须用 `vi.hoisted`：`vi.mock` 的工厂会被提升到 import 之前执行，
 * 若工厂里读普通模块级 `let`，那时变量还在 TDZ，直接 `ReferenceError`。
 */
const mockState = vi.hoisted(() => ({ sentinel: false }));

// 默认透传真实实现（所以正例/负例拿到的仍是真实映射），只有哨兵用例翻转 sentinel。
// 保留 `{ ...actual }`：将来 DocDetail 若引用 DOC_TYPE_LABEL 等其它导出，
// 整包替换会让它们变成 undefined，失败会被误诊成"测试脆弱"。
vi.mock('@/constants/docStatus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/constants/docStatus')>();
  return {
    ...actual,
    getParseStatusMeta: (code: string) =>
      mockState.sentinel
        ? { label: '__SHARED_FN__', color: 'default' as const }
        : actual.getParseStatusMeta(code),
  };
});

// 组件同时 import 了 getDoc 与 reparseDoc，只 mock 一个会报"未导出"。
vi.mock('@/api/doc', () => ({
  getDoc: vi.fn(),
  reparseDoc: vi.fn(),
}));

import { getDoc } from '@/api/doc';
import DocDetail from '@/pages/docs/DocDetail';
import type { DocDetailVO } from '@/types/doc';

/**
 * 造一份文档详情。
 *
 * 按 `@/types/doc.ts` 的 `DocDetailVO` **逐字段**填，不用 `as any`：
 * `as any` 会让将来新增字段时本测试静默失效（编译期不再校验）。
 *
 * @param overrides 覆盖字段
 * @returns DocDetailVO
 */
function buildDocDetail(overrides: Partial<DocDetailVO> = {}): DocDetailVO {
  return {
    id: '1001',
    docNo: 'DOC-20260101-001',
    fileName: '登录需求.md',
    docType: 'MD',
    fileSize: 2048,
    parseStatus: 'PARSED',
    parseError: '',
    sectionCount: 2,
    operationCount: 1,
    // 与 sectionCount / operationCount 相等 → 未截断，页面不会渲染"已显示 x / 共 N 条"
    totalSectionCount: 2,
    totalOperationCount: 1,
    createdAt: '2026-01-01T00:00:00.000Z',
    updatedAt: '2026-01-02T08:30:00.000Z',
    sections: [
      {
        id: '9001',
        heading: '1. 登录流程',
        level: 1,
        seqNo: 1,
        content: '用户输入账号密码后提交。',
        contentLength: 12,
      },
      {
        id: '9002',
        heading: '1.1 失败提示',
        level: 2,
        seqNo: 2,
        content: '账号或密码错误时给出统一提示。',
        contentLength: 15,
      },
    ],
    operations: [
      {
        id: '7001',
        method: 'POST',
        path: '/api/auth/login',
        summary: '登录',
        tags: 'auth',
        seqNo: 1,
        schemaTruncated: false,
      },
    ],
    ...overrides,
  };
}

/**
 * 渲染文档详情页。
 *
 * 必须经由 `<Routes>` 给 `useParams` 提供 `id`——直接把组件 render 到 `MemoryRouter` 里
 * 拿不到路由参数，页面会走"缺少文档 ID"分支，测试就测了个假东西。
 *
 * @returns RTL 的 render 结果
 */
function renderDetail(): ReturnType<typeof render> {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: 0, refetchOnWindowFocus: false } },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={theme}>
        <MemoryRouter initialEntries={['/docs/1001']}>
          <Routes>
            <Route path="/docs/:id" element={<DocDetail />} />
          </Routes>
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('pages/docs/DocDetail — 解析状态标签', () => {
  beforeEach(() => {
    mockState.sentinel = false;
    vi.mocked(getDoc).mockReset();
  });

  afterEach(() => {
    cleanup();
  });

  it('已知状态 PARSED 渲染中文「已解析」（守住既有行为）', async () => {
    vi.mocked(getDoc).mockResolvedValue(buildDocDetail({ parseStatus: 'PARSED' }));

    renderDetail();

    expect(await screen.findByText('已解析')).toBeTruthy();
  });

  it('未知状态渲染裸 code，不崩溃、不空白（守后端加枚举时的降级）', async () => {
    vi.mocked(getDoc).mockResolvedValue(buildDocDetail({ parseStatus: 'SOMETHING_NEW' }));

    renderDetail();

    // 未知 code 显示原始编码而不是"未知状态"：裸 code 是排障的唯一线索
    expect(await screen.findByText('SOMETHING_NEW')).toBeTruthy();
  });

  it('哨兵：证明状态标签来自共享函数，而不是本地拷贝表', async () => {
    // 翻转哨兵后，任何"自己算"的实现都渲染不出这个值
    mockState.sentinel = true;
    vi.mocked(getDoc).mockResolvedValue(buildDocDetail({ parseStatus: 'PARSED' }));

    renderDetail();

    expect(await screen.findByText('__SHARED_FN__')).toBeTruthy();
  });
});
