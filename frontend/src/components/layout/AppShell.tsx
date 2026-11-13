/**
 * 布局壳：侧栏 + 顶栏 + 内容滚动区。
 *
 * 它是受保护区域的**唯一**布局路由（`routes/index.tsx` 里套住 10 条业务路由），
 * 这样切页面时侧栏与顶栏不重建，只替换 `<Outlet />` 的内容。
 */

import { useCallback, useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import Box from '@mui/material/Box';
import ErrorBoundary from '@/components/common/ErrorBoundary';
import SideNav from '@/components/layout/SideNav';
import TopBar from '@/components/layout/TopBar';

/**
 * 应用布局壳。
 *
 * 三个 className 缺一不可：
 * - `h-screen`：撑满视口高度，否则 `main` 的 `overflow-y-auto` 没有可算的高度基准；
 * - `overflow-hidden`：把滚动限制在内层 `main`，避免整页出现第二条滚动条；
 * - `min-w-0`（加在内层 flex 列上）：flex 子项默认 `min-width: auto`，
 *   内容比容器宽时会把父级顶宽，1280px 下会出现横向滚动条。
 *
 * @returns 布局壳
 */
export default function AppShell(): JSX.Element {
  const [navOpen, setNavOpen] = useState(false);
  const { pathname } = useLocation();

  // 用 useCallback 固定引用：两个回调会传给 Drawer / TopBar，
  // 每次渲染都新建函数会让 memo 化的子组件白重渲染
  const openNav = useCallback((): void => {
    setNavOpen(true);
  }, []);

  const closeNav = useCallback((): void => {
    setNavOpen(false);
  }, []);

  return (
    <Box className="flex h-screen w-full overflow-hidden">
      <SideNav open={navOpen} onClose={closeNav} />

      {/* min-w-0：没有它，flex 子项的最小宽度是 auto，宽表格/长代码块会把整列顶宽 */}
      <Box className="flex min-w-0 flex-1 flex-col">
        <TopBar onOpenNav={openNav} />

        {/* min-h-0 同理：纵向 flex 子项的 min-height 也是 auto，
            不加则主区只会被内容撑高、自身不滚，滚动落回 body */}
        <Box component="main" className="min-h-0 flex-1 overflow-y-auto">
          {/* 错误边界放在 main 内、只包 Outlet：页面渲染崩溃时侧栏与顶栏还在，
              用户能直接切去别的模块；包在根上则一次页面崩溃等于整个应用退出。
              key 按路径给：错误边界一旦进入 error 状态不会自愈，不换 key 的话
              切到别的路由仍看到上一页的报错。 */}
          <ErrorBoundary key={pathname}>
            <Outlet />
          </ErrorBoundary>
        </Box>
      </Box>
    </Box>
  );
}
