/**
 * 布局壳的 DOM 级回归（T12-1b W4）。
 *
 * 这批断言原本是 QA 在仓库外用浏览器/脚本跑的，落进仓库是为了防止以后改
 * `NAV_ITEMS` 或 `resolveModuleTitle` 时把"选中态"和"顶栏标题"悄悄改坏。
 *
 * jsdom 不做布局计算（`scrollHeight` 恒为 0），所以这里**不**断言尺寸，
 * 只断言"渲染出什么、选中哪个、标题显示什么"；兜底高度由 `sx.minHeight` 的分支
 * 保证，见 `routes/index.tsx` 的 PageBoundary。
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ThemeProvider } from '@mui/material/styles';
import type { ReactElement } from 'react';
import SideNav from '@/components/layout/SideNav';
import TopBar from '@/components/layout/TopBar';
import { useAuthStore } from '@/store/authStore';
import theme from '@/theme';

/** 侧栏应有的 7 个导航文案（顺序 = 侧栏展示顺序）。 */
const NAV_LABELS = [
  '概览',
  '需求文档',
  '测试点评审',
  '提示词模板',
  '生成任务',
  '用例评审',
  '指标看板',
];

/**
 * 打桩 `window.matchMedia`，让 `useMediaQuery` 走桌面分支。
 *
 * jsdom 没有 matchMedia 实现，不打桩时 MUI 会走 "noSsr=false + supportMatchMedia=false"
 * 的降级路径（永远 false），桌面形态的 permanent Drawer 就不会渲染进 DOM，测试全线变灰。
 *
 * @param matches 断点是否命中
 */
function mockMatchMedia(matches: boolean): void {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  );
}

/**
 * 带主题与路由上下文的渲染辅助。
 *
 * @param ui 待渲染组件
 * @param initialPath 初始路径（`useLocation()` 的值由它决定）
 * @returns RTL 的 render 结果
 */
function renderWithProviders(ui: ReactElement, initialPath: string): ReturnType<typeof render> {
  return render(
    <ThemeProvider theme={theme}>
      <MemoryRouter initialEntries={[initialPath]}>{ui}</MemoryRouter>
    </ThemeProvider>,
  );
}

/**
 * 取出侧栏里所有导航链接。
 *
 * @returns 导航链接数组，顺序与 DOM 顺序一致
 */
function getNavLinks(): HTMLElement[] {
  const nav = screen.getByRole('navigation', { name: '主导航' });
  return within(nav).getAllByRole('link');
}

/**
 * 取出当前带 `Mui-selected` 的导航文案。
 *
 * @returns 选中项文案数组（正常情况下长度恒为 1）
 */
function getSelectedLabels(): string[] {
  return getNavLinks()
    .filter((link) => link.classList.contains('Mui-selected'))
    .map((link) => (link.textContent ?? '').trim());
}

describe('components/layout/SideNav — 导航项与选中态', () => {
  beforeEach(() => {
    mockMatchMedia(true);
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('桌面断点渲染出 7 个导航项，文案与顺序稳定', () => {
    renderWithProviders(<SideNav open={false} onClose={() => undefined} />, '/');

    const labels = getNavLinks().map((link) => (link.textContent ?? '').trim());
    expect(labels).toEqual(NAV_LABELS);
  });

  it('详情页 /docs/123 只点亮「需求文档」，其余 5 项不亮', () => {
    renderWithProviders(<SideNav open={false} onClose={() => undefined} />, '/docs/123');

    expect(getSelectedLabels()).toEqual(['需求文档']);
  });

  it('根路径 / 只点亮「概览」', () => {
    renderWithProviders(<SideNav open={false} onClose={() => undefined} />, '/');

    expect(getSelectedLabels()).toEqual(['概览']);
    // 注：本条钉的是"根路径只有一项选中"这一行为。`exact` 标记本身的回归
    // 由 navMatch.test.ts 的「overview 标记为 exact」用例兜住——实测把 exact 去掉时，
    // 该用例失败而本条**仍然通过**（因为前缀规则是 `path + '/'`，`/` 不会命中 `/docs`），
    // 别误以为这里已经覆盖了 exact。
  });
});

describe('components/layout/TopBar — 账号兜底与标题', () => {
  beforeEach(() => {
    mockMatchMedia(true);
    // 默认给一份完整登录态，个别用例再覆盖成 null
    useAuthStore.setState({
      token: 'test-token',
      userId: '1',
      username: 'alice',
      displayName: '爱丽丝',
      expiresAt: '2026-01-01T00:00:00.000Z',
    });
  });

  afterEach(() => {
    cleanup();
    useAuthStore.setState({
      token: null,
      userId: null,
      username: null,
      displayName: null,
      expiresAt: null,
    });
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it('displayName 与 username 全为 null 时不崩，头像退化为 ?', () => {
    useAuthStore.setState({ displayName: null, username: null });

    renderWithProviders(<TopBar onOpenNav={() => undefined} />, '/');

    const avatarButton = screen.getByRole('button', { name: '账号菜单' });
    expect(avatarButton.textContent).toBe('?');
  });

  it('有显示名时取首字（中文昵称不应变成空）', () => {
    renderWithProviders(<TopBar onOpenNav={() => undefined} />, '/');

    expect(screen.getByRole('button', { name: '账号菜单' }).textContent).toBe('爱');
  });

  it('/testpoints/9 的标题是「测试点评审」，不是「控制台」', () => {
    renderWithProviders(<TopBar onOpenNav={() => undefined} />, '/testpoints/9');

    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('测试点评审');
  });
});
