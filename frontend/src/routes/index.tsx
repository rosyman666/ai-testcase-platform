import { lazy, Suspense, type ReactElement } from 'react';
import { createBrowserRouter, Navigate, Outlet, useLocation } from 'react-router-dom';
import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import { useAuthStore } from '@/store/authStore';

/**
 * 路由守卫与懒加载注册的**唯一入口**（见 §2.3 硬约定）。
 *
 * 纪律：page 组件**不得自建** `<Routes>` / `createBrowserRouter`，新增页面只在这里加一条。
 * 理由：守卫、懒加载边界、404 兜底如果散落在页面里，会变成"某个页面忘了包守卫"的漏洞。
 *
 * T02 只搭骨架：`/login`（公开）+ `/`（受保护占位首页）；T12 在此补齐 6 个业务模块。
 */

// 懒加载：首屏只加载登录页，业务页按需拉取
const LoginPage = lazy(() => import('@/pages/Login'));
const HomePage = lazy(() => import('@/pages/Home'));
// T03：文档模块（只新增，不改动已有路由）
const DocListPage = lazy(() => import('@/pages/docs/DocList'));
const DocDetailPage = lazy(() => import('@/pages/docs/DocDetail'));
// T04：提示词模板（列表 / 详情 / 试跑三合一，不单独开详情路由）
const PromptTemplateListPage = lazy(() => import('@/pages/prompts/PromptTemplateList'));

/**
 * 懒加载兜底：居中转圈（避免白屏突兀）。
 *
 * @param props.children 页面元素
 */
function PageBoundary({ children }: { children: ReactElement }): JSX.Element {
  return (
    <Suspense
      fallback={
        <Box className="flex min-h-screen items-center justify-center">
          <CircularProgress size={28} />
        </Box>
      }
    >
      {children}
    </Suspense>
  );
}

/**
 * 路由守卫：无 token 时跳登录页，并把原路径塞进 `redirect` 供登录后回跳。
 */
function RequireAuth(): JSX.Element {
  const token = useAuthStore((state) => state.token);
  const location = useLocation();

  if (!token) {
    const redirect = encodeURIComponent(`${location.pathname}${location.search}`);
    return <Navigate to={`/login?redirect=${redirect}`} replace />;
  }
  return <Outlet />;
}

/** 应用路由表。 */
export const router = createBrowserRouter([
  {
    path: '/login',
    element: (
      <PageBoundary>
        <LoginPage />
      </PageBoundary>
    ),
  },
  {
    // 受保护区域：后续所有业务页都挂在这个 children 下
    element: <RequireAuth />,
    children: [
      {
        path: '/',
        element: (
          <PageBoundary>
            <HomePage />
          </PageBoundary>
        ),
      },
      {
        // T03 文档模块：列表与详情。只新增，`/login`、`/` 与 `*` 兜底保持原样。
        path: '/docs',
        element: (
          <PageBoundary>
            <DocListPage />
          </PageBoundary>
        ),
      },
      {
        path: '/docs/:id',
        element: (
          <PageBoundary>
            <DocDetailPage />
          </PageBoundary>
        ),
      },
      {
        // T04 提示词模板：列表 + 详情弹窗 + 试跑。详情走弹窗不走独立路由，
        // 因为"看着列表改模板、改完立刻试跑"是一个连续动作，跳页会打断对比
        path: '/prompts',
        element: (
          <PageBoundary>
            <PromptTemplateListPage />
          </PageBoundary>
        ),
      },
    ],
  },
  {
    path: '*',
    element: <Navigate to="/" replace />,
  },
]);
