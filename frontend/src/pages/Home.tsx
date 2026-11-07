import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
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
import { useAuthStore } from '@/store/authStore';
import type { ApiEnvelope } from '@/types/common';

/** `GET /api/health` 的业务数据。 */
interface HealthData {
  status: string;
  dbOk: boolean;
  provider: string;
  model: string;
  time: string;
}

/** 后端地址（缺省走 dev proxy）。 */
const API_BASE = import.meta.env.VITE_API_BASE ?? '/api';

/**
 * 占位首页：T02 的"鉴权闭环 + 基础设施自检"页面，T12 会用真正的 AppShell + 概览页替换它。
 *
 * 保留它是为了把 T01 的自检能力（health、traceId）与 T02 的新能力（登录态、token、
 * Long→String）放在同一个可视面上，QA 一眼就能验完。
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
    queryFn: async () => {
      const response = await axios.get<ApiEnvelope<HealthData>>(`${API_BASE}/health`);
      return response.data;
    },
  });

  const health = healthQuery.data?.data;
  const me = meQuery.data;

  const handleLogout = (): void => {
    clear();
    navigate('/login', { replace: true });
  };

  return (
    <Box className="min-h-screen w-full px-6 py-8" sx={{ backgroundColor: 'background.default' }}>
      <Box className="mx-auto max-w-4xl">
        <Stack direction="row" alignItems="center" justifyContent="space-between" className="mb-6">
          <Box>
            <Typography variant="h1" component="h1">
              登录成功
            </Typography>
            <Typography variant="body2" color="text.secondary">
              T02 基础设施与鉴权闭环自检页（T12 会替换为真正的控制台）
            </Typography>
          </Box>
          <Button variant="outlined" startIcon={<LogoutRoundedIcon />} onClick={handleLogout}>
            退出登录
          </Button>
        </Stack>

        <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
          {/* 登录态 */}
          <Paper elevation={0} className="p-5" sx={{ border: '1px solid', borderColor: 'divider' }}>
            <Typography variant="h5" component="h2" className="mb-3">
              登录态（/api/auth/me）
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

          {/* 健康检查 */}
          <Paper elevation={0} className="p-5" sx={{ border: '1px solid', borderColor: 'divider' }}>
            <Stack direction="row" alignItems="center" justifyContent="space-between" className="mb-3">
              <Typography variant="h5" component="h2">
                后端连通性（/api/health）
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
                  label={healthQuery.data?.code === 0 ? 'code = 0' : 'code != 0'}
                  color={healthQuery.data?.code === 0 ? 'success' : 'error'}
                  size="small"
                />
                <Chip label={`status=${health?.status ?? '-'}`} size="small" variant="outlined" />
                <Chip label={`dbOk=${String(health?.dbOk ?? false)}`} size="small" variant="outlined" />
                <Chip label={`provider=${health?.provider ?? '-'}`} size="small" variant="outlined" />
                <Chip label={`traceId=${healthQuery.data?.traceId ?? '-'}`} size="small" variant="outlined" />
              </Stack>
            )}
          </Paper>
        </div>

        <Paper elevation={0} className="mt-6 p-5" sx={{ border: '1px solid', borderColor: 'divider' }}>
          <Typography variant="h5" component="h2" className="mb-2">
            下一步
          </Typography>
          <Typography variant="body2" color="text.secondary">
            T03 起会有文档接入、测试点评审、用例生成等业务页面；本页仅用于验证
            「鉴权拦截 → 登录 → token 注入 → 401 自动跳登录」这条链路已闭环。
          </Typography>
        </Paper>
      </Box>
    </Box>
  );
}
