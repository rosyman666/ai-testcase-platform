import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Divider from '@mui/material/Divider';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import LogoutRoundedIcon from '@mui/icons-material/LogoutRounded';
import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded';
import { fetchCurrentUser } from '@/api/auth';
import { fetchHealth } from '@/api/system';
import { useAuthStore } from '@/store/authStore';

/**
 * 系统概览页：展示当前登录账号与后端/数据库连通性。
 *
 * 导航已由 `AppShell` 的侧栏接管，本页不再提供业务入口按钮——
 * 页面里重复一份入口，将来加模块就会漏改一处。
 *
 * 外层只留 `p-6`，**不要加 `min-h-screen`**：本页现在位于 AppShell 的
 * `overflow-y-auto` 容器内，再撑一次视口高度会出现"外层滚动区 + 页面自身"双滚动条。
 *
 * 数据获取一律通过 `@/api/*`（见 §8.5），组件里不出现 axios、不拼 baseURL——
 * 否则会绕过 `http.ts` 的 401 处理与 traceId 归一化。
 */
export default function Home(): JSX.Element {
  const navigate = useNavigate();
  const clear = useAuthStore((state) => state.clear);
  const username = useAuthStore((state) => state.username);
  const displayName = useAuthStore((state) => state.displayName);
  const expiresAt = useAuthStore((state) => state.expiresAt);

  const meQuery = useQuery({
    queryKey: ['auth-me'],
    queryFn: fetchCurrentUser,
  });

  const healthQuery = useQuery({
    queryKey: ['health'],
    queryFn: fetchHealth,
  });

  const health = healthQuery.data;
  const me = meQuery.data;

  const handleLogout = (): void => {
    clear();
    navigate('/login', { replace: true });
  };

  return (
    <Box className="p-6" sx={{ backgroundColor: 'background.default' }}>
      <Box className="mx-auto max-w-4xl">
        <Stack direction="row" alignItems="center" justifyContent="space-between" className="mb-6">
          <Box>
            <Typography variant="h1" component="h1">
              系统概览
            </Typography>
            <Typography variant="body2" color="text.secondary">
              平台运行状态与当前登录信息
            </Typography>
          </Box>
          <Button variant="outlined" startIcon={<LogoutRoundedIcon />} onClick={handleLogout}>
            退出登录
          </Button>
        </Stack>

        <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
          {/* 当前账号 */}
          <Paper elevation={0} className="p-5" sx={{ border: '1px solid', borderColor: 'divider' }}>
            <Typography variant="h5" component="h2" className="mb-3">
              当前账号
            </Typography>
            {meQuery.isLoading && <Typography variant="body2">加载中…</Typography>}
            {meQuery.isError && (
              <Typography variant="body2" color="error">
                获取当前用户失败（token 可能已失效）
              </Typography>
            )}
            {me && (
              <Stack spacing={1.5}>
                <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                  <Chip label={`displayName=${me.displayName}`} size="small" />
                  <Chip label={`username=${me.username}`} size="small" variant="outlined" />
                  <Chip label={`enabled=${String(me.enabled)}`} size="small" variant="outlined" />
                </Stack>
                <Divider />
                <Typography variant="body2" color="text.secondary">
                  Long 型 ID 序列化自检：<code>typeof id === &apos;{typeof me.id}&apos;</code>，
                  值 <code>{me.id}</code>
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  本地缓存用户名：{username ?? '-'} / {displayName ?? '-'}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  token 过期时间：{expiresAt ?? '-'}
                </Typography>
              </Stack>
            )}
          </Paper>

          {/* 服务状态 */}
          <Paper elevation={0} className="p-5" sx={{ border: '1px solid', borderColor: 'divider' }}>
            <Stack direction="row" alignItems="center" justifyContent="space-between" className="mb-3">
              <Typography variant="h5" component="h2">
                服务状态
              </Typography>
              <Button
                size="small"
                variant="text"
                startIcon={<RefreshRoundedIcon />}
                onClick={() => void healthQuery.refetch()}
                disabled={healthQuery.isFetching}
              >
                重新检测
              </Button>
            </Stack>
            {healthQuery.isError ? (
              <Typography variant="body2" color="error">
                后端未响应
              </Typography>
            ) : (
              <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                <Chip
                  label={health ? '连接正常' : '检测中…'}
                  color={health ? 'success' : 'default'}
                  size="small"
                />
                <Chip label={`status=${health?.status ?? '-'}`} size="small" variant="outlined" />
                <Chip label={`dbOk=${String(health?.dbOk ?? false)}`} size="small" variant="outlined" />
                <Chip label={`provider=${health?.provider ?? '-'}`} size="small" variant="outlined" />
                <Chip label={`time=${health?.time ?? '-'}`} size="small" variant="outlined" />
              </Stack>
            )}
          </Paper>
        </div>
      </Box>
    </Box>
  );
}
