import { describe, it, expect } from 'vitest';
import { STATUS_COLORS } from '@/theme';
import {
  TASK_STATUS,
  ITEM_STATUS,
  CASE_STATUS,
  EXECUTION_STATUS,
  ALL_STATUS,
  getStatusMeta,
  getStatusColor,
  isStatusKey,
} from '@/constants/status';

describe('constants/status — STATUS_COLORS 12 键双向覆盖', () => {
  const themeKeys = Object.keys(STATUS_COLORS).sort();
  const domainKeys = Object.keys(ALL_STATUS).sort();

  it('STATUS_COLORS 的每个键在某个域里有 label', () => {
    // 双向覆盖：正向 —— theme 键 ⊆ 域键
    for (const key of themeKeys) {
      expect(ALL_STATUS[key], `STATUS_COLORS 的 "${key}" 在 constants/status.ts 里没有 label`).toBeDefined();
    }
  });

  it('每个域的键在 STATUS_COLORS 里有 color', () => {
    // 双向覆盖：反向 —— 域键 ⊆ theme 键
    for (const key of domainKeys) {
      expect(STATUS_COLORS, `constants/status.ts 的 "${key}" 在 STATUS_COLORS 里没有 color`).toHaveProperty(key);
    }
  });

  it('两个键集合完全一致（无缺漏、无多余）', () => {
    expect(domainKeys).toEqual(themeKeys);
    expect(themeKeys).toHaveLength(12);
  });

  it('任务域不含 SKIPPED', () => {
    expect(TASK_STATUS).not.toHaveProperty('SKIPPED');
  });

  it('任务域恰好 6 个键', () => {
    expect(Object.keys(TASK_STATUS).sort()).toEqual(
      ['CANCELED', 'FAILED', 'PARTIAL', 'PENDING', 'RUNNING', 'SUCCESS'],
    );
  });

  it('item 域恰好 5 个键', () => {
    expect(Object.keys(ITEM_STATUS).sort()).toEqual(
      ['FAILED', 'PENDING', 'RUNNING', 'SKIPPED', 'SUCCESS'],
    );
  });

  it('用例域恰好 4 个键', () => {
    expect(Object.keys(CASE_STATUS).sort()).toEqual(
      ['ACCEPTED', 'DRAFT', 'EDITED', 'REJECTED'],
    );
  });

  it('执行结果域恰好 3 个键', () => {
    expect(Object.keys(EXECUTION_STATUS).sort()).toEqual(
      ['FAILED', 'PASSED', 'SKIPPED'],
    );
  });
});

describe('constants/status — 查表函数', () => {
  it('getStatusMeta 返回 label 和 color', () => {
    const meta = getStatusMeta('RUNNING');
    expect(meta).not.toBeNull();
    expect(meta!.label).toBe('执行中');
    expect(meta!.color).toBe('info');
  });

  it('getStatusMeta 对未知 code 返回 null', () => {
    expect(getStatusMeta('NOT_A_STATUS')).toBeNull();
  });

  it('getStatusColor 与 STATUS_COLORS 一致', () => {
    for (const key of Object.keys(STATUS_COLORS)) {
      expect(getStatusColor(key)).toBe(STATUS_COLORS[key as keyof typeof STATUS_COLORS]);
    }
  });

  it('getStatusColor 对未知 code 返回 default', () => {
    expect(getStatusColor('NOT_A_STATUS')).toBe('default');
  });

  it('isStatusKey 类型守卫', () => {
    expect(isStatusKey('SUCCESS')).toBe(true);
    expect(isStatusKey('NOT_A_STATUS')).toBe(false);
  });
});
