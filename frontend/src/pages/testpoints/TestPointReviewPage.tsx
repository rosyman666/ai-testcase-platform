import { useEffect, useMemo, useRef, useState } from 'react';
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
import LinearProgress from '@mui/material/LinearProgress';
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
  TestPointExtractAck,
  TestPointListQuery,
  TestPointUpdateReq,
  TestPointVO,
} from '@/types/testpoint';
import { useTaskStream } from '@/hooks/useTaskStream';
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
  // 抽取自 T06 起是异步任务：这里只存受理回执（taskId + taskNo），进度与终态由
  // useTaskStream 推过来。原先这里存的是同步报告（unitCount/results[]），后端改异步后
  // 再也拿不到那个结构，页面却仍按它渲染，于是「一点抽取就整页崩」。
  const [extractTask, setExtractTask] = useState<TestPointExtractAck | null>(null);
  // 已弹过「抽取完成」toast 的 taskId（effect 双跑/重复触发时不再重复提示）
  const notifiedTaskRef = useRef<string | null>(null);

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
    onSuccess: (ack: TestPointExtractAck) => {
      // 拿到 taskId 只代表「任务已受理」，抽取还在后台跑；这一步不能 invalidate 列表
      // —— 那时候库里还没有新点，刷了也是空的，用户会以为抽取没生效
      setExtractTask(ack);
      toast(`已提交抽取任务 ${ack.taskNo}`, 'success');
    },
    onError: () => toast('提交抽取任务失败，请查看服务端日志', 'error'),
  });

  const extractStream = useTaskStream(extractTask?.taskId);

  /**
   * 抽取任务到终态后刷新列表。
   *
   * 用 ref 去重：`terminal` 变 true 后本 effect 的依赖仍可能因快照字段变化再触发一次，
   * StrictMode 下还会双跑——不去重就是两条一样的 toast。
   */
  useEffect(() => {
    if (!extractTask || !extractStream.terminal) return;
    if (notifiedTaskRef.current === extractTask.taskId) return;
    notifiedTaskRef.current = extractTask.taskId;

    void queryClient.invalidateQueries({ queryKey: ['testpoints'] });

    const failed = extractStream.snapshot?.failed ?? 0;
    const produced = extractStream.snapshot?.produced ?? 0;
    const total = extractStream.snapshot?.total ?? 0;
    if (failed > 0) {
      toast(`抽取结束：新增 ${produced} 条，${failed} 个单元失败`, 'warning');
    } else if (produced === 0 && total > 0) {
      // produced=0 不是失败：抽取是幂等的（content_hash 由 doc_id+title+category 决定），
      // 同一篇文档重复抽取时全部命中库中已有的测试点。说「新增 0 条」用户会读成「白跑了」。
      toast(`${total} 个单元全部成功，无新增（与库中已有测试点一致）`, 'success');
    } else {
      toast(`抽取完成：新增 ${produced} 条`, 'success');
    }
  }, [
    extractTask,
    extractStream.terminal,
    extractStream.snapshot?.failed,
    extractStream.snapshot?.produced,
    extractStream.snapshot?.total,
    queryClient,
  ]);

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

  // 抽取任务仍在跑：按钮要禁用，否则重复点击会重复建任务（后端每次都会新建一个）
  const extractRunning = extractTask !== null && !extractStream.terminal;
  const snap = extractStream.snapshot;

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
            disabled={!docId || extractMutation.isPending || extractRunning}
            onClick={() => extractMutation.mutate()}
          >
            {extractMutation.isPending ? '提交中…' : extractRunning ? '抽取中…' : '开始抽取'}
          </Button>
        </Stack>
      </Stack>

      {extractRunning && (
        <Alert severity="info" className="mb-4">
          抽取任务进行中：已完成 {snap?.done ?? 0} / 共 {snap?.total ?? 0} 个单元
          {snap && snap.produced > 0 ? `，已新增 ${snap.produced} 条` : ''}。
          完成后列表会自动刷新，逐单元明细可去「任务详情」看。
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
        ) : listQuery.isError ? (
          // 必须排在 rows.length === 0 之前：请求失败时 rows 也是空数组，
          // 顺序反了会把"加载失败"显示成"还没有测试点"，引导用户去点"开始抽取"
          // —— 而那正是刚失败的那次操作
          <Box className="py-6">
            <Alert
              severity="error"
              action={
                <Button color="inherit" size="small" onClick={() => void listQuery.refetch()}>
                  重试
                </Button>
              }
            >
              测试点列表加载失败，请检查网络或后端服务是否已启动
            </Alert>
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

      {/* 抽取任务进度：异步任务的进度来自 SSE，不再有同步的「抽取报告」 */}
      <Dialog open={extractTask !== null} onClose={() => setExtractTask(null)} maxWidth="sm" fullWidth>
        <DialogTitle>{extractStream.terminal ? '抽取结束' : '抽取进行中'}</DialogTitle>
        <DialogContent>
          <Stack spacing={2}>
            <Typography variant="body2" color="text.secondary">
              任务号 {extractTask?.taskNo ?? '—'}　·　状态 {snap?.status ?? '连接中…'}
            </Typography>

            <LinearProgress
              variant={snap !== null && snap.total > 0 ? 'determinate' : 'indeterminate'}
              value={snap !== null && snap.total > 0 ? Math.round((snap.done / snap.total) * 100) : undefined}
            />

            <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
              <Chip size="small" label={`单元 ${snap?.done ?? 0}/${snap?.total ?? 0}`} />
              <Chip size="small" color="success" label={`成功 ${snap?.success ?? 0}`} />
              <Chip
                size="small"
                color={(snap?.failed ?? 0) > 0 ? 'error' : 'default'}
                label={`失败 ${snap?.failed ?? 0}`}
              />
              <Chip size="small" color="primary" label={`新增 ${snap?.produced ?? 0}`} />
            </Stack>

            {(snap?.failed ?? 0) > 0 && (
              <Alert severity="warning">
                有 {snap?.failed} 个单元抽取失败，其余已正常入库。失败原因与逐单元明细请到「任务详情」查看。
              </Alert>
            )}

            {extractStream.errorFrame !== null && (
              <Alert severity="error">进度订阅失败：{extractStream.errorFrame.message}</Alert>
            )}

            {extractStream.terminal && (snap?.failed ?? 0) === 0 && (
              <Alert severity="success">
                {(snap?.produced ?? 0) === 0
                  ? `抽取完成，${snap?.total ?? 0} 个单元全部成功。本轮没有新增——抽取是幂等的，同一篇文档重复抽取会命中库里已有的测试点，列表已刷新。`
                  : '抽取完成，列表已刷新。'}
              </Alert>
            )}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => navigate(`/tasks/${extractTask?.taskId ?? ''}`)}>查看任务详情</Button>
          <Button variant="contained" onClick={() => setExtractTask(null)}>
            {extractStream.terminal ? '知道了' : '后台运行'}
          </Button>
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
