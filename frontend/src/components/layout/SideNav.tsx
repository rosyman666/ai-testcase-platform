/**
 * 左侧导航栏。
 *
 * 两种形态共用同一份 DOM 结构，只按断点切换 `variant`：
 * - `>= 900px`（MUI `md`）：`permanent`，常驻占位（`flexShrink: 0` 必须加，否则 flex 容器会把它压窄）；
 * - `< 900px`：`temporary`，由 AppShell 的 `open` / `onClose` 控制抽屉开合。
 */

import { Link as RouterLink, useLocation } from 'react-router-dom';
import Box from '@mui/material/Box';
import Divider from '@mui/material/Divider';
import Drawer from '@mui/material/Drawer';
import List from '@mui/material/List';
import ListItemButton from '@mui/material/ListItemButton';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import useMediaQuery from '@mui/material/useMediaQuery';
import { matchNavItem, NAV_ITEMS } from '@/constants/nav';

/** 侧栏宽度（px）。AppShell 与本文档宽度保持一致，避免出现 1px 级抖动。 */
export const SIDE_NAV_WIDTH = 240;

/** SideNav 属性。 */
export interface SideNavProps {
  /** 移动端抽屉是否展开（桌面端忽略）。 */
  open: boolean;
  /** 请求关闭抽屉（移动端点导航项、点遮罩、按 Esc 都会触发）。 */
  onClose: () => void;
}

/**
 * 左侧导航栏。
 *
 * @param props 属性
 * @returns 侧栏
 */
export default function SideNav({ open, onClose }: SideNavProps): JSX.Element {
  const theme = useTheme();
  // theme.breakpoints.up('md') 即 >= 900px，与 theme 默认断点一致
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'));
  const { pathname } = useLocation();
  const active = matchNavItem(pathname);

  return (
    <Drawer
      variant={isDesktop ? 'permanent' : 'temporary'}
      // permanent 形态下 MUI 不读 open，这里显式传 true，避免窗口从窄变宽时
      // 残留 open=false 导致首次渲染一瞬间不可见
      open={isDesktop ? true : open}
      onClose={onClose}
      // 移动端保持挂载：抽屉里是纯静态导航，keepMounted 能让第二次打开无重建开销
      ModalProps={{ keepMounted: true }}
      sx={{
        // flexShrink: 0 —— 不加这句，父级 flex 容器会在空间不足时把 240px 的侧栏压窄
        flexShrink: 0,
        width: isDesktop ? SIDE_NAV_WIDTH : undefined,
        '& .MuiDrawer-paper': {
          width: SIDE_NAV_WIDTH,
          boxSizing: 'border-box',
          borderRight: '1px solid',
          borderColor: 'divider',
        },
      }}
    >
      {/* 品牌区 */}
      <Box className="px-4 py-4">
        <Typography variant="h6" component="div">
          AI Testcase Platform
        </Typography>
        <Typography variant="caption" color="text.secondary">
          测试用例生成与评审
        </Typography>
      </Box>
      <Divider />

      <List component="nav" aria-label="主导航" className="py-2">
        {NAV_ITEMS.map((item) => {
          const selected = active?.key === item.key;
          return (
            <ListItemButton
              key={item.key}
              component={RouterLink}
              to={item.path}
              selected={selected}
              onClick={onClose}
              sx={{
                borderRadius: 1,
                mx: 1,
                mb: 0.5,
                // 默认选中色是 primary 的 8% 透明底，在 background.default(#f6f8fb) 上几乎看不出颜色差，
                // 所以选中态直接用主色实底 + 反白文字，保证一眼能认出当前模块
                '&.Mui-selected': {
                  backgroundColor: 'primary.main',
                  color: 'primary.contrastText',
                  '&:hover': { backgroundColor: 'primary.dark' },
                },
              }}
            >
              {/* color: inherit —— 让图标跟随 ListItemButton 的选中态反白，否则选中时图标仍是深灰色 */}
              <ListItemIcon sx={{ minWidth: 36, color: 'inherit' }}>
                <item.icon fontSize="small" />
              </ListItemIcon>
              <ListItemText
                primary={item.label}
                primaryTypographyProps={{ variant: 'body2', fontWeight: 600 }}
              />
            </ListItemButton>
          );
        })}
      </List>
    </Drawer>
  );
}
