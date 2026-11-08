/**
 * 分页状态 ↔ 后端 `PageQuery` 参数的双向映射。
 *
 * **`page` 0-based ↔ 1-based 的换算只允许在这一个文件里**。
 * 错一次就是整页数据错位，且看上去像"后端数据乱序"（§6.1）。
 *
 * MUI `TablePagination` 的 `page` 是 0-based（第一页 = 0），后端 `PageQuery` 是 1-based（第一页 = 1）。
 */

import { useState, useCallback, useMemo } from 'react';

/** 后端分页查询参数（`PageQuery`）。page 是 **1-based**。 */
export interface PageQueryParams {
  page: number;
  size: number;
}

/** 分页查询返回值。 */
export interface UsePageQueryResult {
  /** MUI TablePagination 的 page（0-based）。 */
  page: number;
  /** 每页条数。 */
  size: number;
  /** 切页回调（0-based）。 */
  setPage: (page: number) => void;
  /** 切每页条数回调（自动重置到第一页）。 */
  setSize: (size: number) => void;
  /** 后端查询参数（page 已转 1-based）。 */
  query: PageQueryParams;
  /** 重置到第一页（筛选条件变化时调用）。 */
  resetPage: () => void;
}

/**
 * 分页状态管理。
 *
 * @param defaultSize 默认每页条数
 * @returns 分页状态与回调
 */
export function usePageQuery(defaultSize = 20): UsePageQueryResult {
  const [page, setPageState] = useState(0);
  const [size, setSizeState] = useState(defaultSize);

  const setPage = useCallback((p: number) => {
    setPageState(Math.max(0, p));
  }, []);

  const setSize = useCallback((s: number) => {
    setSizeState(s);
    setPageState(0); // 换页大小后回到第一页
  }, []);

  const resetPage = useCallback(() => {
    setPageState(0);
  }, []);

  // page 0-based → 1-based：**全项目唯一的换算点**
  const query = useMemo(() => ({
    page: page + 1,
    size,
  }), [page, size]);

  return { page, size, setPage, setSize, query, resetPage };
}
