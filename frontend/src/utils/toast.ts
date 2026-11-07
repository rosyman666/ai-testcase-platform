/**
 * 全局提示桥（toast）。
 *
 * 背景：notistack 的 `enqueueSnackbar` 只能通过 hook 在 React 组件内拿到，
 * 但 `api/http.ts` 的 axios 拦截器是模块级代码，不在组件树里。
 * 所以这里做一层极薄的注册表：`ToastBridge`（挂在 SnackbarProvider 内）启动时注册实现，
 * 拦截器调用 `toast()` 即可。找不到实现时降级到 console，绝不吞掉错误。
 */

/** 提示类型（与 notistack 的 variant 对齐）。 */
export type ToastVariant = 'default' | 'error' | 'success' | 'warning' | 'info';

type ToastHandler = (message: string, variant: ToastVariant) => void;

let handler: ToastHandler | null = null;

/**
 * 注册提示实现（由 ToastBridge 调用）。
 *
 * @param next 具体实现
 */
export function registerToastHandler(next: ToastHandler): void {
  handler = next;
}

/**
 * 弹出提示。
 *
 * @param message 文案（后端返回的 message 已是可直接展示的中文）
 * @param variant 类型，默认 error
 */
export function toast(message: string, variant: ToastVariant = 'error'): void {
  if (handler) {
    handler(message, variant);
    return;
  }
  // 组件树尚未挂载（例如首屏就失败的请求）：降级到 console，保证信息不丢
  console.error(`[toast:${variant}] ${message}`);
}
