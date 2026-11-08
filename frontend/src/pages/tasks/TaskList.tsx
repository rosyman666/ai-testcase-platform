/**
 * 任务列表页。列表 + 状态筛选 + 跳详情。
 *
 * **不做 5 秒轮询兜底**（§7.6）：react-query 默认策略（staleTime: 30s, retry: 0）+ 手动刷新按钮即可。
 */

import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import TablePagination from '@mui/material/TablePagination';
import Paper from '@mui/material/Paper';
import MenuItem from '@mui/material/MenuItem';
import TextField from '@mui/material/TextField';
import IconButton from '@mui/material/IconButton';
import RefreshIcon from '@mui/icons-material/Refresh';
import AddIcon from '@mui/icons-material/Add';
import { useQuery } from '@tanstack/react-query';
import PageHeader from '@/components/common/PageHeader';
import StatusChip from '@/components/common/StatusChip';
import EmptyState from '@/components/common/EmptyState';
import TaskCreateDialog from '@/pages/tasks/TaskCreateDialog';
import { listTasks } from '@/api/task';
import { usePageQuery } from '@/hooks/usePageQuery';
import { TASK_STATUS } from '@/constants/status';

/**
 * 任务列表页。
 *
 * @returns 页面
 */
export default function TaskList(): JSX.Element {
  const navigate = useNavigate();
  const [statusFilter, setStatusFilter] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const { page, size, setPage, setSize, query, resetPage } = usePageQuery();

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['tasks', query.page, query.size, statusFilter],
    queryFn: () => listTasks({ ...query, status: statusFilter || undefined }),
  });

  const tasks = data?.records ?? [];
  const total = data?.total ?? 0;

  return (
    <Box className="mx-auto max-w-6xl p-6">
      <PageHeader
        title="任务列表"
        subtitle="查看用例生成任务的执行状态"
        actions={
          <>
            <IconButton onClick={() => refetch()} title="刷新">
              <RefreshIcon />
            </IconButton>
            <Button
              variant="contained"
              startIcon={<AddIcon />}
              onClick={() => setCreateOpen(true)}
            >
              新建任务
            </Button>
          </>
        }
      />

      {/* 状态筛选 */}
      <Box className="mb-4 flex items-center gap-3">
        <TextField
          select
          label="状态"
          value={statusFilter}
          onChange={(e) => {
            setStatusFilter(e.target.value);
            resetPage();
          }}
          size="small"
          sx={{ minWidth: 140 }}
        >
          <MenuItem value="">全部</MenuItem>
          {Object.entries(TASK_STATUS).map(([code, meta]) => (
            <MenuItem key={code} value={code}>
              {meta.label}
            </MenuItem>
          ))}
        </TextField>
      </Box>

      {/* 列表 */}
      {isLoading ? (
        <EmptyState variant="loading" title="加载中…" />
      ) : error ? (
        <EmptyState
          variant="error"
          title="加载失败"
          description="请检查网络或后端服务是否已启动"
          action={<Button onClick={() => refetch()}>重试</Button>}
        />
      ) : tasks.length === 0 ? (
        <EmptyState
          variant="empty"
          title="暂无任务"
          description="点击右上角「新建任务」开始创建"
          action={<Button variant="contained" onClick={() => setCreateOpen(true)}>新建任务</Button>}
        />
      ) : (
        <>
          <TableContainer component={Paper}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>任务编号</TableCell>
                  <TableCell>场景</TableCell>
                  <TableCell>状态</TableCell>
                  <TableCell>进度</TableCell>
                  <TableCell>产出</TableCell>
                  <TableCell>创建时间</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {tasks.map((task) => (
                  <TableRow
                    key={task.taskId}
                    hover
                    className="cursor-pointer"
                    onClick={() => navigate(`/tasks/${task.taskId}`)}
                  >
                    <TableCell className="font-mono">{task.taskNo}</TableCell>
                    <TableCell>{task.scene}</TableCell>
                    <TableCell>
                      <StatusChip code={task.status} />
                    </TableCell>
                    <TableCell>{task.done}/{task.total}</TableCell>
                    <TableCell>{task.produced} 条</TableCell>
                    <TableCell>
                      {task.createdAt
                        ? new Date(task.createdAt).toLocaleString('zh-CN')
                        : '-'}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>

          <TablePagination
            component="div"
            count={total}
            page={page}
            onPageChange={(_, p) => setPage(p)}
            rowsPerPage={size}
            onRowsPerPageChange={(e) => setSize(Number(e.target.value))}
            rowsPerPageOptions={[10, 20, 50]}
            labelRowsPerPage="每页"
            labelDisplayedRows={({ from, to, count }) => `${from}-${to} / ${count}`}
          />
        </>
      )}

      <TaskCreateDialog open={createOpen} onClose={() => setCreateOpen(false)} />
    </Box>
  );
}
