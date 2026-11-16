/**
 * 导入执行结果弹窗（JUnit XML / CSV）。
 *
 * <p><b>成功后自动跳详情</b>：导入完用户下一步一定是"看失败明细"，
 * 停在列表上还要再点一次「查看明细」，等于把唯一的目的地藏了一层。</p>
 *
 * <p><b>前端先挡一道</b>：未选任务 / 未选文件时提交按钮 `disabled`。
 * 不靠后端报错才发现——那要多一次往返，且错误文案是"参数校验失败"，
 * 用户读到的信息量远低于"你还没选文件"。</p>
 *
 * <p>批次名称留空 = 由后端取文件名（不 append 空串），避免落成空批次名。</p>
 */

import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import MenuItem from '@mui/material/MenuItem';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/api/http';
import { importExecution } from '@/api/execution';
import { listTasks } from '@/api/task';
import { toast } from '@/utils/toast';

/** 允许上传的扩展名（与后端执行结果解析器的白名单一致）。 */
const ACCEPT_EXTENSIONS = '.xml,.csv';

/** 任务下拉取最近一页即可：导入的是"刚跑完的那批用例"，不需要全量分页选择。 */
const TASK_OPTION_PAGE_SIZE = 50;

/** 组件入参。 */
export interface ExecutionImportDialogProps {
  /** 是否打开。 */
  open: boolean;
  /** 关闭回调（提交中不允许关闭，避免"以为取消了其实传上去了"）。 */
  onClose: () => void;
}

/**
 * 导入执行结果弹窗。
 *
 * @param props 开关状态与关闭回调
 * @returns 弹窗
 */
