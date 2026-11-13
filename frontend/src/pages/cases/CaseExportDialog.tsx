/**
 * T09 用例导出弹窗（#35 xlsx / #36 zip，docs/07 §4.1）。
 *
 * <p><b>两个按钮共用同一 `statuses`</b>（T09-P1-2）：`toStatusesParam` 只算一次，
 * 保证同一任务下 xlsx 与 zip 的用例条数一致。</p>
 *
 * <p><b>全不勾选 ≠ 全量</b>：后端在 `statuses` 为空串时回落 `ACCEPTED,EDITED`，
 * 不是导出全部。界面必须把「全不选」表达成「请选择」，而不是暗示「等于导出全部」——
 * 两个按钮 `disabled` + 明确提示。这样界面与后端口径同向，不会出现
 * 「我什么都没选，结果导出了两项」的静默误解。</p>
 *
 * <p><b>失败保持打开</b>：`40003`（空集/超限）与 `40001`（参数非法）已由
 * `downloadOrThrow` / 拦截器 toast，弹窗保持打开、按钮 loading 复位，方便用户改条件重试。</p>
 */

import { useEffect, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Checkbox from '@mui/material/Checkbox';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import FormControlLabel from '@mui/material/FormControlLabel';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { fetchPublicConfig } from '@/api/auth';
import { exportCasesXlsx, exportJUnitZip } from '@/api/exportApi';
import { CASE_STATUS } from '@/constants/status';
import { toast } from '@/utils/toast';
import {
  ALL_STATUS_FILTERS,
  DEFAULT_EXPORT_STATUSES,
  toStatusesParam,
  type ExportExtension,
  type ExportParams,
  type StatusFilter,
} from '@/types/export';

/** 弹窗属性。 */
export interface CaseExportDialogProps {
  /** 是否打开。 */
  open: boolean;
  /** 关闭回调。 */
  onClose: () => void;
  /** 初始任务 ID（取看板当前 `taskIdFilter`，可编辑）。 */
  defaultTaskId?: string;
}

/** zip 默认 Java 包名。 */
const DEFAULT_PACKAGE_NAME = 'com.aittest.generated';

/**
 * 状态中文标签。
 *
 * @param status 状态码
 * @returns 中文
 */
function statusLabel(status: StatusFilter): string {
  return CASE_STATUS[status]?.label ?? status;
}

/**
 * 用例导出弹窗。
 *
 * @param props 属性
 * @returns 弹窗
 */
export default function CaseExportDialog({
  open,
  onClose,
  defaultTaskId = '',
}: CaseExportDialogProps): JSX.Element {
  const [taskId, setTaskId] = useState(defaultTaskId);
  const [docId, setDocId] = useState('');
  const [statuses, setStatuses] = useState<StatusFilter[]>([...DEFAULT_EXPORT_STATUSES]);
  const [packageName, setPackageName] = useState(DEFAULT_PACKAGE_NAME);
  const [xlsxLoading, setXlsxLoading] = useState(false);
  const [zipLoading, setZipLoading] = useState(false);
  const [maxRows, setMaxRows] = useState<number | null>(null);

  // 每次打开都用看板当前任务 ID 重置（用户可能切换过筛选）
  useEffect(() => {
    if (open) {
      setTaskId(defaultTaskId);
    }
  }, [open, defaultTaskId]);

  // 上限读 PublicConfig.limits.exportMaxRows；读不到就不显示数字，不 fallback 假值
  useEffect(() => {
    if (!open) {
      return undefined;
    }
    let cancelled = false;
    fetchPublicConfig()
      .then((config) => {
        if (!cancelled) {
          setMaxRows(config.limits.exportMaxRows);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setMaxRows(null);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [open]);

  const trimmedTaskId = taskId.trim();
  const trimmedDocId = docId.trim();
  const noneSelected = statuses.length === 0;
  const taskIdMissing = trimmedTaskId.length === 0;
  const busy = xlsxLoading || zipLoading;
  const exportDisabled = noneSelected || taskIdMissing || busy;

  /**
   * 切换一个状态。
   *
   * @param status 状态码
   */
  const toggleStatus = (status: StatusFilter): void => {
    setStatuses((prev) =>
      prev.includes(status) ? prev.filter((item) => item !== status) : [...prev, status],
    );
  };

  /**
   * 触发一次导出。
   *
   * @param extension 文件类型
   */
  const handleExport = async (extension: ExportExtension): Promise<void> => {
    if (exportDisabled) {
      return;
    }
    const params: ExportParams = {
      taskId: trimmedTaskId,
      ...(trimmedDocId ? { docId: trimmedDocId } : {}),
      statuses: toStatusesParam(statuses),
      ...(extension === 'zip' && packageName.trim() ? { packageName: packageName.trim() } : {}),
    };
    const setLoading = extension === 'xlsx' ? setXlsxLoading : setZipLoading;
    setLoading(true);
    try {
      const result =
        extension === 'xlsx' ? await exportCasesXlsx(params) : await exportJUnitZip(params);
      toast(`已导出 ${result.filename}`, 'success');
      onClose();
    } catch {
      // 40003 / 40001 已由 downloadOrThrow / 拦截器 toast；保留弹窗，loading 在 finally 复位
    } finally {
      setLoading(false);
    }
  };

  const selectedLabels = statuses.map(statusLabel).join('、');

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>用例导出</DialogTitle>
      <DialogContent className="space-y-4">
        <TextField
          label="任务 ID（必填）"
          value={taskId}
          onChange={(event) => setTaskId(event.target.value)}
          size="small"
          fullWidth
          required
          error={taskIdMissing}
          helperText={taskIdMissing ? '任务 ID 必填' : undefined}
          className="mt-2"
        />
        <TextField
          label="文档 ID（可选）"
          value={docId}
          onChange={(event) => setDocId(event.target.value)}
          size="small"
          fullWidth
        />

        <Box>
          <Typography variant="body2" className="mb-1">
            导出状态
          </Typography>
          <Box className="flex flex-wrap gap-x-4">
            {ALL_STATUS_FILTERS.map((status) => (
              <FormControlLabel
                key={status}
                control={
                  <Checkbox checked={statuses.includes(status)} onChange={() => toggleStatus(status)} />
                }
                label={statusLabel(status)}
              />
            ))}
          </Box>
          {noneSelected && (
            <Typography variant="caption" color="error">
              至少选择一种状态（空选择不等于全量）
            </Typography>
          )}
        </Box>

        <Alert severity="info" variant="outlined">
          导出范围：任务 {trimmedTaskId || '（未填）'} /{' '}
          {trimmedDocId ? `文档 ${trimmedDocId}` : '全部文档'} / 状态{' '}
          {selectedLabels || '（未选）'}
        </Alert>

        {maxRows !== null && (
          <Typography variant="caption" color="text.secondary">
            单次最多导出 {maxRows} 条，超出请加筛选条件。
          </Typography>
        )}

        <TextField
          label="Java 包名（仅 zip 生效）"
          value={packageName}
          onChange={(event) => setPackageName(event.target.value)}
          size="small"
          fullWidth
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy}>
          取消
        </Button>
        <Button variant="outlined" onClick={() => void handleExport('xlsx')} disabled={exportDisabled}>
          {xlsxLoading ? '导出中…' : '导出 Excel（.xlsx）'}
        </Button>
        <Button variant="contained" onClick={() => void handleExport('zip')} disabled={exportDisabled}>
          {zipLoading ? '导出中…' : '导出 JUnit（.zip）'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
