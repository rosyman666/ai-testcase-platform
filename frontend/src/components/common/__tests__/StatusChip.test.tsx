/**
 * `StatusChip` 的未知状态码告警与去重（T12-2b）。
 *
 * 目录说明：`components/common/__tests__/` 此前不存在——**`StatusChip` 在这次改动前
 * 零测试覆盖**，本文件是它的首例基线，也是去重逻辑唯一的一道护栏。
 * 所以那条断言必须同时覆盖「同 code 只 1 次」和「换 code 变 2 次」：
 * 只测前者的话，把 `Set<string>` 退化成 `let warned = false` 也能通过。
 *
 * ⚠️ **本文件只允许存在这一条用例**（除非改用 `vi.resetModules()` + 动态 import 拿新模块实例）：
 * `warnedCodes` 是 module 级 `Set`，同一测试文件内跨用例共享。
 * 加第二条用例时，若它再次断言"某 code 会告警"，会因为前一条已经告警过而失败——
 * 失败原因是测试隔离，不是实现缺陷，很容易被后来人误诊。
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import StatusChip from '@/components/common/StatusChip';
import theme from '@/theme';

/** 两个只在本文件出现的未知 code，避免与真实业务状态或其它测试串味。 */
const UNKNOWN_A = '__UNKNOWN_CODE_A__';
const UNKNOWN_B = '__UNKNOWN_CODE_B__';

/**
 * 渲染一个状态 Chip。
 *
 * @param code 状态码
 * @returns RTL 的 render 结果
 */
function renderChip(code: string): ReturnType<typeof render> {
  return render(
    <ThemeProvider theme={theme}>
      <StatusChip code={code} />
    </ThemeProvider>,
  );
}

describe('components/common/StatusChip — 未知状态码告警去重', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('同一未知 code 只告警一次，换一个 code 会再次告警', () => {
    // mockImplementation 必须给：否则 warn 照旧打到 stderr，
    // 既污染 CI 日志，又会让人以为发现了真告警
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);

    const first = renderChip(UNKNOWN_A);
    expect(warnSpy.mock.calls.filter((call) => String(call[0]).includes(UNKNOWN_A))).toHaveLength(1);

    // 同一 code 第二次渲染（模拟"一屏里 50 行都是这个脏状态"）——不得重复告警
    first.unmount();
    renderChip(UNKNOWN_A);
    expect(warnSpy.mock.calls.filter((call) => String(call[0]).includes(UNKNOWN_A))).toHaveLength(1);

    // 换一个新 code：必须再告警一次 —— 这条挡的是"把 Set 写成 let warned = false"的退化
    cleanup();
    renderChip(UNKNOWN_B);
    expect(warnSpy.mock.calls.filter((call) => String(call[0]).includes(UNKNOWN_B))).toHaveLength(1);

    // 合计恰好 2 次：证明去重粒度是"按 code"，不是"整个进程只 warn 一次"
    expect(warnSpy).toHaveBeenCalledTimes(2);
  });
});
