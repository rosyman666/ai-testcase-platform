/**
 * 建任务对话框。选文档 → 选测试点 → 建任务。
 *
 * **不依赖 `useTaskStream`**（§3.3 判断 1）：建完只调 `POST /api/tasks` 拿 `{taskId,taskNo}`，
 * 拿完 navigate 到详情页。订阅发生在详情页。
 *
 * ⚠️ **请求体固定 `{scene:'CASE_GENERATE', docId, provider?, model?, testPointIds:[…]}`**
 * （D-49）：**不设模板下拉、不设超时输入**——`templateCode`/`timeoutSec` 不存在，
 * 传了被静默忽略，用户以为选了实际没用。
 */

import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import MenuItem from '@mui/material/MenuItem';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Checkbox from '@mui/material/Checkbox';
import FormControlLabel from '@mui/material/FormControlLabel';
import CircularProgress from '@mui/material/CircularProgress';
import { useQuery } from '@tanstack/react-query';
import { listDocs } from '@/api/doc';
import { listTestPoints } from '@/api/testpoint';
import { createTask } from '@/api/task';
import { toast } from '@/utils/toast';
import type { GenTaskCreateReq } from '@/types/task';

/** TaskCreateDialog 属性。 */
export interface TaskCreateDialogProps {
  /** 是否打开。 */
  open: boolean;
  /** 关闭回调。 */
  onClose: () => void;
}

/**
 * 建任务对话框。
 *
 * @param props 属性
 * @returns 对话框
 */
export default function TaskCreateDialog({ open, onClose }: TaskCreateDialogProps): JSX.Element {
  const navigate = useNavigate();
  const [docId, setDocId] = useState('');
  const [selectedPoints, setSelectedPoints] = useState<Set<string>>(new Set());
  const [submitting, setSubmitting] = useState(false);

  // 重置状态
  useEffect(() => {
    if (open) {
      setDocId('');
      setSelectedPoints(new Set());
      setSubmitting(false);
    }
  }, [open]);

  // 文档列表
  const { data: docsData, isLoading: docsLoading } = useQuery({
    queryKey: ['docs', 'for-task'],
    queryFn: () => listDocs({ page: 1, size: 100 }),
    enabled: open,
  });

  // 测试点列表（按选中文档）
  const { data: pointsData, isLoading: pointsLoading } = useQuery({
    queryKey: ['testpoints', 'for-task', docId],
    queryFn: () => listTestPoints({ docId, page: 1, size: 200 }),
    enabled: open && !!docId,
  });

  const docs = docsData?.records ?? [];
  const points = pointsData?.records ?? [];

  const togglePoint = (id: string): void => {
    setSelectedPoints((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const handleSubmit = async (): Promise<void> => {
    if (!docId) {
      toast('请先选择文档', 'warning');
      return;
    }
    if (selectedPoints.size === 0) {
      toast('请至少选择一个测试点', 'warning');
      return;
    }

    setSubmitting(true);
    try {
      // D-49：scene 必填，unitRefs 与 testPointIds 二选一，无 templateCode/timeoutSec
      const req: GenTaskCreateReq = {
        scene: 'CASE_GENERATE',
        docId,
        testPointIds: Array.from(selectedPoints),
      };
      const result = await createTask(req);
      toast(`任务 ${result.taskNo} 已创建`, 'success');
      onClose();
      navigate(`/tasks/${result.taskId}`);
    } catch {
      // http.ts 已统一 toast 了错误
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>创建用例生成任务</DialogTitle>
      <DialogContent>
        <Box className="flex flex-col gap-4 pt-2">
          {/* 文档选择 */}
          <TextField
            select
            label="选择文档"
            value={docId}
            onChange={(e) => {
              setDocId(e.target.value);
              setSelectedPoints(new Set());
            }}
            disabled={docsLoading}
            fullWidth
          >
            {docs.map((doc) => (
              <MenuItem key={doc.id} value={doc.id}>
                {doc.fileName}
              </MenuItem>
            ))}
          </TextField>

          {/* 测试点选择 */}
          {docId && (
            <Box>
              <Typography variant="subtitle2" className="mb-2">
                选择测试点（已选 {selectedPoints.size} 个）
              </Typography>
              {pointsLoading ? (
                <CircularProgress size={20} />
              ) : points.length === 0 ? (
                <Typography variant="body2" color="text.secondary">
                  该文档暂无测试点
                </Typography>
              ) : (
                <Box className="max-h-60 overflow-y-auto">
                  {points.map((point) => (
                    <FormControlLabel
                      key={point.id}
                      control={
                        <Checkbox
                          checked={selectedPoints.has(point.id)}
                          onChange={() => togglePoint(point.id)}
                          size="small"
                        />
                      }
                      label={
                        <Typography variant="body2">
                          {point.title || point.id}
                        </Typography>
                      }
                    />
                  ))}
                </Box>
              )}
            </Box>
          )}
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={submitting}>
          取消
        </Button>
        <Button
          onClick={handleSubmit}
          variant="contained"
          disabled={submitting || !docId || selectedPoints.size === 0}
        >
          {submitting ? '创建中…' : '创建任务'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
