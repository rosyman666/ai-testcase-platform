/**
 * 空态 / 加载态 / 错误态三合一。T12 验收② 要求所有异步请求有这三态。
 */

import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import CircularProgress from '@mui/material/CircularProgress';
import type { ReactNode } from 'react';

/** EmptyState 属性。 */
export interface EmptyStateProps {
  /** 状态类型。 */
  variant: 'loading' | 'error' | 'empty';
  /** 主文案。 */
  title: string;
  /** 补充说明（可选）。 */
  description?: string;
  /** 操作按钮（如"重试"、"去创建"）。 */
  action?: ReactNode;
}

/**
 * 空态 / 加载态 / 错误态。
 *
 * @param props 属性
 * @returns 状态组件
 */
export default function EmptyState({ variant, title, description, action }: EmptyStateProps): JSX.Element {
  return (
    <Box className="flex flex-col items-center justify-center py-12">
      {variant === 'loading' && <CircularProgress size={28} className="mb-4" />}
      <Typography variant="h6" color="text.secondary" gutterBottom>
        {title}
      </Typography>
      {description && (
        <Typography variant="body2" color="text.secondary" className="max-w-md text-center">
          {description}
        </Typography>
      )}
      {action && <Box className="mt-4">{action}</Box>}
    </Box>
  );
}
