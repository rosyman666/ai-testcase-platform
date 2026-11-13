import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Container from '@mui/material/Container';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogContentText from '@mui/material/DialogContentText';
import DialogTitle from '@mui/material/DialogTitle';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TablePagination from '@mui/material/TablePagination';
import TableRow from '@mui/material/TableRow';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useMutation, useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { deleteDoc, listDocs, reparseDoc } from '@/api/doc';
import { DOC_TYPE_LABEL, getParseStatusMeta, PARSE_STATUS_META } from '@/constants/docStatus';
import type { DocListQuery, DocSourceVO } from '@/types/doc';
import { toast } from '@/utils/toast';
import DocUploadDialog from '@/pages/docs/DocUploadDialog';

/**
 * 文档列表页（`/docs`）。
 *
 * 三态齐全：loading 转圈、error 带重试、empty 给引导文案（T12 会把这三态抽成通用组件，
 * 在此之前先按同一套语义写，避免到时候改写逻辑）。
 *
 * 解析状态与文档类型的中文映射**不在本文件定义**：测试点评审落地页也要用同一份，
 * 已抽到 `@/constants/docStatus`。
 */
export default function DocList(): JSX.Element {
  const navigate = useNavigate();
  const [query, setQuery] = useState<DocListQuery>({ page: 1, size: 10 });
  const [keywordInput, setKeywordInput] = useState('');
  const [uploadOpen, setUploadOpen] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<DocSourceVO | null>(null);

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['docs', query],
    queryFn: () => listDocs(query),
  });

  const reparseMutation = useMutation({
    mutationFn: (id: string) => reparseDoc(id),
    onSuccess: () => {
      toast('已重新解析', 'success');
      void refetch();
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteDoc(id),
    onSuccess: () => {
      toast('已删除（重新上传同一文件可恢复）', 'success');
      setPendingDelete(null);
      void refetch();
    },
  });

  const rows = data?.records ?? [];

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
      <Box className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <Box>
          <Typography variant="h5" component="h1">
            需求文档
          </Typography>
          <Typography variant="body2" color="text.secondary">
            上传需求文档后自动解析章节与接口，供后续抽取测试点使用。
          </Typography>
        </Box>
        <Button variant="contained" onClick={() => setUploadOpen(true)}>
          上传文档
        </Button>
      </Box>

      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <Box className="flex flex-wrap items-center gap-2">
          <TextField
            size="small"
            label="文件名 / 单号"
            value={keywordInput}
            onChange={(event) => setKeywordInput(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                setQuery((prev) => ({ ...prev, page: 1, keyword: keywordInput.trim() }));
              }
            }}
          />
          <TextField
            select
            size="small"
            label="类型"
            value={query.docType ?? ''}
            onChange={(event) => setQuery((prev) => ({ ...prev, page: 1, docType: event.target.value }))}
            sx={{ minWidth: 140 }}
          >
            <MenuItem value="">全部</MenuItem>
            {Object.entries(DOC_TYPE_LABEL).map(([code, label]) => (
              <MenuItem key={code} value={code}>
                {label}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            size="small"
            label="解析状态"
            value={query.parseStatus ?? ''}
            onChange={(event) => setQuery((prev) => ({ ...prev, page: 1, parseStatus: event.target.value }))}
            sx={{ minWidth: 140 }}
          >
            <MenuItem value="">全部</MenuItem>
            {Object.entries(PARSE_STATUS_META).map(([code, meta]) => (
              <MenuItem key={code} value={code}>
                {meta.label}
              </MenuItem>
            ))}
          </TextField>
          <Button
            variant="outlined"
            onClick={() => setQuery((prev) => ({ ...prev, page: 1, keyword: keywordInput.trim() }))}
          >
            查询
          </Button>
          <Button
            onClick={() => {
              setKeywordInput('');
              setQuery({ page: 1, size: query.size });
            }}
          >
            重置
          </Button>
        </Box>
      </Paper>

      {isError && (
        <Alert
          severity="error"
          sx={{ mb: 2 }}
          action={
            <Button color="inherit" size="small" onClick={() => void refetch()}>
              重试
            </Button>
          }
        >
          文档列表加载失败
        </Alert>
      )}

      <Paper variant="outlined">
        {isLoading ? (
          <Box className="flex items-center justify-center p-12">
            <CircularProgress size={28} />
          </Box>
        ) : rows.length === 0 ? (
          <Box className="flex flex-col items-center gap-2 p-12">
            <Typography color="text.secondary">还没有文档</Typography>
            <Button variant="text" onClick={() => setUploadOpen(true)}>
              上传第一份需求文档
            </Button>
          </Box>
        ) : (
          <>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>单号</TableCell>
                  <TableCell>文件名</TableCell>
                  <TableCell>类型</TableCell>
                  <TableCell>解析状态</TableCell>
                  <TableCell align="right">章节 / 接口</TableCell>
                  <TableCell align="right">大小</TableCell>
                  <TableCell>上传时间</TableCell>
                  <TableCell align="right">操作</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((row) => {
                  const meta = getParseStatusMeta(row.parseStatus);
                  return (
                    <TableRow key={row.id} hover>
                      <TableCell>{row.docNo}</TableCell>
                      <TableCell>
                        <Button size="small" onClick={() => navigate(`/docs/${row.id}`)}>
                          {row.fileName}
                        </Button>
                      </TableCell>
                      <TableCell>{DOC_TYPE_LABEL[row.docType] ?? row.docType}</TableCell>
                      <TableCell>
                        <Chip size="small" label={meta.label} color={meta.color} />
                      </TableCell>
                      <TableCell align="right">
                        {row.sectionCount} / {row.operationCount}
                      </TableCell>
                      <TableCell align="right">{(row.fileSize / 1024).toFixed(1)} KB</TableCell>
                      <TableCell>{dayjs(row.createdAt).format('YYYY-MM-DD HH:mm:ss')}</TableCell>
                      <TableCell align="right">
                        <Button
                          size="small"
                          disabled={reparseMutation.isPending}
                          onClick={() => reparseMutation.mutate(row.id)}
                        >
                          重解析
                        </Button>
                        <Button size="small" color="error" onClick={() => setPendingDelete(row)}>
                          删除
                        </Button>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
            <TablePagination
              component="div"
              count={data?.total ?? 0}
              page={query.page - 1}
              rowsPerPage={query.size}
              rowsPerPageOptions={[10, 20, 50]}
              onPageChange={(_event, nextPage) => setQuery((prev) => ({ ...prev, page: nextPage + 1 }))}
              onRowsPerPageChange={(event) =>
                setQuery((prev) => ({ ...prev, page: 1, size: Number(event.target.value) }))
              }
            />
          </>
        )}
      </Paper>

      <DocUploadDialog
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        onUploaded={() => {
          void refetch();
        }}
      />

      <Dialog open={pendingDelete !== null} onClose={() => setPendingDelete(null)}>
        <DialogTitle>删除文档</DialogTitle>
        <DialogContent>
          <DialogContentText>
            确认删除「{pendingDelete?.fileName}」？删除后重新上传同一文件可恢复原记录（单号不变）。
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPendingDelete(null)}>取消</Button>
          <Button
            color="error"
            disabled={deleteMutation.isPending}
            onClick={() => pendingDelete && deleteMutation.mutate(pendingDelete.id)}
          >
            删除
          </Button>
        </DialogActions>
      </Dialog>
    </Container>
  );
}
