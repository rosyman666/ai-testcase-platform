/**
 * 页面顶部标题栏。4 个页面都是"标题 + 副标题 + 右侧操作按钮"。
 */

import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import type { ReactNode } from 'react';

/** PageHeader 属性。 */
export interface PageHeaderProps {
  /** 主标题。 */
  title: string;
  /** 副标题（可选）。 */
  subtitle?: string;
  /** 右侧操作区（按钮等）。 */
  actions?: ReactNode;
}

/**
 * 页面顶部标题栏。
 *
 * @param props 属性
 * @returns 标题栏
 */
export default function PageHeader({ title, subtitle, actions }: PageHeaderProps): JSX.Element {
  return (
    <Box className="mb-4 flex items-start justify-between">
      <Box>
        <Typography variant="h5" component="h1">
          {title}
        </Typography>
        {subtitle && (
          <Typography variant="body2" color="text.secondary" className="mt-1">
            {subtitle}
          </Typography>
        )}
      </Box>
      {actions && <Box className="flex items-center gap-2">{actions}</Box>}
    </Box>
  );
}
