/**
 * `usePageQuery` 的 0/1 基换算回归（T12-3）。
 *
 * 这个 hook 的全部价值就是那个 `+1`。它错的时候**不报错、不崩、类型也对**，
 * 症状是"列表第一页显示的是第二页的内容"，而第一页永远看不到——很容易被
 * 归因成"后端排序有问题"。所以每一条分支都必须有断言，不能只测初值。
 *
 * 另外锁两条容易漏的：
 * - `setSize` 必须把 page 重置为 0。换页大小后停在第 5 页，用户看到的是不存在的页。
 * - `setPage(-1)` 必须被夹到 0。MUI 正常不会传负数，但手写调用会。
 */

import { describe, expect, it } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { usePageQuery } from '@/hooks/usePageQuery';

describe('hooks/usePageQuery — 0/1 基换算', () => {
  it('初始态：UI 页码 0、后端页码 1、默认 size 20', () => {
    const { result } = renderHook(() => usePageQuery());

    expect(result.current.page).toBe(0);
    expect(result.current.size).toBe(20);
    // 这条是本文件的中心断言：UI 0-based 与后端 1-based 的差恒为 1
    expect(result.current.query.page).toBe(1);
    expect(result.current.query.size).toBe(20);
  });

  it('接受自定义默认 size，且 query 同步', () => {
    const { result } = renderHook(() => usePageQuery(10));

    expect(result.current.size).toBe(10);
    expect(result.current.query).toEqual({ page: 1, size: 10 });
  });

  it('setPage 只改 UI 页码，query 恒比它大 1', () => {
    const { result } = renderHook(() => usePageQuery());

    act(() => result.current.setPage(2));

    expect(result.current.page).toBe(2);
    expect(result.current.query.page).toBe(3);
  });

  it('setPage(-1) 被夹到 0，不会把后端页码算成 0', () => {
    const { result } = renderHook(() => usePageQuery());

    act(() => result.current.setPage(-1));

    expect(result.current.page).toBe(0);
    expect(result.current.query.page).toBe(1);
  });

  it('setSize 会把页码重置到第一页（换页大小后不能停在原页）', () => {
    const { result } = renderHook(() => usePageQuery());

    act(() => result.current.setPage(4));
    expect(result.current.query.page).toBe(5);

    act(() => result.current.setSize(50));

    expect(result.current.size).toBe(50);
    expect(result.current.page).toBe(0);
    expect(result.current.query).toEqual({ page: 1, size: 50 });
  });

  it('resetPage 回到第一页但保留 size（筛选条件变化时用）', () => {
    const { result } = renderHook(() => usePageQuery(50));

    act(() => result.current.setPage(3));
    act(() => result.current.resetPage());

    expect(result.current.page).toBe(0);
    expect(result.current.query).toEqual({ page: 1, size: 50 });
  });
});
