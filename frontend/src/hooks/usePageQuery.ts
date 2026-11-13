/**
 * 分页状态 ↔ 后端 `PageQuery` 参数的双向映射。
 *
 * **换算规则只有一条**：`uiPage = apiPage - 1`，两个方向必须由同一个数字决定。
 * MUI `TablePagination` 的 `page` 是 0-based（第一页 = 0），后端 `PageQuery` 是
 * 1-based（第一页 = 1）。错一次的后果不是报错，是整页数据错位 + 看起来像
 * "后端数据乱序"，且只翻一次页看不出来。
 *
 * **两种落地形态，都合法，但不要第三种**：
 * 1. `page` / `size` 独立管理 → 用本 hook，换算由 `query` 统一给出；
 * 2. `page` 与筛选条件塞在**同一个 state 对象**里 → 换算只能内联写
 *    （`page={query.page - 1}` 配 `page: nextPage + 1`）。这么做不是偷懒：
 *    "搜索 + 回第一页"必须原子生效，拆成本 hook + 独立的筛选 state 会变成两次
 *    setState，中间那一帧会用旧关键词 + 新页码发一次请求。
 *    当前内联的两处：`pages/docs/DocList.tsx`、`pages/testpoints/TestPointPicker.tsx`。
 *
 * 无论哪种形态，`onRowsPerPageChange` 都必须把页码重置为第一页——换页大小后
 * 停在第 5 页，用户看到的是一个不存在的页。
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
