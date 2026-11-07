import { useEffect } from 'react';
import { RouterProvider } from 'react-router-dom';
import { useSnackbar } from 'notistack';
import { router } from '@/routes';
import { registerToastHandler } from '@/utils/toast';

/**
 * 提示桥：把 notistack 的 `enqueueSnackbar` 注册给模块级的 `toast()`，
 * 这样 `api/http.ts` 的拦截器（不在 React 组件树里）也能弹提示。
 */
function ToastBridge(): null {
  const { enqueueSnackbar } = useSnackbar();

  useEffect(() => {
    registerToastHandler((message, variant) => {
      enqueueSnackbar(message, { variant, autoHideDuration: 4000 });
    });
  }, [enqueueSnackbar]);

  return null;
}

/**
 * 应用根组件：挂路由出口。
 *
 * 全局 Provider（QueryClient / Theme / Snackbar）在 `main.tsx` 里，本组件只负责
 * "提示桥 + 路由"，保持职责单一。
 */
export default function App(): JSX.Element {
  return (
    <>
      <ToastBridge />
      <RouterProvider router={router} />
    </>
  );
}
