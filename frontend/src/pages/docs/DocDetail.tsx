import { useNavigate, useParams } from 'react-router-dom';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Container from '@mui/material/Container';
import Divider from '@mui/material/Divider';
import List from '@mui/material/List';
import ListItem from '@mui/material/ListItem';
import Paper from '@mui/material/Paper';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import { useMutation, useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { getDoc, reparseDoc } from '@/api/doc';
import { toast } from '@/utils/toast';

/** 状态中文与配色（与列表页保持同一套映射）。 */
const PARSE_STATUS_META: Record<string, { label: string; color: 'default' | 'success' | 'error' | 'warning' }> = {
  PENDING: { label: '待解析', color: 'warning' },
  PARSED: { label: '已解析', color: 'success' },
  FAILED: { label: '解析失败', color: 'error' },
};

/** HTTP 方法配色。 */
const METHOD_COLOR: Record<string, 'primary' | 'success' | 'warning' | 'error' | 'default'> = {
  GET: 'success',
  POST: 'primary',
  PUT: 'warning',
  PATCH: 'warning',
  DELETE: 'error',
};

/**
 * 文档详情页（`/docs/:id`）。
 *
 * 章节按 `level` 缩进渲染成树，接口列表用表格。两者都是**内嵌预览**：
 * 后端详情接口各取前 200 条、章节正文截断到 1000 字，全量走 `/sections`、`/operations` 分页接口。
 */
export default function DocDetail(): JSX.Element {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['doc', id],
    queryFn: () => getDoc(id as string),
    enabled: Boolean(id),
  });

  const reparseMutation = useMutation({
    mutationFn: () => reparseDoc(id as string),
    onSuccess: () => {
      toast('已重新解析', 'success');
      void refetch();
    },
  });

  if (!id) {
    return (
      <Container maxWidth="lg" sx={{ py: 4 }}>
        <Alert severity="error">缺少文档 ID</Alert>
      </Container>
    );
  }

  if (isLoading) {
    return (
      <Container maxWidth="lg" sx={{ py: 4 }}>
        <Box className="flex items-center justify-center p-12">
          <CircularProgress size={28} />
        </Box>
      </Container>
    );
  }

  if (isError || !data) {
    return (
      <Container maxWidth="lg" sx={{ py: 4 }}>
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={() => void refetch()}>
              重试
            </Button>
          }
        >
          文档详情加载失败
        </Alert>
      </Container>
    );
  }

  const statusMeta = PARSE_STATUS_META[data.parseStatus] ?? {
    label: data.parseStatus,
    color: 'default' as const,
  };

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
      <Box className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <Box>
          <Button size="small" onClick={() => navigate('/docs')} sx={{ mb: 1 }}>
            ← 返回文档列表
          </Button>
          <Typography variant="h5" component="h1">
            {data.fileName}
          </Typography>
          <Box className="mt-1 flex flex-wrap items-center gap-2">
            <Typography variant="body2" color="text.secondary">
              {data.docNo}
            </Typography>
            <Chip size="small" label={statusMeta.label} color={statusMeta.color} />
            <Typography variant="body2" color="text.secondary">
              {data.sectionCount} 章节 / {data.operationCount} 接口 · {(data.fileSize / 1024).toFixed(1)} KB ·
              上传于 {dayjs(data.createdAt).format('YYYY-MM-DD HH:mm:ss')}
            </Typography>
          </Box>
        </Box>
        <Button variant="outlined" disabled={reparseMutation.isPending} onClick={() => reparseMutation.mutate()}>
          {reparseMutation.isPending ? '解析中…' : '重新解析'}
        </Button>
      </Box>

      {data.parseStatus === 'FAILED' && (
        <Alert severity="error" sx={{ mb: 2 }}>
          解析失败：{data.parseError || '未提供原因'}
        </Alert>
      )}

      <Box className="grid gap-4 lg:grid-cols-2">
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="subtitle1" component="h2" gutterBottom>
            章节结构（{data.sections.length} 条预览）
          </Typography>
          <Divider sx={{ mb: 1 }} />
          {data.sections.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              没有章节（OpenAPI 文档只有接口操作）
            </Typography>
          ) : (
            <List dense disablePadding>
              {data.sections.map((section) => (
                <ListItem
                  key={section.id}
                  alignItems="flex-start"
                  sx={{ display: 'block', pl: `${Math.min(section.level - 1, 5) * 16}px` }}
                >
                  <Typography variant="body2" sx={{ fontWeight: section.level <= 2 ? 600 : 400 }}>
                    {section.seqNo}. {section.heading}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" className="line-clamp-2">
                    {section.content.slice(0, 120)}
                    {section.contentLength > section.content.length ? '…' : ''}
                  </Typography>
                </ListItem>
              ))}
            </List>
          )}
        </Paper>

        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="subtitle1" component="h2" gutterBottom>
            接口列表（{data.operations.length} 条预览）
          </Typography>
          <Divider sx={{ mb: 1 }} />
          {data.operations.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              没有接口（纯文本文档只有章节）
            </Typography>
          ) : (
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>方法</TableCell>
                  <TableCell>路径</TableCell>
                  <TableCell>摘要</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {data.operations.map((operation) => (
                  <TableRow key={operation.id} hover>
                    <TableCell>
                      <Chip
                        size="small"
                        label={operation.method}
                        color={METHOD_COLOR[operation.method] ?? 'default'}
                      />
                    </TableCell>
                    <TableCell sx={{ wordBreak: 'break-all' }}>{operation.path}</TableCell>
                    <TableCell>{operation.summary || '—'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </Paper>
      </Box>
    </Container>
  );
}
