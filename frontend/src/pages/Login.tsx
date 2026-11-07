import { useState, type FormEvent } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import LockRoundedIcon from '@mui/icons-material/LockRounded';
import { fetchPublicConfig, login } from '@/api/auth';
import { ApiError } from '@/api/http';
import { useAuthStore } from '@/store/authStore';
import { toast } from '@/utils/toast';

/**
 * 登录页。
 *
 * 这一页同时是"鉴权白名单"的活体验证：它在**未登录**状态下调用
 * `GET /api/config/public` 拿 provider 标识，如果白名单配错，这里会 401 → 死锁。
 */
export default function Login(): JSX.Element {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const setAuth = useAuthStore((state) => state.setAuth);

  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const [formError, setFormError] = useState('');

  // 未登录也要能拿到（白名单接口）
  const configQuery = useQuery({
    queryKey: ['public-config'],
    queryFn: fetchPublicConfig,
    staleTime: 5 * 60_000,
  });

  const loginMutation = useMutation({
    mutationFn: login,
    onSuccess: (data) => {
      setAuth({
        token: data.token,
        userId: data.userId,
        username: data.username,
        displayName: data.displayName,
        expiresAt: data.expiresAt,
      });
      toast(`欢迎回来，${data.displayName}`, 'success');
      const redirect = searchParams.get('redirect');
      navigate(redirect && redirect.startsWith('/') ? redirect : '/', { replace: true });
    },
    onError: (error: Error) => {
      setFormError(
        error instanceof ApiError
          ? error.traceId
            ? `${error.message}（traceId: ${error.traceId}）`
            : error.message
          : '登录失败，请稍后重试',
      );
    },
  });

  const handleSubmit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    setFormError('');
    if (!username.trim() || !password) {
      setFormError('请输入用户名与密码');
      return;
    }
    loginMutation.mutate({ username: username.trim(), password });
  };

  const provider = configQuery.data?.app.aiProvider ?? '-';

  return (
    <Box
      className="flex min-h-screen w-full items-center justify-center px-4"
      sx={{ backgroundColor: 'background.default' }}
    >
      <Box className="w-full max-w-md">
        <Stack direction="row" alignItems="center" spacing={1.5} className="mb-6">
          <LockRoundedIcon color="primary" fontSize="large" />
          <Box>
            <Typography variant="h2" component="h1">
              AI 测试用例平台
            </Typography>
            <Typography variant="body2" color="text.secondary">
              文档解析 · 测试点抽取 · 用例生成 · 人工评审 · 指标看板
            </Typography>
          </Box>
        </Stack>

        <Paper elevation={0} className="p-6" sx={{ border: '1px solid', borderColor: 'divider' }}>
          <form onSubmit={handleSubmit} noValidate>
            <Stack spacing={2.5}>
              <TextField
                label="用户名"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                autoComplete="username"
                autoFocus
                fullWidth
              />
              <TextField
                label="密码"
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoComplete="current-password"
                fullWidth
              />
              {formError && <Alert severity="error">{formError}</Alert>}
              <Button
                type="submit"
                variant="contained"
                size="large"
                disabled={loginMutation.isPending}
                startIcon={loginMutation.isPending ? <CircularProgress size={16} color="inherit" /> : undefined}
                fullWidth
              >
                {loginMutation.isPending ? '登录中…' : '登录'}
              </Button>
            </Stack>
          </form>

          <Box className="mt-5 flex flex-wrap items-center gap-2">
            <Typography variant="caption" color="text.secondary">
              当前模型模式：
            </Typography>
            <Chip label={provider} size="small" variant="outlined" />
            {configQuery.isError && (
              <Chip label="公开配置获取失败（白名单或后端未启动）" size="small" color="error" variant="outlined" />
            )}
          </Box>
        </Paper>

        <Typography variant="caption" color="text.secondary" className="mt-4 block text-center">
          本地默认账号 admin / admin123（仅开发环境，生产必须修改）
        </Typography>
      </Box>
    </Box>
  );
}
