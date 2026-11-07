import { useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Checkbox from '@mui/material/Checkbox';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Container from '@mui/material/Container';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Divider from '@mui/material/Divider';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TablePagination from '@mui/material/TablePagination';
import TableRow from '@mui/material/TableRow';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  batchTestPointStatus,
  createTestPoint,
  extractTestPoints,
  listTestPoints,
  updateTestPoint,
} from '@/api/testpoint';
import type {
  TestPointExtractReport,
  TestPointListQuery,
  TestPointUpdateReq,
  TestPointVO,
} from '@/types/testpoint';
import { toast } from '@/utils/toast';

/** 状态的中文与配色（后端编码是唯一真源，这里只做展示映射）。 */
const STATUS_META: Record<string, { label: string; color: 'default' | 'success' | 'warning' }> = {
  ACTIVE: { label: '启用', color: 'success' },
  DISABLED: { label: '停用', color: 'default' },
  SUPERSEDED: { label: '已取代', color: 'warning' },
};

/** 风险等级的中文与配色。 */
const RISK_META: Record<string, { label: string; color: 'error' | 'warning' | 'default' }> = {
  HIGH: { label: '高', color: 'error' },
  MEDIUM: { label: '中', color: 'warning' },
  LOW: { label: '低', color: 'default' },
};

/** 分类的中文名。 */
const CATEGORY_LABEL: Record<string, string> = {
  FUNCTIONAL: '功能',
  BOUNDARY: '边界',
  EXCEPTION: '异常',
  SECURITY: '安全',
  PERFORMANCE: '性能',
  COMPATIBILITY: '兼容',
};

/** 分类选项（新增/编辑下拉）。 */
const CATEGORY_OPTIONS = Object.keys(CATEGORY_LABEL);

/**
 * 测试点评审页（验收 ⑥：可启停 / 可编辑 / 可手补）。
 *
 * **为什么列表默认只显示 ACTIVE**：`SUPERSEDED` 是上一轮抽取的旧点，`DISABLED` 是人已停用的。
 * 混在一页里用户会看到"我明明停用的点怎么又出现了"。要看历史，切顶部的状态筛选。
 *
 * **为什么抽取按钮放在本页而不是文档详情页**：抽完立刻要看结果、要挑要停用——
 * 把"抽取"和"评审"放在两页，中间就多一次导航，而这两个动作几乎总是连着做。
 */
