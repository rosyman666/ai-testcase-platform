/**
 * 渲染期错误边界。
 *
 * **为什么需要它**：React 在渲染过程中抛出的异常会一路冒到根，整棵树被卸载 ——
 * 用户看到的是一片白屏，控制台里才有 stack。Suspense 的 fallback 接不住这类错误
 * （`PageBoundary` 管的是"chunk 还没下载完"，不是"渲染时炸了"），二者不能互相替代。
 *
 * **为什么放在 `main` 里而不是根上**：包住 `<Outlet />`，页面崩了侧栏与顶栏还在，
 * 用户能直接点去别的模块；包在根上则一次页面崩溃等于整个应用退出。
 *
 * **必须配合 `key={location.pathname}` 使用**：React 的错误边界一旦进入 error 状态
 * **不会自己恢复**，切到别的路由仍会渲染上一次的 fallback。按路径给 key 让它在
 * 路由变化时重建，是唯一不依赖额外状态的做法。
 *
 * **fallback 不用 `toast`**：渲染期抛错时无法保证 notistack 的 provider 还活着
 * （它可能就在被卸载的子树里）；而且 toast 是 4 秒后自动消失的瞬时提示，错误信息
 * 需要留在页面上让人读完再决定重试。
 */

import { Component } from 'react';
import type { ErrorInfo, ReactNode } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Typography from '@mui/material/Typography';

/** ErrorBoundary 属性。 */
export interface ErrorBoundaryProps {
  /** 子节点。 */
  children: ReactNode;
  /** 自定义兜底渲染（默认给一个带重试的 Alert）。 */
  fallback?: ReactNode;
}

/** ErrorBoundary 状态。 */
interface ErrorBoundaryState {
  /** 捕获到的异常；`null` 表示正常。 */
  error: Error | null;
}

/**
 * 渲染期错误边界。
 *
 * 类组件是硬性要求：`componentDidCatch` / `getDerivedStateFromError` 没有 hook 等价物。
 */
export default class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null };

  /**
   * 捕获子树的渲染异常。
   *
   * @param error 抛出的异常
   * @returns 新的 state
   */
  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  /**
   * 记录异常。
   *
   * `console.error` 是刻意的：本项目没有接前端监控，这一行是出问题时唯一能看到
   * componentStack 的地方。不吞异常、不上报接口（那需要额外依赖与后端）。
   *
   * @param error 抛出的异常
   * @param info 组件栈
   */
  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('[ErrorBoundary] 页面渲染异常：', error, info.componentStack);
  }

  /** 重试：清掉错误状态，让子树重新挂载一次。 */
  private readonly handleRetry = (): void => {
    this.setState({ error: null });
  };

  /**
   * 渲染。
   *
   * @returns 子树或兜底
   */
  render(): ReactNode {
    const { error } = this.state;
    const { children, fallback } = this.props;

    if (!error) {
      return children;
    }
    if (fallback) {
      return fallback;
    }

    return (
      <Box className="p-6">
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={this.handleRetry}>
              重试
            </Button>
          }
        >
          页面渲染出错
        </Alert>
        <Typography variant="body2" color="text.secondary" className="mt-3">
          {error.message || '未提供错误信息'}
        </Typography>
        <Typography variant="caption" color="text.secondary" className="mt-2 block">
          详细堆栈见浏览器控制台。若重试无效，返回上一页或刷新页面。
        </Typography>
      </Box>
    );
  }
}
