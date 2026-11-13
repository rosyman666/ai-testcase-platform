/**
 * 顶部栏。
 *
 * 只做三件事：移动端汉堡按钮、当前模块标题、账号菜单（含退出登录）。
 * 页面级标题与操作按钮仍由各页面的 `PageHeader` 负责，避免两套标题体系打架。
 */

import { useState, type MouseEvent } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import AppBar from '@mui/material/AppBar';
import Avatar from '@mui/material/Avatar';
import Divider from '@mui/material/Divider';
import IconButton from '@mui/material/IconButton';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Toolbar from '@mui/material/Toolbar';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import useMediaQuery from '@mui/material/useMediaQuery';
import MenuRoundedIcon from '@mui/icons-material/MenuRounded';
import { matchNavItem } from '@/constants/nav';
import { useAuthStore } from '@/store/authStore';

/** TopBar 属性。 */
export interface TopBarProps {
  /** 请求打开移动端导航抽屉。 */
  onOpenNav: () => void;
}

/**
 * 取头像上显示的首字。
 *
 * 用 `Array.from` 而不是 `charAt(0)`：昵称首字可能是 emoji（占两个 UTF-16 码元），
 * `charAt` 会切出半个代理对，渲染成乱码方块。
 *
 * @param displayName 显示名（可能为 null）
 * @param username 登录名（可能为 null）
 * @returns 首字；两者都为空时返回 `?`
 */
function resolveAvatarLabel(displayName: string | null, username: string | null): string {
  const source = (displayName ?? '').trim() || (username ?? '').trim();
  if (!source) {
    return '?';
  }
  const first = Array.from(source)[0];
  return first ? first.toUpperCase() : '?';
}

/**
 * 顶部栏。
 *
 * @param props 属性
 * @returns 顶部栏
 */
export default function TopBar({ onOpenNav }: TopBarProps): JSX.Element {
  const theme = useTheme();
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'));
  const { pathname } = useLocation();
  const navigate = useNavigate();

  const username = useAuthStore((state) => state.username);
  const displayName = useAuthStore((state) => state.displayName);
  const expiresAt = useAuthStore((state) => state.expiresAt);

  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null);
  const menuOpen = menuAnchor !== null;

  // 命中导航项就用它的 label；命中不了（例如以后新增页面还没登记导航）退回「控制台」，
  // 不留空白标题
  const moduleTitle = matchNavItem(pathname)?.label ?? '控制台';

  const handleOpenMenu = (event: MouseEvent<HTMLElement>): void => {
    setMenuAnchor(event.currentTarget);
  };

  const handleCloseMenu = (): void => {
    setMenuAnchor(null);
  };

  const handleLogout = (): void => {
    setMenuAnchor(null);
    // 用 getState() 而不是订阅来的 clear：这里只需要触发一次动作，不需要引起重渲染
    useAuthStore.getState().clear();
    navigate('/login', { replace: true });
  };

  return (
    <AppBar
      position="sticky"
      elevation={0}
      color="inherit"
      sx={{
        borderBottom: '1px solid',
        borderColor: 'divider',
        backgroundColor: 'background.paper',
      }}
    >
      <Toolbar variant="dense">
        {!isDesktop && (
          <IconButton
            edge="start"
            color="inherit"
            aria-label="打开导航"
            onClick={onOpenNav}
            className="mr-1"
          >
            <MenuRoundedIcon />
          </IconButton>
        )}

        <Typography variant="h6" component="h1" className="flex-1 truncate">
          {moduleTitle}
        </Typography>

        <IconButton
          onClick={handleOpenMenu}
          size="small"
          aria-label="账号菜单"
          aria-controls={menuOpen ? 'account-menu' : undefined}
          aria-haspopup="true"
          aria-expanded={menuOpen ? 'true' : undefined}
        >
          <Avatar sx={{ width: 28, height: 28, fontSize: '0.8125rem' }}>
            {resolveAvatarLabel(displayName, username)}
          </Avatar>
        </IconButton>

        <Menu
          id="account-menu"
          anchorEl={menuAnchor}
          open={menuOpen}
          onClose={handleCloseMenu}
          anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
          transformOrigin={{ vertical: 'top', horizontal: 'right' }}
          // 与头像右对齐时留 4px 缝，避免菜单边线贴住 AppBar 的下边框
          slotProps={{ paper: { sx: { mt: 0.5, minWidth: 200 } } }}
        >
          {/* 只读信息项用 disabled：MUI 会让它不参与键盘焦点，也点不动，
              比自绘一行文字更省事，但需要显式把文字颜色提回来，否则 0.38 透明度下看不清 */}
          <MenuItem disabled>
            <Typography variant="body2">账号：{username ?? '-'}</Typography>
          </MenuItem>
          <MenuItem disabled sx={{ maxWidth: 280 }}>
            <Typography
              variant="body2"
              sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
            >
              过期时间：{expiresAt ?? '-'}
            </Typography>
          </MenuItem>
          <Divider />
          <MenuItem onClick={handleLogout}>
            <Typography variant="body2">退出登录</Typography>
          </MenuItem>
        </Menu>
      </Toolbar>
    </AppBar>
  );
}
