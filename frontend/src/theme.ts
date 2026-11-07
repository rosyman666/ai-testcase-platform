import { createTheme } from '@mui/material/styles';
import type { Theme, ThemeOptions } from '@mui/material/styles';

/**
 * 中文字体栈：优先系统字体，避免首屏字体抖动（不引外部 webfont）。
 * 与 tailwind.config.js 的 fontFamily.sans 保持完全一致，否则 MUI 组件与
 * Tailwind 容器的字号/字距会出现细微差异。
 */
export const FONT_FAMILY_SANS = [
  'Inter',
  '-apple-system',
  'BlinkMacSystemFont',
  'PingFang SC',
  'Hiragino Sans GB',
  'Microsoft YaHei',
  'Helvetica Neue',
  'Arial',
  'sans-serif',
].join(',');

/**
 * 状态色板：任务状态、用例状态、执行结果三类状态共用一个映射，
 * 避免各页面自己写颜色导致同状态不同色（StatusChip 组件在 T12 消费它）。
 */
export const STATUS_COLORS = {
  PENDING: 'default',
  RUNNING: 'info',
  SUCCESS: 'success',
  PARTIAL: 'warning',
  FAILED: 'error',
  CANCELED: 'default',
  DRAFT: 'default',
  ACCEPTED: 'success',
  EDITED: 'info',
  REJECTED: 'error',
  PASSED: 'success',
  SKIPPED: 'default',
} as const;

/** 状态枚举 → MUI Chip color 的键类型。 */
export type StatusKey = keyof typeof STATUS_COLORS;

/** 主题配置：紧凑密度（信息密度优先，测试平台以表格为主）+ 中文友好的字体栈。 */
export const themeOptions: ThemeOptions = {
  palette: {
    mode: 'light',
    primary: { main: '#2563eb', light: '#3b82f6', dark: '#1d4ed8', contrastText: '#ffffff' },
    secondary: { main: '#7c3aed' },
    success: { main: '#16a34a' },
    warning: { main: '#d97706' },
    error: { main: '#dc2626' },
    info: { main: '#0284c7' },
    background: { default: '#f6f8fb', paper: '#ffffff' },
    divider: 'rgba(15, 23, 42, 0.08)',
    text: { primary: '#0f172a', secondary: '#475569' },
  },
  shape: { borderRadius: 8 },
  typography: {
    fontFamily: FONT_FAMILY_SANS,
    fontSize: 14,
    h1: { fontSize: '1.75rem', fontWeight: 700 },
    h2: { fontSize: '1.5rem', fontWeight: 700 },
    h3: { fontSize: '1.25rem', fontWeight: 600 },
    h4: { fontSize: '1.125rem', fontWeight: 600 },
    h5: { fontSize: '1rem', fontWeight: 600 },
    h6: { fontSize: '0.9375rem', fontWeight: 600 },
    button: { textTransform: 'none', fontWeight: 600 },
    subtitle2: { fontWeight: 600 },
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: { backgroundColor: '#f6f8fb' },
      },
    },
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: { textTransform: 'none', borderRadius: 8, fontWeight: 600 },
        sizeSmall: { paddingTop: 4, paddingBottom: 4 },
      },
    },
    MuiTextField: {
      defaultProps: { size: 'small', variant: 'outlined' },
    },
    MuiSelect: {
      defaultProps: { size: 'small' },
    },
    MuiTableCell: {
      styleOverrides: {
        root: { paddingTop: 8, paddingBottom: 8, borderColor: 'rgba(15, 23, 42, 0.06)' },
        head: { fontWeight: 600, backgroundColor: '#f8fafc' },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: { fontWeight: 600, borderRadius: 6 },
        sizeSmall: { height: 22, fontSize: '0.75rem' },
      },
    },
    MuiTooltip: {
      defaultProps: { arrow: true },
    },
  },
};

/** 全局唯一主题实例。 */
const theme: Theme = createTheme(themeOptions);

export default theme;
