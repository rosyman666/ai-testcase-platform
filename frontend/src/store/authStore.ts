import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

/** 登录态数据结构。 */
interface AuthState {
  /** 访问令牌；null 表示未登录。 */
  token: string | null;
  /** 用户 ID（后端 Long 序列化为字符串）。 */
  userId: string | null;
  /** 登录名。 */
  username: string | null;
  /** 显示名。 */
  displayName: string | null;
  /** 过期时间（ISO-8601 UTC）。 */
  expiresAt: string | null;
  /** 写入登录态。 */
  setAuth: (payload: {
    token: string;
    userId: string;
    username: string;
    displayName: string;
    expiresAt: string;
  }) => void;
  /** 清空登录态（登出、或 token 失效时调用）。 */
  clear: () => void;
}

/**
 * 登录态 store（zustand + localStorage 持久化）。
 *
 * 为什么持久化：刷新页面不该被踢回登录页。token 放在 localStorage 是"有 XSS 才有风险"的
 * 常见取舍——本项目是无外部脚本的纯管理台，比 httpOnly cookie 的跨域配置成本更低。
 */
export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      userId: null,
      username: null,
      displayName: null,
      expiresAt: null,
      setAuth: ({ token, userId, username, displayName, expiresAt }) =>
        set({ token, userId, username, displayName, expiresAt }),
      clear: () =>
        set({ token: null, userId: null, username: null, displayName: null, expiresAt: null }),
    }),
    {
      name: 'ai-testcase-auth',
      storage: createJSONStorage(() => localStorage),
      // 只持久化数据，不持久化函数
      partialize: (state) => ({
        token: state.token,
        userId: state.userId,
        username: state.username,
        displayName: state.displayName,
        expiresAt: state.expiresAt,
      }),
    },
  ),
);

/**
 * 判断当前是否已登录（在组件外使用请调用 `useAuthStore.getState()`）。
 *
 * @returns 有 token 返回 true
 */
export function isAuthenticated(): boolean {
  return Boolean(useAuthStore.getState().token);
}
