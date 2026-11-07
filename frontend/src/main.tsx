import React from 'react';
import ReactDOM from 'react-dom/client';
import CssBaseline from '@mui/material/CssBaseline';
import { ThemeProvider } from '@mui/material/styles';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SnackbarProvider } from 'notistack';
import App from './App';
import theme from './theme';
import './index.css';

/**
 * 全局单例 QueryClient。
 *
 * 默认策略：不重试（T02 的 axios 拦截器会统一 toast，重复重试只会放大噪音）、
 * 关闭窗口聚焦自动刷新（测试平台的数据变更由用户主动触发）、30s 内视为新鲜。
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 0,
      refetchOnWindowFocus: false,
      staleTime: 30_000,
    },
    mutations: {
      retry: 0,
    },
  },
});

const container = document.getElementById('root');
if (!container) {
  throw new Error('未找到 #root 挂载点，请检查 index.html');
}

ReactDOM.createRoot(container).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={theme}>
        {/* CssBaseline 是全局唯一样式重置来源（Tailwind preflight 已关闭） */}
        <CssBaseline />
        <SnackbarProvider
          maxSnack={3}
          autoHideDuration={3000}
          anchorOrigin={{ vertical: 'top', horizontal: 'right' }}
        >
          <App />
        </SnackbarProvider>
      </ThemeProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
