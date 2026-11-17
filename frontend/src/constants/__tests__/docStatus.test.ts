/**
 * `constants/docStatus.ts` 的单测（收尾补齐的测试债）。
 *
 * <p><b>为什么要单独测这个文件</b>：`getParseStatusMeta` 的契约里有一半是**回退分支**
 * ——「未知 code → `{ label: 裸 code, color: 'default' }`」。这条分支此前从来没被真正执行过：
 * `pages/docs/__tests__/DocDetail.test.tsx:40-43` 把整个模块 `vi.mock` 掉了，
 * 只返回写死的元数据，所以哪怕回退分支被删成 `return PARSE_STATUS_META[code]`（未知码返回
 * `undefined`，页面直接崩），那边也照样全绿。</p>
 *
 * <p>这里锁四类边界，每一类都对应一种真实会发生的退化：</p>
 * <ol>
 *   <li>已知码的 label/color 被改错（同状态不同色、同色不同词）；</li>
 *   <li>未知码回退成「未知状态」这种“好看但丢信息”的文案，或者干脆不兜底；</li>
 *   <li>大小写敏感被顺手做成 `toUpperCase()`（后端枚举是精确匹配，前端放宽会掩盖契约漂移）；</li>
 *   <li>`PARSED` 被塞进 `ALL_STATUS`（D-56：那是任务/用例/执行结果三域的并集）。</li>
 * </ol>
 */

import { describe, expect, it } from 'vitest';
import { getParseStatusMeta, PARSE_STATUS_META } from '@/constants/docStatus';
import { ALL_STATUS, getStatusMeta, isStatusKey } from '@/constants/status';

describe('constants/docStatus — getParseStatusMeta 已知码', () => {
  /**
   * 逐字段断言而不是 `toBeTruthy()`。
   *
   * 「有个对象返回」这件事对回归零判别力：把 `PARSED` 的 color 从 success 改成 error、
   * 把 label 从「已解析」改成「解析完成」，`toBeTruthy()` 全都绿——而前者会让成功的解析
   * 在列表里显示成红色。
   */
  it('三档已知码的 label 与 color 逐个字段正确', () => {
    expect(getParseStatusMeta('PENDING')).toEqual({ label: '待解析', color: 'warning' });
    expect(getParseStatusMeta('PARSED')).toEqual({ label: '已解析', color: 'success' });
    expect(getParseStatusMeta('FAILED')).toEqual({ label: '解析失败', color: 'error' });
  });

  it('元数据表恰好三档，与后端 ParseStatus 枚举一致（不许自行加第四档）', () => {
    // 后端 common.enums.ParseStatus 只有 PENDING / PARSED / FAILED；
    // 前端多一档就与契约脱节，而且没人会去删它。
    expect(Object.keys(PARSE_STATUS_META).sort()).toEqual(['FAILED', 'PARSED', 'PENDING']);
  });
});

describe('constants/docStatus — getParseStatusMeta 未知码回退', () => {
  /**
   * 回退的 `label` 必须是**裸 code**，不是「未知状态」。
   *
   * 列表页宁可露出原始编码，也不要静默丢信息：看到「ARCHIVED」运维能立刻反应
   * “后端加了状态、前端没同步”；看到「未知状态」只会知道前端不认识，然后开始翻日志。
   */
  it('未知码回退成「裸 code + default 灰」，而不是中文兜底词', () => {
    expect(getParseStatusMeta('ARCHIVED')).toEqual({ label: 'ARCHIVED', color: 'default' });
  });

  it('大小写敏感：小写 parsed 不算已知码，回退成裸 code', () => {
    // 与后端枚举（精确匹配）保持一致。这里如果被顺手改成 toUpperCase() 查表，
    // 前端就会开始“容忍”后端不可能返回的形态，真正的契约漂移反而被掩盖。
    expect(getParseStatusMeta('parsed')).toEqual({ label: 'parsed', color: 'default' });
    expect(getParseStatusMeta('Parsed')).toEqual({ label: 'Parsed', color: 'default' });
  });
});

describe('constants/docStatus — getParseStatusMeta 不抛错', () => {
  it('未知码 / 空串 / 带空格的码都不抛异常，且都回退成裸 code', () => {
    // 列表页是一次 map 出来的，这里抛一次就是整张表白掉
    expect(() => getParseStatusMeta('ARCHIVED')).not.toThrow();
    expect(() => getParseStatusMeta('')).not.toThrow();
    expect(() => getParseStatusMeta('  ')).not.toThrow();
    expect(() => getParseStatusMeta(' PARSED ')).not.toThrow();

    // 空串回退成空 label（不是 undefined）：调用方渲染 `${meta.label}` 不会出现 "undefined"
    expect(getParseStatusMeta('')).toEqual({ label: '', color: 'default' });
    // 带空格的码不 trim —— 后端枚举里没有这种值，出现就说明契约层面出问题了，露出原样更好排障
    expect(getParseStatusMeta(' PARSED ')).toEqual({ label: ' PARSED ', color: 'default' });
  });
});

describe('constants/docStatus — PARSED 不属于任务/用例/执行结果三域（D-56）', () => {
  /**
   * `ALL_STATUS` 是任务 / 用例 / 执行结果**三个域**的并集，`StatusChip` 按 code 查它。
   *
   * 把 `PARSED` 并进去的后果不是"多一个词"：`getStatusMeta('PARSED')` 会命中，`StatusChip
   * code="PARSED"` 于是渲染成「已解析」的绿色 Chip 而不是刻意保留的灰色裸码，
   * 「待解析 / PARSED / 解析失败」这种混排就出现了——`docStatus.ts` 的注释专门警告过这一点。
   *
   * <p><b>注意 `isStatusKey` 不是这条防线的承重墙</b>：它的实现是 `code in STATUS_COLORS`
   * （`theme.ts` 的色板，12 键），而 `ALL_STATUS` 是语义表，两条独立实现。把 `PARSED` 并进
   * `ALL_STATUS` **不会**让 `isStatusKey('PARSED')` 变成 true——这条断言今天成立，但只是顺带。
   * 真正承重的是下一行 `getStatusMeta('PARSED') === null` 与 `ALL_STATUS` 的属性断言，
   * 变异验证已实测：删掉 `status` 语义表里的键，只有这两条会红。</p>
   *
   * 这条断言的作用是：**将来谁图省事把解析状态并进 `ALL_STATUS`，这里会红**，
   * 而红的时候 diff 只有一行，改回去的成本最低。
   */
  it('isStatusKey("PARSED") 为 false，且 getStatusMeta("PARSED") 返回 null', () => {
    expect(isStatusKey('PARSED')).toBe(false);
    expect(getStatusMeta('PARSED')).toBeNull();
    expect(ALL_STATUS).not.toHaveProperty('PARSED');
  });

  it('对照：三域里的真实状态码仍然是被认识的（防止上一条被写死成"永远 false"）', () => {
    // 反向对照：上一条如果被改成恒真断言（比如误写成 expect(false).toBe(false)），
    // 这里会红，因为任务域的 PENDING / 用例域的 DRAFT / 执行域的 PASSED 必须都为 true。
    expect(isStatusKey('PENDING')).toBe(true);
    expect(isStatusKey('DRAFT')).toBe(true);
    expect(isStatusKey('PASSED')).toBe(true);
    expect(getStatusMeta('PASSED')).toEqual({ label: '通过', color: 'success' });
  });
});
