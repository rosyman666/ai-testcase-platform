import { useQuery } from '@tanstack/react-query';
import axios from 'axios';
import Divider from '@mui/material/Divider';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded';
import ScienceRoundedIcon from '@mui/icons-material/ScienceRounded';

/** 后端统一响应体（与架构文档 §8.1 一致）。 */
interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T;
  traceId: string;
}

/** GET /api/health 的业务数据。 */
interface HealthData {
  status: string;
  dbOk: boolean;
  provider: string;
  model: string;
  time: string;
}

/** 后端地址：缺省走 vite dev proxy，生产环境同源反代。 */
const API_BASE = import.meta.env.VITE_API_BASE ?? '/api';

/**
 * T01 骨架自检页。
 *
 * <p>这一页不是业务页面，它的唯一职责是**目视验证工程骨架**：</p>
 * <ol>
 *   <li>MUI 组件样式完好（Button 有实心背景、无被 Tailwind preflight 重置）；</li>
 *   <li>Tailwind 原子类生效（渐变卡片、网格布局、间距类）；</li>
 *   <li>dev proxy 生效（能拿到后端 /api/health 的 code=0 与 traceId）。</li>
 * </ol>
 * <p>T02 起会把本页替换为路由出口（AppShell + 登录页）。</p>
 */
export default function App(): JSX.Element {
  const healthQuery = useQuery<ApiEnvelope<HealthData>>({
    queryKey: ['health'],
    queryFn: async () => {
      const response = await axios.get<ApiEnvelope<HealthData>>(`${API_BASE}/health`);
      return response.data;
    },
  });

  const health = healthQuery.data?.data;
  const isOk = healthQuery.data?.code === 0;

  return (
    <Box className="min-h-screen w-full px-6 py-8" sx={{ backgroundColor: 'background.default' }}>
      <Box className="mx-auto max-w-4xl">
        {/* ---------- 头部 ---------- */}
        <Stack direction="row" alignItems="center" spacing={1.5} className="mb-1">
          <ScienceRoundedIcon color="primary" fontSize="large" />
          <Typography variant="h1" component="h1">
            AI 测试用例生成与评审平台
          </Typography>
        </Stack>
        <Typography variant="body2" color="text.secondary" className="mb-6">
          T01 工程骨架自检页 · 用于验证 MUI 与 Tailwind 共存、以及前后端连通
        </Typography>

        <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
          {/* ---------- 面板一：MUI 组件 ---------- */}
          <Paper elevation={0} className="p-5" sx={{ border: '1px solid', borderColor: 'divider' }}>
            <Typography variant="h5" component="h2" className="mb-3">
              MUI 组件
            </Typography>
            <Typography variant="body2" color="text.secondary" className="mb-4">
              若下方按钮是**实心蓝底白字、圆角 8px**，说明 Tailwind 的 preflight 已正确关闭，
              没有重置 MUI 的 <code>button</code> 基础样式。
            </Typography>
            <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
              <Button variant="contained" startIcon={<ScienceRoundedIcon />}>
                实心按钮（contained）
              </Button>
              <Button variant="outlined">描边按钮（outlined）</Button>
              <Tooltip title="状态色板与 StatusChip 共用">
                <Chip label="SUCCESS" color="success" size="small" />
              </Tooltip>
              <Chip label="RUNNING" color="info" size="small" />
              <Chip label="FAILED" color="error" size="small" />
            </Stack>
            <Divider className="my-4" />
            <Typography variant="subtitle2" gutterBottom>
              主题要点
            </Typography>
            <Typography variant="body2" color="text.secondary">
              中文字体栈（PingFang SC / 微软雅黑）、紧凑密度（表格 8px 单元格内边距）、
              Chip 圆角 6px —— 与 <code>theme.ts</code> 一致即生效。
            </Typography>
          </Paper>

          {/* ---------- 面板二：Tailwind 原子类 ---------- */}
          <Paper elevation={0} className="p-5" sx={{ border: '1px solid', borderColor: 'divider' }}>
            <Typography variant="h5" component="h2" className="mb-3">
              Tailwind 原子类
            </Typography>
            <Typography variant="body2" color="text.secondary" className="mb-4">
              下面三个方块由 <code>grid grid-cols-3 gap-2</code> 与渐变类控制，
              若渲染成横向等宽三列即说明 Tailwind 编译链正常。
            </Typography>
            <div className="grid grid-cols-3 gap-2">
              <div className="rounded-lg bg-gradient-to-br from-blue-500 to-blue-700 p-3 text-center text-xs font-semibold text-white shadow-sm">
                grid
              </div>
              <div className="rounded-lg bg-gradient-to-br from-violet-500 to-violet-700 p-3 text-center text-xs font-semibold text-white shadow-sm">
                gradient
              </div>
              <div className="rounded-lg bg-gradient-to-br from-emerald-500 to-emerald-700 p-3 text-center text-xs font-semibold text-white shadow-sm">
                utility
              </div>
            </div>
            <div className="mt-4 rounded-xl border border-dashed border-slate-300 bg-slate-50 px-4 py-3 text-xs leading-relaxed text-slate-600">
              生产构建后请确认 <code>dist/assets/*.css</code> 中**不含** Tailwind preflight 的
              <code>*,::before,::after&#123;box-sizing:border-box</code> 重置块。
            </div>
          </Paper>
        </div>

        {/* ---------- 面板三：后端连通性 ---------- */}
        <Paper elevation={0} className="mt-6 p-5" sx={{ border: '1px solid', borderColor: 'divider' }}>
          <Stack direction="row" alignItems="center" justifyContent="space-between" className="mb-3">
            <Typography variant="h5" component="h2">
              后端连通性（/api/health，走 vite proxy）
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

          {healthQuery.isLoading ? (
            <Typography variant="body2" color="text.secondary">
              正在请求后端…
            </Typography>
          ) : healthQuery.isError ? (
            <Typography variant="body2" color="error">
              请求失败：请确认后端已在 8080 端口启动（<code>cd backend &amp;&amp; mvn spring-boot:run</code>）。
            </Typography>
          ) : (
            <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
              <Chip
                label={isOk ? 'code = 0 连接正常' : `code = ${healthQuery.data?.code ?? '?'}`}
                color={isOk ? 'success' : 'error'}
                size="small"
              />
              <Chip label={`status=${health?.status ?? '-'}`} size="small" variant="outlined" />
              <Chip label={`dbOk=${String(health?.dbOk ?? false)}`} size="small" variant="outlined" />
              <Chip label={`provider=${health?.provider ?? '-'}`} size="small" variant="outlined" />
              <Chip label={`model=${health?.model ?? '-'}`} size="small" variant="outlined" />
              <Tooltip title="与响应头 X-Trace-Id 一致，排障时可据此查日志">
                <Chip label={`traceId=${healthQuery.data?.traceId ?? '-'}`} size="small" variant="outlined" />
              </Tooltip>
            </Stack>
          )}
        </Paper>
      </Box>
    </Box>
  );
}