export default function ExecutionImportDialog({ open, onClose }: ExecutionImportDialogProps): JSX.Element {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const inputRef = useRef<HTMLInputElement>(null);

  const [taskId, setTaskId] = useState<string>('');
  const [file, setFile] = useState<File | null>(null);
  const [name, setName] = useState<string>('');
  const [localError, setLocalError] = useState<string>('');

  // 任务下拉：只在打开时拉，#close 时不打无谓的请求
  const taskQuery = useQuery({
    queryKey: ['tasks', 'options', TASK_OPTION_PAGE_SIZE],
    queryFn: () => listTasks({ page: 1, size: TASK_OPTION_PAGE_SIZE }),
    enabled: open,
  });
  const tasks = taskQuery.data?.records ?? [];

  const mutation = useMutation({
    mutationFn: (payload: { taskId: string; file: File; name: string }) => importExecution(payload),
    onSuccess: async (result) => {
      // 带任务单号：用户下一步就是去对任务里的用例，只说"导入成功"还得自己找
      const prefix = result.taskNo ? `已导入到任务 ${result.taskNo}：` : '导入成功：';
      if (result.total > 0 && result.mappedCount === 0) {
        // 映射不上**不算导入失败**（后端不阻断），但"一条都没映射上"几乎只有两个原因：
        // 拿旧的结果文件导入（同一任务只保留最近一次导出的编号），或报告里的
        // classname 与导出文件完全不一致。这时仍然跳详情，但提示语必须点明这一点，
        // 否则用户看到"导入成功"就去详情页找映射，找不到再回来问"为什么全是未映射"
        toast(
          `${prefix}共 ${result.total} 条，但一条都没映射到用例（0 / ${result.total}）；` +
            '请确认导入的是与最近一次导出（junit.zip）对应的结果文件',
          'warning',
        );
      } else {
        toast(`${prefix}共 ${result.total} 条，映射 ${result.mappedCount} 条`, 'success');
      }
      // 列表与详情共用 'executions' 前缀：一次失效把两页都刷新掉
      await queryClient.invalidateQueries({ queryKey: ['executions'] });
      onClose();
      // 无论映射率多少都进详情：映射不上的记录也保留着，用户要能看到它们
      navigate(`/executions/${result.runId}`);
    },
    onError: (error: unknown) => {
      // http.ts 的响应拦截器已经对业务错误 / 网络错误 toast 过一次（网络错误还带 traceId），
      // 这里再 toast 一次会出现两条一模一样的提示叠在右下角。所以只给"没经过拦截器
      // 归一化"的意外异常补提示，保证失败一定有反馈、又不重复。
      if (error instanceof ApiError) {
        return;
      }
      toast(error instanceof Error ? error.message : '导入失败，请稍后重试', 'error');
    },
  });

  // 每次打开重置内部状态，避免上次选的任务 / 文件残留
  useEffect(() => {
    if (open) {
      setTaskId('');
      setFile(null);
      setName('');
      setLocalError('');
    }
  }, [open]);

  const handleClose = (): void => {
    if (mutation.isPending) {
      return;
    }
    onClose();
  };

  const handlePick = (event: React.ChangeEvent<HTMLInputElement>): void => {
    const picked = event.target.files?.[0] ?? null;
    if (!picked) {
      setFile(null);
      return;
    }
    const extension = picked.name.includes('.') ? picked.name.split('.').pop()?.toLowerCase() ?? '' : '';
    if (!ACCEPT_EXTENSIONS.split(',').includes(`.${extension}`)) {
      setLocalError(`不支持的文件类型 .${extension}，仅支持 JUnit XML（.xml）与 CSV（.csv）`);
      setFile(null);
      return;
    }
    if (picked.size === 0) {
      // 0 字节文件后端会回 44002，其 `EMPTY_FILE_MESSAGE` 与下面这句**逐字相同**
      // （任何路径触发的 0 字节，用户看到的必须是同一句话；改这里就要通知后端改常量）。
      //
      // 文案选"重新导出"而不是"重新选择"：0 字节几乎只有一个成因——导出/下载中断，
      // 文件根本没被写进去。只说"请重新选择"，用户会去文件管理器里再点一次同一个空文件，
      // 原地撞第二次墙。
      setLocalError('文件内容为空（0 字节），通常是导出或下载没有完成，请重新导出后再上传');
      setFile(null);
      return;
    }
    setLocalError('');
    setFile(picked);
  };

  const canSubmit = Boolean(taskId) && Boolean(file) && !mutation.isPending;

  /**
   * 任务下拉的辅助说明。
   *
   * 三种状态各有各的说法：字典/接口挂了、任务一个都没有（**用户此时无论怎么点都提交不了**，
   * 必须明确指向"先去生成任务"）、正常情况。
   */
  let taskHelperText = '这批执行结果属于哪个用例生成任务（决定明细能与哪些用例对上）';
  if (taskQuery.isError) {
    taskHelperText = '任务列表加载失败，请关闭弹窗后重试';
  } else if (!taskQuery.isLoading && tasks.length === 0) {
    taskHelperText = '还没有可关联的任务，请先到「生成任务」创建并跑完一个任务';
  }

  const handleSubmit = (): void => {
    if (!taskId || !file || mutation.isPending) {
      return;
    }
    mutation.mutate({ taskId, file, name });
  };

  return (
    <Dialog open={open} onClose={handleClose} fullWidth maxWidth="sm">
      <DialogTitle>导入执行结果</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          支持 JUnit XML 报告（.xml）与结果表（.csv）。导入后按 <code>classname</code> 反查用例，
          映射不上的记录仍会保留，可以在详情页看到。
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          请导入<strong>与本次导出对应</strong>的结果文件：用例导出（junit.zip）时会把「第 N 行 → 用例」
          的编号固化下来，同一任务只保留最近一次导出的编号，拿旧文件导入会大面积映射不上。
        </Typography>

        <TextField
          select
          required
          fullWidth
          label="关联任务"
          value={taskId}
          onChange={(event) => setTaskId(event.target.value)}
          size="small"
          sx={{ mb: 2 }}
          helperText={taskHelperText}
          error={taskQuery.isError}
        >
          <MenuItem value="">
            <em>请选择任务</em>
          </MenuItem>
          {tasks.map((task) => (
            <MenuItem key={task.taskId} value={task.taskId}>
              {`${task.taskNo} · ${task.scene}`}
            </MenuItem>
          ))}
        </TextField>

        <TextField
          fullWidth
          label="批次名称（可选）"
          value={name}
          onChange={(event) => setName(event.target.value)}
          size="small"
          sx={{ mb: 2 }}
          placeholder="留空则使用文件名"
          helperText="留空则使用文件名，便于在列表里认出这批结果"
        />

        <Box
          className="flex flex-col items-center justify-center gap-2 rounded-lg border border-dashed p-6"
          sx={{ borderColor: 'divider' }}
        >
          <input
            ref={inputRef}
            type="file"
            accept={ACCEPT_EXTENSIONS}
            hidden
            onChange={handlePick}
            aria-label="选择结果文件"
          />
          <Button variant="outlined" onClick={() => inputRef.current?.click()} disabled={mutation.isPending}>
            选择文件
          </Button>
          <Typography variant="body2" color={file ? 'text.primary' : 'text.secondary'}>
            {file ? `${file.name}（${(file.size / 1024).toFixed(1)} KB）` : '尚未选择文件（.xml / .csv）'}
          </Typography>
        </Box>

        {localError && (
          <Alert severity="error" sx={{ mt: 2 }}>
            {localError}
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose} disabled={mutation.isPending}>
          取消
        </Button>
        <Button variant="contained" disabled={!canSubmit} onClick={handleSubmit}>
          {mutation.isPending ? '上传中…' : '导入'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
