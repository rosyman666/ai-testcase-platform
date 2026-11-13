import { lazy, Suspense, type ReactElement } from 'react';
import { createBrowserRouter, Navigate, Outlet, useLocation } from 'react-router-dom';
import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import AppShell from '@/components/layout/AppShell';
import { useAuthStore } from '@/store/authStore';

/**
 * 路由守卫与懒加载注册的**唯一入口**（见 §2.3 硬约定）。
 *
 * 纪律：page 组件**不得自建** `<Routes>` / `createBrowserRouter`，新增页面只在这里加一条。
 * 理由：守卫、懒加载边界、404 兜底如果散落在页面里，会变成"某个页面忘了包守卫"的漏洞。
 *
 * 当前层级：`/login`（公开）→ `RequireAuth`（守卫）→ `AppShell`（布局壳）→ 9 条业务路由。
 * 业务路由的 path 与 element 一旦确定就不要在本层顺手改，加模块只在 AppShell 的 children 里追加。
 */

// 懒加载：首屏只加载登录页，业务页按需拉取
const LoginPage = lazy(() => import('@/pages/Login'));
const HomePage = lazy(() => import('@/pages/Home'));
// T03：文档模块（只新增，不改动已有路由）
const DocListPage = lazy(() => import('@/pages/docs/DocList'));
const DocDetailPage = lazy(() => import('@/pages/docs/DocDetail'));
// T04：提示词模板（列表 / 详情 / 试跑三合一，不单独开详情路由）
const PromptTemplateListPage = lazy(() => import('@/pages/prompts/PromptTemplateList'));
// T05：测试点评审（按文档查看，所以路由带 docId）
const TestPointReviewPage = lazy(() => import('@/pages/testpoints/TestPointReviewPage'));
// T07-3+T08 前端合并批：任务 + 用例
const TaskListPage = lazy(() => import('@/pages/tasks/TaskList'));
const TaskDetailPage = lazy(() => import('@/pages/tasks/TaskDetail'));
const CaseBoardPage = lazy(() => import('@/pages/cases/CaseBoard'));
// T09/T11：指标与成本看板（只追加，不动已有 7 条与兜底）
const OverviewDashboardPage = lazy(() => import('@/pages/metrics/OverviewDashboard'));

/** PageBoundary 属性。 */
interface PageBoundaryProps {
  /** 待渲染的页面元素。 */
  children: ReactElement;
  /**
   * 兜底占位是否按"整屏"计算高度。
   *
   * 只有落在 AppShell **外面**的页面（`/login`）才传 true；
   * 壳内的页面走 `height: 100%`，理由见下方 JSDoc。
   */
  fullScreen?: boolean;
}

/**
 * 懒加载兜底：居中转圈（避免白屏突兀）。
 *
 * 只包**页面级**懒加载组件，不要包 `<AppShell />`：AppShell 是壳，
 * 一旦被 Suspense 包住，任何页面 chunk 未就绪时整壳都会被替换成转圈，
 * 侧栏与顶栏跟着闪一下。
 *
 * 兜底高度必须**分两种场景**，不要统一成一个值：
 * - 壳外（`/login`，传 `fullScreen`）：用 `minHeight: 100vh`。登录页的父级是 body，
 *   没有别的容器撑高度，只有视口单位能拿到参照，不留满屏会让转圈贴在顶端；
 * - 壳内（其余 9 条）：用 `height: 100%`。`main` 是 `h-screen` flex 列里的
 *   `flex-1 + min-h-0`，高度已经确定，`100%` 能正确解析到它，转圈精确居中。
 *
 * 这里踩过两次，记下来免得再踩：
 * - 最初用 `100vh`：`main` 只有 751px（800 − TopBar 49px），兜底却按 800px 算，
 *   每次首次进入未加载页面都会闪一条 49px 的多余竖向滚动条（实测 `overflowPx=49`）；
 * - 改成 `40vh` 后溢出归零，但 320px 的盒子撑不满 751px 的容器，转圈偏上 215px
 *   （1920×1080 下偏 299px）——**修掉溢出不能顺手把居中丢掉**。
 *
 * 用 MUI `sx` 而不是 Tailwind 任意值（`min-h-[40vh]`）：任意值依赖 JIT 扫描到字面量、
 * 且和 MUI 的 emotion 样式层叠顺序存在不确定性，`sx` 最稳。
 *
 * @param props 属性
 * @returns Suspense 边界
 */
function PageBoundary({ children, fullScreen = false }: PageBoundaryProps): JSX.Element {
  return (
    <Suspense
      fallback={
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            // 壳外（/login）：按视口高度铺满，父级是 body，只有 100vh 能拿到参照。
            // 壳内（其余 9 条）：用 100% 而不是 40vh——`main` 是 `h-screen` flex 列里的
            // `flex-1 + min-h-0`，有确定高度，100% 能解析到它；40vh 只有容器的一半多
            // （1280×800 下 320px vs 751px），转圈会明显偏上，虽然不再溢出但不居中。
            minHeight: fullScreen ? '100vh' : undefined,
            height: fullScreen ? undefined : '100%',
          }}
        >
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
      // 唯一的 fullScreen：登录页在 AppShell 外面，兜底要占满整屏
      <PageBoundary fullScreen>
        <LoginPage />
      </PageBoundary>
    ),
  },
  {
    // 受保护区域：后续所有业务页都挂在这个 children 下
    element: <RequireAuth />,
    children: [
      {
        // T12-1 布局壳：侧栏 + 顶栏 + 内容滚动区。它只做布局，
        // 因此**不包 PageBoundary**（理由见 PageBoundary 的注释）
        element: <AppShell />,
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
          {
            // T05 测试点评审：列表永远属于某一篇文档，所以 docId 进路径而不是查询串
            path: '/testpoints/:docId',
            element: (
              <PageBoundary>
                <TestPointReviewPage />
              </PageBoundary>
            ),
          },
          {
            // T07-3 任务列表
            path: '/tasks',
            element: (
              <PageBoundary>
                <TaskListPage />
              </PageBoundary>
            ),
          },
          {
            // T07-3 任务详情
            path: '/tasks/:id',
            element: (
              <PageBoundary>
                <TaskDetailPage />
              </PageBoundary>
            ),
          },
          {
            // T08 用例看板：taskId 是筛选条件不是路径身份（可跨任务看），
            // 与 /testpoints/:docId 的反例同理
            path: '/cases',
            element: (
              <PageBoundary>
                <CaseBoardPage />
              </PageBoundary>
            ),
          },
          {
            // T09/T11 指标与成本看板：只追加这一条，/login、/、* 兜底一字不动
            path: '/metrics',
            element: (
              <PageBoundary>
                <OverviewDashboardPage />
              </PageBoundary>
            ),
          },
        ],
      },
    ],
  },
  {
    path: '*',
    element: <Navigate to="/" replace />,
  },
]);