export default function TestPointReviewPage(): JSX.Element {
  const { docId = '' } = useParams<{ docId: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [statusFilter, setStatusFilter] = useState('ACTIVE');
  const [selected, setSelected] = useState<string[]>([]);
  const [editing, setEditing] = useState<TestPointVO | null>(null);
  const [editForm, setEditForm] = useState<TestPointUpdateReq>({});
  const [creating, setCreating] = useState(false);
  const [createForm, setCreateForm] = useState({ title: '', category: 'FUNCTIONAL', riskLevel: 'MEDIUM', description: '' });
  const [report, setReport] = useState<TestPointExtractReport | null>(null);

  const query: TestPointListQuery = useMemo(
    () => ({ docId, status: statusFilter, page: page + 1, size }),
    [docId, statusFilter, page, size],
  );

  const listQuery = useQuery({
    queryKey: ['testpoints', query],
    queryFn: () => listTestPoints(query),
    enabled: Boolean(docId),
  });

  const extractMutation = useMutation({
    mutationFn: () => extractTestPoints(docId),
    onSuccess: (data) => {
      setReport(data);
      void queryClient.invalidateQueries({ queryKey: ['testpoints'] });
    },
    onError: () => toast('抽取失败，请查看服务端日志', 'error'),
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, req }: { id: string; req: TestPointUpdateReq }) => updateTestPoint(id, req),
    onSuccess: () => {
      toast('已保存', 'success');
      setEditing(null);
      void queryClient.invalidateQueries({ queryKey: ['testpoints'] });
    },
  });

  const batchMutation = useMutation({
    mutationFn: (status: string) => batchTestPointStatus({ ids: selected, status }),
    onSuccess: (updated) => {
      toast(`已更新 ${updated} 条`, 'success');
      setSelected([]);
      void queryClient.invalidateQueries({ queryKey: ['testpoints'] });
    },
  });

  const createMutation = useMutation({
    mutationFn: () =>
      createTestPoint({
        docId,
        title: createForm.title,
        category: createForm.category,
        riskLevel: createForm.riskLevel,
        description: createForm.description,
      }),
    onSuccess: () => {
      toast('已新增测试点', 'success');
      setCreating(false);
      setCreateForm({ title: '', category: 'FUNCTIONAL', riskLevel: 'MEDIUM', description: '' });
      void queryClient.invalidateQueries({ queryKey: ['testpoints'] });
    },
  });

  const rows = listQuery.data?.records ?? [];
  const allChecked = rows.length > 0 && rows.every((r) => selected.includes(r.id));

  return (
    <Container maxWidth="lg" className="py-8">
      <Stack direction="row" alignItems="center" justifyContent="space-between" className="mb-2">
        <Box>
          <Typography variant="h1" component="h1">
            测试点评审
          </Typography>
          <Typography variant="body2" color="text.secondary">
            文档 {docId}　·　抽取结果可编辑、可停用、可手动补充
          </Typography>
        </Box>
        <Stack direction="row" spacing={1.5}>
          <Button variant="outlined" onClick={() => navigate('/docs')}>
            返回文档
          </Button>
          <Button variant="outlined" onClick={() => setCreating(true)}>
            手动补点
          </Button>
          <Button
            variant="contained"
            disabled={extractMutation.isPending || !docId}
            onClick={() => extractMutation.mutate()}
          >
            {extractMutation.isPending ? '抽取中…' : '开始抽取'}
          </Button>
        </Stack>
      </Stack>

      {extractMutation.isPending && (
        <Alert severity="info" className="mb-4">
          正在按「一个接口/章节一次调用」逐个抽取，篇幅长会耗时较久。T06 起改为异步任务 + 进度推送。
        </Alert>
      )}

      <Paper elevation={0} className="mb-4 p-4" sx={{ border: '1px solid', borderColor: 'divider' }}>
        <Stack direction="row" spacing={2} alignItems="center">
          <TextField
            select
            size="small"
            label="状态"
            value={statusFilter}
            onChange={(e) => {
              setStatusFilter(e.target.value);
              setPage(0);
              setSelected([]);
            }}
            sx={{ minWidth: 140 }}
          >
            {Object.entries(STATUS_META).map(([code, meta]) => (
              <MenuItem key={code} value={code}>
                {meta.label}
              </MenuItem>
            ))}
          </TextField>
          <Divider orientation="vertical" flexItem />
          <Button
            size="small"
            disabled={selected.length === 0 || batchMutation.isPending}
            onClick={() => batchMutation.mutate('DISABLED')}
          >
            停用所选（{selected.length}）
          </Button>
          <Button
            size="small"
            disabled={selected.length === 0 || batchMutation.isPending}
            onClick={() => batchMutation.mutate('ACTIVE')}
          >
            启用所选
          </Button>
        </Stack>
      </Paper>

      <Paper elevation={0} sx={{ border: '1px solid', borderColor: 'divider' }}>
        {listQuery.isLoading ? (
          <Box className="flex justify-center py-10">
            <CircularProgress size={28} />
          </Box>
        ) : rows.length === 0 ? (
          <Box className="py-10 text-center">
            <Typography variant="body1" color="text.secondary">
              {statusFilter === 'ACTIVE' ? '还没有测试点，点右上角「开始抽取」' : '当前筛选下没有测试点'}
            </Typography>
          </Box>
        ) : (
          <>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell padding="checkbox">
                    <Checkbox
                      checked={allChecked}
                      indeterminate={selected.length > 0 && !allChecked}
                      onChange={(e) =>
                        setSelected(e.target.checked ? rows.map((r) => r.id) : [])
                      }
                    />
                  </TableCell>
                  <TableCell>标题</TableCell>
                  <TableCell width={90}>分类</TableCell>
                  <TableCell width={70}>风险</TableCell>
                  <TableCell width={170}>来源</TableCell>
                  <TableCell width={90}>状态</TableCell>
                  <TableCell width={110}>操作</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((row) => (
                  <TableRow key={row.id} hover>
                    <TableCell padding="checkbox">
                      <Checkbox
                        checked={selected.includes(row.id)}
                        onChange={(e) =>
                          setSelected((prev) =>
                            e.target.checked ? [...prev, row.id] : prev.filter((x) => x !== row.id),
                          )
                        }
                      />
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2">{row.title}</Typography>
                      <Typography variant="caption" color="text.secondary">
                        {row.description.slice(0, 80)}
                        {row.description.length > 80 ? '…' : ''}
                      </Typography>
                    </TableCell>
                    <TableCell>{CATEGORY_LABEL[row.category] ?? row.category}</TableCell>
                    <TableCell>
                      <Chip
                        size="small"
                        label={RISK_META[row.riskLevel]?.label ?? row.riskLevel}
                        color={RISK_META[row.riskLevel]?.color ?? 'default'}
                        variant="outlined"
                      />
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                        {row.sourceRef || '—'}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Chip
                        size="small"
                        label={STATUS_META[row.status]?.label ?? row.status}
                        color={STATUS_META[row.status]?.color ?? 'default'}
                      />
                    </TableCell>
                    <TableCell>
                      <Button
                        size="small"
                        onClick={() => {
                          setEditing(row);
                          setEditForm({
                            title: row.title,
                            category: row.category,
                            riskLevel: row.riskLevel,
                            description: row.description,
                          });
                        }}
                      >
                        编辑
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <TablePagination
              component="div"
              count={listQuery.data?.total ?? 0}
              page={page}
              onPageChange={(_, p) => setPage(p)}
              rowsPerPage={size}
              onRowsPerPageChange={(e) => {
                setSize(Number(e.target.value));
                setPage(0);
              }}
              rowsPerPageOptions={[10, 20, 50]}
            />
          </>
        )}
      </Paper>

      {/* 抽取报告 */}
      <Dialog open={report !== null} onClose={() => setReport(null)} maxWidth="sm" fullWidth>
        <DialogTitle>抽取完成</DialogTitle>
        <DialogContent>
          {report && (
            <>
              <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap className="mb-3">
                <Chip size="small" label={`单元 ${report.unitCount}`} />
                <Chip size="small" color="success" label={`成功 ${report.successCount}`} />
                <Chip
                  size="small"
                  color={report.failedCount > 0 ? 'error' : 'default'}
                  label={`失败 ${report.failedCount}`}
                />
                <Chip size="small" label={`模型返回 ${report.parsedCount}`} />
                <Chip size="small" color="primary" label={`新增 ${report.insertedCount}`} />
              </Stack>
              {report.failedCount > 0 && (
                <Alert severity="warning" className="mb-2">
                  有 {report.failedCount} 个单元抽取失败，其余已正常入库。失败明细见下。
                </Alert>
              )}
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>来源</TableCell>
                    <TableCell width={70}>结果</TableCell>
                    <TableCell width={70}>新增</TableCell>
                    <TableCell width={130}>质量信号</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {report.results.map((r) => (
                    <TableRow key={r.sourceRef}>
                      <TableCell sx={{ fontFamily: 'monospace', fontSize: 12 }}>{r.sourceRef}</TableCell>
                      <TableCell>
                        {r.success ? (
                          <Chip size="small" color="success" label="成功" />
                        ) : (
                          <Chip size="small" color="error" label="失败" />
                        )}
                      </TableCell>
                      <TableCell>{r.insertedCount}</TableCell>
                      <TableCell>
                        {r.repaired && (
                          <Chip size="small" color="warning" label="修复过" sx={{ mr: 0.5 }} />
                        )}
                        {r.truncated && <Chip size="small" color="warning" label="截断" />}
                        {!r.repaired && !r.truncated && r.success && (
                          <Typography variant="caption" color="text.secondary">
                            干净
                          </Typography>
                        )}
                        {r.error && (
                          <Typography variant="caption" color="error" display="block">
                            {r.error}
                          </Typography>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setReport(null)}>知道了</Button>
        </DialogActions>
      </Dialog>

      {/* 编辑 */}
      <Dialog open={editing !== null} onClose={() => setEditing(null)} maxWidth="sm" fullWidth>
        <DialogTitle>编辑测试点</DialogTitle>
        <DialogContent>
          <Stack spacing={2} className="pt-2">
            <TextField
              label="标题"
              fullWidth
              value={editForm.title ?? ''}
              onChange={(e) => setEditForm((f) => ({ ...f, title: e.target.value }))}
              helperText="改标题会改变去重身份，下次抽取视为新点"
            />
            <TextField
              select
              label="分类"
              fullWidth
              value={editForm.category ?? ''}
              onChange={(e) => setEditForm((f) => ({ ...f, category: e.target.value }))}
            >
              {CATEGORY_OPTIONS.map((c) => (
                <MenuItem key={c} value={c}>
                  {CATEGORY_LABEL[c]}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              select
              label="风险等级"
              fullWidth
              value={editForm.riskLevel ?? ''}
              onChange={(e) => setEditForm((f) => ({ ...f, riskLevel: e.target.value }))}
            >
              {Object.entries(RISK_META).map(([code, meta]) => (
                <MenuItem key={code} value={code}>
                  {meta.label}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="描述"
              fullWidth
              multiline
              minRows={3}
              value={editForm.description ?? ''}
              onChange={(e) => setEditForm((f) => ({ ...f, description: e.target.value }))}
              helperText="描述不参与去重，可反复修订"
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditing(null)}>取消</Button>
          <Button
            variant="contained"
            disabled={updateMutation.isPending}
            onClick={() => editing && updateMutation.mutate({ id: editing.id, req: editForm })}
          >
            保存
          </Button>
        </DialogActions>
      </Dialog>

      {/* 手补点 */}
      <Dialog open={creating} onClose={() => setCreating(false)} maxWidth="sm" fullWidth>
        <DialogTitle>手动补点</DialogTitle>
        <DialogContent>
          <Stack spacing={2} className="pt-2">
            <TextField
              label="标题"
              fullWidth
              value={createForm.title}
              onChange={(e) => setCreateForm((f) => ({ ...f, title: e.target.value }))}
              helperText="4~500 字；与已有测试点重名同分类会被判定为重复"
            />
            <TextField
              select
              label="分类"
              fullWidth
              value={createForm.category}
              onChange={(e) => setCreateForm((f) => ({ ...f, category: e.target.value }))}
            >
              {CATEGORY_OPTIONS.map((c) => (
                <MenuItem key={c} value={c}>
                  {CATEGORY_LABEL[c]}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              select
              label="风险等级"
              fullWidth
              value={createForm.riskLevel}
              onChange={(e) => setCreateForm((f) => ({ ...f, riskLevel: e.target.value }))}
            >
              {Object.entries(RISK_META).map(([code, meta]) => (
                <MenuItem key={code} value={code}>
                  {meta.label}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="描述"
              fullWidth
              multiline
              minRows={3}
              value={createForm.description}
              onChange={(e) => setCreateForm((f) => ({ ...f, description: e.target.value }))}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCreating(false)}>取消</Button>
          <Button
            variant="contained"
            disabled={createMutation.isPending || createForm.title.trim().length < 4}
            onClick={() => createMutation.mutate()}
          >
            新增
          </Button>
        </DialogActions>
      </Dialog>
    </Container>
  );
}
