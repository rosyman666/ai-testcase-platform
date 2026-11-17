import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * Vite 配置。
 *
 * 关键点：
 * 1. alias `@` → src，全项目统一用绝对别名引用，避免 ../../..；
 * 2. dev proxy：`/api` → `http://localhost:8080`，前端开发期不产生跨域，
 *    生产环境由 Nginx 反代同源；因此 axios 的 baseURL 始终是相对路径 `/api`；
 * 3. SSE 端点（/api/tasks/{id}/stream）走同一 proxy，需关闭 proxy 的缓冲。
 */
export default defineConfig({
  plugins: [react()],
  optimizeDeps: {
    // 首轮预构建就把懒加载路由才会解析到的包收进来。否则首次访问 /executions、
    // /metrics 时 Vite 才发现缺依赖，判定「依赖已变更」重做预构建并清空 deps_temp_*；
    // 本机若干 shell 会给 NODE_OPTIONS 注入批量删除护栏，整个目录一次清掉会被拒绝，
    // 表现为 dev server 无声退出、量测中途 000。预声明是治因，清 NODE_OPTIONS 只是绕。
    //
    // 清单 = `package.json` 的 13 个运行时依赖 + 4 个扫描器看不见的入口：
    // `react-dom/client`、`react/jsx-runtime` / `react/jsx-dev-runtime`（JSX 编译产物，
    // 由插件注入，源码里没有对应 import）、`@mui/material/styles`（MUI 内部按子路径引）。
    // 判据是「这个入口源码里显式写过吗」：写过的交给扫描器，没写过的（编译器/插件注入）
    // 必须在这里钉死。
    //
    // **图标只列 barrel（`@mui/icons-material`），不逐条列深路径**：实测预构建 barrel 时
    // 深路径会被一并登记——冷启动后 `.vite/deps` 里 366 个文件中有 36 个是
    // `@mui_icons-material_*`（含 `_ArrowBackRounded` / `_EditRounded`）。把 18 条深路径
    // 从 include 里滤掉再冷启动，这些文件照样出现，说明"barrel 覆盖不到深路径"是推理、
    // 不是事实。所以这里不留那 18 条，也不留"新增图标须同步加清单"的维护约定——
    // 那会是一条从前提上就不成立的隐式规则。
    //
    // 边界（别把这条推过头）：barrel 进 include 断掉的是"**现有图标**在首轮预构建被漏掉"，
    // 不是"图标再也不会触发预构建"。在保留 barrel 的配置下放一个缓存里没有的新图标
    // （`Settings`）实测仍打出 `new dependencies optimized: @mui/icons-material/Settings`。
    // 即新增图标退化成"新增任意依赖"这一类一次性风险，与本文件清单无关。
    //
    // 运行期重优化的代价不是"慢一下"：它要清空 `deps_temp_*`（一次 275 个文件），而若干
    // 环境会给 `NODE_OPTIONS` 注入批量删除护栏，目录清理被拒后进程直接退出——dev server
    // 无声消失、页面 000。实跑复现：放一个 import 全仓未引用图标（`Settings`）的模块，
    // 请求它 → 出现 `deps_temp_*` → 再请求 → `✨ new dependencies optimized: …`。
    // 触发条件不是"某几个特定包"，而是**首次 resolve 一个不在缓存里的依赖**这个机制本身；
    // 真打出 `new dependencies optimized: <包名>` 时，按那条日志逐个补进来。
    include: [
      'react',
      'react-dom',
      'react-dom/client',
      'react/jsx-runtime',
      'react/jsx-dev-runtime',
      'react-router-dom',
      '@mui/material',
      '@mui/material/styles',
      '@mui/icons-material',
      '@emotion/react',
      '@emotion/styled',
      'recharts',
      '@tanstack/react-query',
      'axios',
      'dayjs',
      'notistack',
      'zustand',
    ],
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: 'localhost',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: false,
        // SSE 必须逐块透传，不能被代理缓冲
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            if (proxyRes.headers['content-type']?.includes('text/event-stream')) {
              proxyRes.headers['cache-control'] = 'no-cache';
            }
          });
        },
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          mui: ['@mui/material', '@mui/icons-material'],
          charts: ['recharts'],
        },
      },
    },
  },
});
