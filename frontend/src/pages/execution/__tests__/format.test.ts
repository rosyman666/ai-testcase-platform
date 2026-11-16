/**
 * `pages/execution/format.ts` 的单测（T10-2 联调后补）。
 *
 * <p><b>为什么要单独测这三个纯函数</b>：它们是列表页与详情页**共用的唯一口径**，
 * 而其中 `formatDurationMs` 踩过一个只有联调才能发现的坑——后端 `JacksonConfig`
 * 全局把 `Long` 序列化成 String，`durationMs` 在 JSON 里是 `"123"` 而不是 `123`。
 * 类型写成 `number` 时 tsc **不会报错**（`get<T>()` 的 `T` 只是断言），
 * 运行时 `"123" < 1000` 恰好还能比较（字符串被隐式转数字），
 * 但 `(ms / 1000).toFixed(1)` 这种算术就会出现 `NaN` / 拼接。
 * 所以这里把"字符串入参"当成**正常输入**来钉，而不是当成异常。</p>
 */

import { describe, expect, it } from 'vitest';
import { describeMappedRate, formatDurationMs, formatMappedRate } from '@/pages/execution/format';

describe('pages/execution/format — formatDurationMs', () => {
  it('后端返回的数字字符串按毫秒处理（"1200" → 1.2s）', () => {
    // 真实返回形如 "5297" / "123"，见 T10 后端实测
    expect(formatDurationMs('1200')).toBe('1.2s');
    expect(formatDurationMs('5297')).toBe('5.3s');
  });

  it('不足 1 秒显示毫秒（"850" → 850ms）', () => {
    // 团队指定的固定回归点：字符串入参 + 毫秒档位
    expect(formatDurationMs('850')).toBe('850ms');
    expect(formatDurationMs('123')).toBe('123ms');
    expect(formatDurationMs('0')).toBe('0ms');
  });

  it('超过 1 分钟显示 m + 两位秒（"125000" → 2m05s）', () => {
    expect(formatDurationMs('125000')).toBe('2m05s');
    expect(formatDurationMs('60000')).toBe('1m00s');
  });

  it('null / undefined / 空串 / 脏值 / 负数 一律显示「-」，绝不显示 NaNms', () => {
    expect(formatDurationMs(null)).toBe('-');
    expect(formatDurationMs(undefined)).toBe('-');
    expect(formatDurationMs('')).toBe('-');
    expect(formatDurationMs('   ')).toBe('-');
    // 这条是团队指定的固定回归点：非法字符串不得渲染成 NaNms
    expect(formatDurationMs('abc')).toBe('-');
    expect(formatDurationMs('-1')).toBe('-');
    expect(formatDurationMs('NaN')).toBe('-');
  });
});

describe('pages/execution/format — formatMappedRate', () => {
  it('后端给的 0~1 小数 ×100 保留 1 位（0.1250 → 12.5%）', () => {
    expect(formatMappedRate(0.85, 20)).toBe('85.0%');
    expect(formatMappedRate(0.125, 8)).toBe('12.5%');
    expect(formatMappedRate(1, 3)).toBe('100.0%');
  });

  it('0 条结果时无定义，显示「-」而不是 0.0%', () => {
    // 0 条结果（空报告）的映射率没有分母，写 0.0% 会被读成"一条都没映射上"
    expect(formatMappedRate(0, 0)).toBe('-');
  });

  it('有结果但一条未映射时如实显示 0.0%', () => {
    expect(formatMappedRate(0, 8)).toBe('0.0%');
  });

  it('映射率非有限数时退化为「-」，不渲染 NaN%', () => {
    expect(formatMappedRate(Number.NaN, 10)).toBe('-');
  });
});

describe('pages/execution/format — describeMappedRate', () => {
  it('给出分子分母，避免用户只看到百分比猜不出绝对值', () => {
    expect(describeMappedRate(1, 8)).toBe('1 / 8 条结果映射到了用例');
    expect(describeMappedRate(0, 0)).toBe('0 / 0 条结果映射到了用例');
  });
});
