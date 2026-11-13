/**
 * 任务详情页。顶部汇总 + 进度条 + item 明细表 + 取消/重试按钮。
 *
 * ⚠️ **渲染顺序必须"先 `GET /items` 拉全量，再叠 SSE 增量"**（§4.6）：
 * 终态后打开页面时 SSE 只有一帧 snapshot、明细一条都拿不到；
 * 反过来的后果是"页面先显示 3 条、200ms 后突然变成 40 条"。
 *
 * ⚠️ **顶部汇总只显示 done/total/success/failed/produced**（D-51）：
 * `GenTaskVO` 没有 tokens/cost，照文档写会渲染空值被误判成"后端没算成本"。
 */

import { useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Typography from '@mui/material/Typography';
import Paper from '@mui/material/Paper';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Chip from '@mui/material/Chip';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import { useQuery } from '@tanstack/react-query';
import PageHeader from '@/components/common/PageHeader';
import StatusChip from '@/components/common/StatusChip';
import EmptyState from '@/components/common/EmptyState';
import ProgressTimeline from '@/pages/tasks/ProgressTimeline';
import { getTask, listTaskItems, cancelTask } from '@/api/task';
import { useTaskStream } from '@/hooks/useTaskStream';
import { toast } from '@/utils/toast';
import type { GenTaskItemVO, ItemFramePayload } from '@/types/task';

/**
 * 任务详情页。
 *
 * @returns 页面
 */
export default function TaskDetail(): JSX.Element {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  // REST：任务基础信息 + 逐 item 明细（权威来源）
  const { data: task, isLoading: taskLoading, error: taskError, refetch: refetchTask } = useQuery({
    queryKey: ['task', id],
    queryFn: () => getTask(id!),
    enabled: !!id,
  });

  const { data: items, refetch: refetchItems } = useQuery({
    queryKey: ['task-items', id],
    queryFn: () => listTaskItems(id!),
    enabled: !!id,
  });

  // SSE：增量加速
  const stream = useTaskStream(id);

  // 合并：先 /items 全量，再叠 SSE 增量（SSE 的更新覆盖同 itemId 的行）
  const mergedItems = useMemo(() => {
    const base = new Map<string, GenTaskItemVO | ItemFramePayload>();
    for (const item of items ?? []) {
      base.set(item.id, item);
    }
    for (const item of stream.items) {
      const existing = base.get(item.itemId);
      if (existing) {
        // SSE 增量覆盖 status/latencyMs/errorCode 等（/items 可能是旧快照）
        base.set(item.itemId, { ...existing, ...item, id: item.itemId } as GenTaskItemVO);
      } else {
        base.set(item.itemId, item as unknown as GenTaskItemVO);
      }
    }
    return Array.from(base.values());
  }, [items, stream.items]);

  // 终态后 refetch /items 兜底（SSE 有损，done 后可能丢 item）
  const terminal = stream.terminal;
  if (terminal) {
    // 一次性 refetch（避免无限循环——用 useEffect 不太好控制，这里用事件式）
    // 实际上 react-query 的 staleTime 会在下次 mount 时自动拉，这里手动触发一次
    refetchItems();
    refetchTask();
  }

  const handleCancel = async (): Promise<void> => {
    try {
      await cancelTask(id!);
      toast('任务已取消', 'success');
      refetchTask();
    } catch {
      // http.ts 已统一 toast
    }
  };

  if (taskLoading) {
    return <EmptyState variant="loading" title="加载中…" />;
  }

  if (taskError || !task) {
    return (
      <EmptyState
        variant="error"
        title="任务不存在或加载失败"
        description={taskError ? '请检查网络或后端服务是否已启动' : '该任务可能已被删除'}
        action={
          // 两个动作都要给：只给「返回列表」的话，一次网络抖动人就得重新找回来路
          <Box className="flex gap-2">
            <Button onClick={() => void refetchTask()}>重试</Button>
            <Button onClick={() => navigate('/tasks')}>返回列表</Button>
          </Box>
        }
      />
    );
  }

  const isTerminal = ['SUCCESS', 'PARTIAL', 'FAILED', 'CANCELED'].includes(task.status);

  return (
    <Box className="mx-auto max-w-6xl p-6">
      <PageHeader
        title={`任务 ${task.taskNo}`}
        subtitle={`场景 ${task.scene} · 模型 ${task.model || task.provider}`}
        actions={
          <>
            <Button
              startIcon={<ArrowBackIcon />}
              onClick={() => navigate('/tasks')}
            >
              返回
            </Button>
            {!isTerminal && (
              <Button color="error" variant="outlined" onClick={handleCancel}>
                取消任务
              </Button>
            )}
            {/* 重试接口尚未交付（POST /api/tasks/{id}/retry），禁用按钮防止 404 */}
            <Button variant="outlined" disabled title="重试接口待交付">
              重试
            </Button>
          </>
        }
      />

      {/* 顶部汇总：done/total/success/failed/produced（D-51：不含 tokens/cost） */}
      <Paper className="mb-4 p-4">
        <Box className="flex items-center gap-4">
          <StatusChip code={task.status} size="medium" />
          <Typography variant="body2">
            进度 {task.done}/{task.total}
          </Typography>
          <Typography variant="body2" color="success.main">
            成功 {task.success}
          </Typography>
          <Typography variant="body2" color={task.failed > 0 ? 'error' : 'text.secondary'}>
            失败 {task.failed}
          </Typography>
          <Typography variant="body2">
            产出 {task.produced} 条
          </Typography>
        </Box>
        {task.failed > 0 && (
          <Typography variant="body2" color="text.secondary" className="mt-2">
            有 {task.failed} 条子项失败，详见下方明细
            {/* D-42：任务级错误码聚合规则未定，不显示任务级 errorMsg */}
          </Typography>
        )}
      </Paper>

      {/* 进度时间线（SSE 增量） */}
      <Box className="mb-4">
        <ProgressTimeline snapshot={stream.snapshot ?? {
          taskId: task.taskId, status: task.status, progress: task.progress,
          done: task.done, total: task.total, success: task.success,
          failed: task.failed, produced: task.produced,
        }} items={stream.items} />
      </Box>

      {/* item 明细表（先 /items 全量，再叠 SSE 增量） */}
      <Typography variant="h6" className="mb-2">
        子项明细
      </Typography>
      {mergedItems.length === 0 ? (
        <EmptyState variant="empty" title="暂无子项明细" />
      ) : (
        <TableContainer component={Paper}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>定位</TableCell>
                <TableCell>状态</TableCell>
                <TableCell>产出</TableCell>
                <TableCell>耗时</TableCell>
                <TableCell>尝试</TableCell>
                <TableCell>错误码</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {mergedItems.map((item) => {
                const it = item as GenTaskItemVO & ItemFramePayload;
                return (
                  <TableRow key={it.id ?? it.itemId}>
                    <TableCell className="font-mono text-xs">
                      {it.unitRef ?? it.testPointId ?? it.id ?? it.itemId}
                    </TableCell>
                    <TableCell>
                      <StatusChip code={it.status} />
                    </TableCell>
                    <TableCell>{it.caseCount ?? 0} 条</TableCell>
                    <TableCell>{it.latencyMs != null ? `${it.latencyMs}ms` : '-'}</TableCell>
                    <TableCell>{it.attempts ?? '-'}</TableCell>
                    <TableCell>
                      {it.errorCode ? (
                        <Chip label={it.errorCode} size="small" color="error" variant="outlined" />
                      ) : (
                        '-'
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {/* 连接状态提示 */}
      {stream.connection === 'lost' && (
        <Box className="mt-4 text-center">
          <Typography variant="body2" color="text.secondary" className="mb-2">
            连接已断开
          </Typography>
          <Button variant="outlined" onClick={stream.reconnect}>
            重新连接
          </Button>
        </Box>
      )}
    </Box>
  );
}
