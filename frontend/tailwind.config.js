/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  // ==========================================================================
  // 与 MUI 共存的关键：必须关闭 Tailwind 的 preflight
  //   preflight 会重置 html/body 的 margin、button 的 background/border 等基础样式，
  //   而 MUI 组件（Button/TextField/Paper）依赖浏览器默认样式与自身 CSS 变量的组合，
  //   两者叠加后会出现「按钮变透明背景、边框消失、字号变小」这类诡异现象。
  //   保留 MUI 的 CssBaseline 作为唯一的样式重置来源。
  // ==========================================================================
  corePlugins: {
    preflight: false,
  },
  // Tailwind 的样式层级必须低于 MUI 的 emotion 样式，否则 utility class 会覆盖 MUI 组件内部样式
  important: false,
  theme: {
    extend: {
      colors: {
        brand: {
          50: '#eff6ff',
          100: '#dbeafe',
          500: '#3b82f6',
          600: '#2563eb',
          700: '#1d4ed8',
        },
      },
      fontFamily: {
        sans: [
          'Inter',
          '-apple-system',
          'BlinkMacSystemFont',
          'PingFang SC',
          'Hiragino Sans GB',
          'Microsoft YaHei',
          'Helvetica Neue',
          'Arial',
          'sans-serif',
        ],
        mono: ['JetBrains Mono', 'SFMono-Regular', 'Consolas', 'Menlo', 'monospace'],
      },
      spacing: {
        // 与 MUI 8px 栅格对齐，混用时布局不乱
        18: '4.5rem',
      },
    },
  },
  plugins: [],
};
