/**
 * Vitest 全局 setup。当前只需清理 jsdom 的副作用（如 `window.location`）。
 */
import { afterEach } from 'vitest';

afterEach(() => {
  // 预留：未来需要清理 localStorage / mock 时在此扩展
});
