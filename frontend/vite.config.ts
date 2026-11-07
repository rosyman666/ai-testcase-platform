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
