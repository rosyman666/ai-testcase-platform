/**
 * 进度时间线（纯展示）。吃 `snapshot` + `items[]`，**不自己发请求**（便于单测）。
 */

import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import LinearProgress from '@mui/material/LinearProgress';
import Paper from '@mui/material/Paper';
import StatusChip from '@/components/common/StatusChip';
import type { SnapshotPayload, ItemFramePayload } from '@/types/task';

/** ProgressTimeline 属性。 */
export interface ProgressTimelineProps {
  /** 任务快照。 */
  snapshot: SnapshotPayload | null;
  /** 逐 item 增量（SSE 推来的，可能不完整——权威来源是 `/items`）。 */
  items: ItemFramePayload[];
}

/**
 * 进度条 + item 时间线。
 *
 * @param props 属性
 * @returns 时间线组件
 */
export default function ProgressTimeline({ snapshot, items }: ProgressTimelineProps): JSX.Element {
  if (!snapshot) {
    return (
      <Paper className="p-4">
        <Typography color="text.secondary">等待任务数据…</Typography>
      </Paper>
    );
  }

  const { progress, done, total, success, failed, produced, status } = snapshot;

  return (
    <Paper className="p-4">
      {/* 顶部汇总：done/total/success/failed/produced（D-51：不含 tokens/cost） */}
      <Box className="mb-3 flex items-center gap-3">
        <StatusChip code={status} />
        <Typography variant="body2" color="text.secondary">
          {done}/{total} 完成 · 成功 {success} · 失败 {failed} · 产出 {produced} 条
        </Typography>
      </Box>

      <LinearProgress
        variant="determinate"
        value={Math.min(100, Math.max(0, progress))}
        color={failed > 0 ? 'warning' : 'primary'}
        className="mb-4"
      />

      {/* item 时间线 */}
      {items.length > 0 && (
        <Box>
          <Typography variant="subtitle2" className="mb-2">
            子项明细（{items.length} 条）
          </Typography>
          <Box className="flex flex-col gap-1">
            {items.map((item) => (
              <Box
                key={item.itemId}
                className="flex items-center justify-between rounded border border-gray-100 px-3 py-1.5"
              >
                <Box className="flex items-center gap-2">
                  <StatusChip code={item.status} />
                  <Typography variant="body2" className="font-mono text-xs">
                    {item.unitRef ?? item.testPointId ?? item.itemId}
                  </Typography>
                </Box>
                <Box className="flex items-center gap-3">
                  {item.latencyMs !== undefined && (
                    <Typography variant="caption" color="text.secondary">
                      {item.latencyMs}ms
                    </Typography>
                  )}
                  {item.caseCount !== undefined && item.caseCount > 0 && (
                    <Typography variant="caption" color="text.secondary">
                      {item.caseCount} 条
                    </Typography>
                  )}
                  {item.errorCode && (
                    <Typography variant="caption" color="error">
                      {item.errorCode}
                    </Typography>
                  )}
                </Box>
              </Box>
            ))}
          </Box>
        </Box>
      )}
    </Paper>
  );
}
