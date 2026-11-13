/**
 * `ErrorBoundary` 的单元回归（T12-3）。
 *
 * 这个组件的价值只有一个：**渲染期抛错时不要让整棵树被卸载**。所以三条断言
 * 分别对应三件事：
 * 1. 正常时不干预（边界写错最常见的方式是"永远显示兜底"，那样反而更糟）；
 * 2. 抛错时兜底出现、消息可见、并且**真的记了日志**（不接监控的项目里，
 *    `console.error` 是唯一能看到 componentStack 的地方，漏掉它等于静默）；
 * 3. 重试能把子树重新挂载起来（`setState({error:null})` 之后 React 会重新渲染
 *    children，但不会重建子组件的 state —— 这一点对"重试能不能救回来"是决定性的，
 *    所以用「第一次抛、第二次不抛」的开关来验）。
 *
 * React 会把错误同时打到 `console.error`，不打桩的话测试输出会被 stack 刷满，
 * 且容易掩盖真正的失败原因。
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import ErrorBoundary from '@/components/common/ErrorBoundary';
import theme from '@/theme';

/** 抛错开关：第一次渲染抛，之后不抛（用于验证重试）。 */
let shouldThrow = true;

/**
 * 受控的炸弹组件。
 *
 * @returns 正常内容
 */
function Bomb(): JSX.Element {
  if (shouldThrow) {
    throw new Error('boom-in-render');
  }
  return <div>恢复正常</div>;
}

describe('components/common/ErrorBoundary', () => {
  beforeEach(() => {
    shouldThrow = true;
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('子树正常渲染时不显示兜底', () => {
    shouldThrow = false;

    render(
      <ThemeProvider theme={theme}>
        <ErrorBoundary>
          <Bomb />
        </ErrorBoundary>
      </ThemeProvider>,
    );

    expect(screen.getByText('恢复正常')).toBeTruthy();
    expect(screen.queryByText('页面渲染出错')).toBeNull();
  });

  it('子树抛错时显示兜底与错误消息，并记录一次日志', () => {
    render(
      <ThemeProvider theme={theme}>
        <ErrorBoundary>
          <Bomb />
        </ErrorBoundary>
      </ThemeProvider>,
    );

    expect(screen.getByText('页面渲染出错')).toBeTruthy();
    // 消息要露出来：只说"出错了"等于让用户和排障的人都无话可说
    expect(screen.getByText('boom-in-render')).toBeTruthy();
    // 必须有人喊过：断言的是「componentDidCatch 里那行 console.error 真的跑了」
    expect(console.error).toHaveBeenCalled();
  });

  it('点重试后子树重新挂载并恢复正常', () => {
    render(
      <ThemeProvider theme={theme}>
        <ErrorBoundary>
          <Bomb />
        </ErrorBoundary>
      </ThemeProvider>,
    );

    // 先让它进入错误态，再撤掉抛错开关，模拟"偶发故障已消失"
    expect(screen.getByText('页面渲染出错')).toBeTruthy();
    shouldThrow = false;

    fireEvent.click(screen.getByRole('button', { name: '重试' }));

    expect(screen.getByText('恢复正常')).toBeTruthy();
    expect(screen.queryByText('页面渲染出错')).toBeNull();
  });
});
