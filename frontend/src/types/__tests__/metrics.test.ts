/**
 * T11 契约纠偏回归：后端 `long` 字段经 `JacksonConfig` 的 `ToStringSerializer`
 * 序列化为 JSON 字符串，前端 `types/metrics.ts` 必须声明为 `string`，
 * 且 `resolveZeroDisplay()` 在做比较 / 求和前必须显式 `Number(...)` 转换。
 */
import { describe, expect, it } from 'vitest';
import { resolveZeroDisplay, type OverviewVO } from '@/types/metrics';

/**
 * 构造一份「服务端真实出参」形状的 overview：所有 `long` 字段都是字符串，
 * 比率是数字，`boolean` 是布尔。用于锁死前后端序列化契约。
 *
 * @param overrides 需要覆盖的字段
 * @returns 完整的 OverviewVO
 */
function overview(overrides: Partial<OverviewVO> = {}): OverviewVO {
  return {
    generatedCount: '10',
    reviewedCount: '6',
    draftCount: '4',
    acceptedCount: '3',
    editedCount: '1',
    rejectedCount: '2',
    acceptRate: 4 / 6,
    editedRate: 1 / 6,
    rejectRate: 2 / 6,
    strictAcceptRate: 3 / 6,
    totalCostMicro: '3500000',
    totalPromptTokens: '1000',
    totalCompletionTokens: '500',
    unitCostMicro: '875000',
    p50Ms: '1200',
    p95Ms: '4800',
    costZeroReasons: [],
    hasReviewData: true,
    ...overrides,
  };
}

describe('types/metrics.ts 数值序列化契约', () => {
  it('long 型字段声明为 string（编译期 + 运行期双重断言）', () => {
    const vo = overview();
    // 若 totalCostMicro 被误写成 number，这一行会直接 tsc 报错
    const totalCostMicro: string = vo.totalCostMicro;
    const reviewedCount: string = vo.reviewedCount;
    const p50Ms: string = vo.p50Ms;
    expect(typeof totalCostMicro).toBe('string');
    expect(typeof reviewedCount).toBe('string');
    expect(typeof p50Ms).toBe('string');
    expect(typeof vo.acceptRate).toBe('number');
    expect(typeof vo.hasReviewData).toBe('boolean');
  });

  it('A：有评审、有成本 → A_VALUE', () => {
    expect(
      resolveZeroDisplay({ overview: overview(), rejectReasonCount: 2, costPointCount: 3 }),
    ).toBe('A_VALUE');
  });

  it('B：reviewedCount="0" → B_NO_REVIEW，绝不落到 C / A', () => {
    expect(
      resolveZeroDisplay({
        overview: overview({
          reviewedCount: '0',
          hasReviewData: false,
          totalCostMicro: '0',
        }),
        rejectReasonCount: 0,
        costPointCount: 0,
      }),
    ).toBe('B_NO_REVIEW');
  });

  it('C：token 非 0 但成本为字符串 "0" → C_COST_ZERO（证明按 Number 比较）', () => {
    expect(
      resolveZeroDisplay({
        overview: overview({ totalCostMicro: '0', costZeroReasons: ['MOCK_PRICE_ZERO'] }),
        rejectReasonCount: 1,
        costPointCount: 1,
      }),
    ).toBe('C_COST_ZERO');
  });

  it('求和必须 Number(...)："1000" + "500" === 1500，不是 "1000500"', () => {
    const vo = overview({
      totalPromptTokens: '1000',
      totalCompletionTokens: '500',
      totalCostMicro: '0',
    });
    expect(Number(vo.totalPromptTokens) + Number(vo.totalCompletionTokens)).toBe(1500);
    expect(resolveZeroDisplay({ overview: vo, rejectReasonCount: 1, costPointCount: 1 })).toBe(
      'C_COST_ZERO',
    );
  });

  it('D：generatedCount="0" 且两个分布为空 → D_EMPTY_DIST', () => {
    expect(
      resolveZeroDisplay({
        overview: overview({
          generatedCount: '0',
          reviewedCount: '1',
          hasReviewData: true,
          totalCostMicro: '0',
          totalPromptTokens: '0',
          totalCompletionTokens: '0',
        }),
        rejectReasonCount: 0,
        costPointCount: 0,
      }),
    ).toBe('D_EMPTY_DIST');
  });

  it('overview=null（加载中）→ A_VALUE，不抛异常', () => {
    expect(
      resolveZeroDisplay({ overview: null, rejectReasonCount: 0, costPointCount: 0 }),
    ).toBe('A_VALUE');
  });
});
