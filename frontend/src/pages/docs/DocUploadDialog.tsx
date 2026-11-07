import { useEffect, useRef, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Typography from '@mui/material/Typography';
import { useMutation } from '@tanstack/react-query';
import { uploadDoc } from '@/api/doc';
import type { DocUploadResult } from '@/types/doc';
import { toast } from '@/utils/toast';

/** 允许上传的扩展名（与后端 `common.enums.DocType` 的扩展名白名单一致）。 */
const ACCEPT_EXTENSIONS = '.md,.txt,.docx,.json';

/** 单文件上限（与 `spring.servlet.multipart.max-file-size: 20MB` 一致，前端先挡一道省一次往返）。 */
const MAX_FILE_MB = 20;

/** 组件入参。 */
interface DocUploadDialogProps {
  /** 是否打开。 */
  open: boolean;
  /** 关闭回调。 */
  onClose: () => void;
  /** 上传成功回调（`parseStatus` 可能为 FAILED，由调用方决定提示与跳转）。 */
  onUploaded: (result: DocUploadResult) => void;
}

/**
 * 文档上传对话框。
 *
 * 只负责"选文件 + 调接口"，不负责列表刷新——刷新交给调用方，避免这个组件和列表状态耦合。
 */
export default function DocUploadDialog({ open, onClose, onUploaded }: DocUploadDialogProps): JSX.Element {
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [localError, setLocalError] = useState<string>('');

  const mutation = useMutation({
    mutationFn: (target: File) => uploadDoc(target),
    onSuccess: (result) => {
      // 解析失败时后端仍返回 code=0（请求成功、文档状态失败），这里按状态给不同提示
      if (result.parseStatus === 'FAILED') {
        toast('文件已保存，但解析失败，请在详情页查看原因', 'warning');
      } else {
        toast(`上传成功：${result.parseStatus === 'PARSED' ? '已解析' : '解析中'}`, 'success');
      }
      onUploaded(result);
      handleClose();
    },
    onError: () => {
      // 42002/50000 等已由 http.ts 拦截器统一 toast，这里不重复提示
    },
  });

  // 每次打开重置内部状态，避免上次选的文件残留
  useEffect(() => {
    if (open) {
      setFile(null);
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
      setLocalError(`不支持的文件类型 .${extension}，仅支持 md / txt / docx / json`);
      setFile(null);
      return;
    }
    if (picked.size > MAX_FILE_MB * 1024 * 1024) {
      setLocalError(`文件 ${(picked.size / 1024 / 1024).toFixed(1)}MB 超过上限 ${MAX_FILE_MB}MB`);
      setFile(null);
      return;
    }
    setLocalError('');
    setFile(picked);
  };

  return (
    <Dialog open={open} onClose={handleClose} fullWidth maxWidth="sm">
      <DialogTitle>上传需求文档</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          支持 Markdown（.md）、纯文本（.txt）、Word（.docx）、OpenAPI/Swagger JSON（.json），单文件不超过{' '}
          {MAX_FILE_MB}MB。
        </Typography>

        <Box
          className="flex flex-col items-center justify-center gap-2 rounded-lg border border-dashed p-6"
          sx={{ borderColor: 'divider' }}
        >
          <input ref={inputRef} type="file" accept={ACCEPT_EXTENSIONS} hidden onChange={handlePick} />
          <Button variant="outlined" onClick={() => inputRef.current?.click()} disabled={mutation.isPending}>
            选择文件
          </Button>
          <Typography variant="body2" color={file ? 'text.primary' : 'text.secondary'}>
            {file ? `${file.name}（${(file.size / 1024).toFixed(1)} KB）` : '尚未选择文件'}
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
        <Button
          variant="contained"
          disabled={!file || mutation.isPending}
          onClick={() => file && mutation.mutate(file)}
        >
          {mutation.isPending ? '上传并解析中…' : '上传'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
