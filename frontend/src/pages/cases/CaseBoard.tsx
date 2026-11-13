/**
 * 用例评审看板。列表 + 批量选择 + 键盘评审（A/R/E）+ 自动下一条。
 *
 * ⚠️ **筛选栏只放 3 个控件**（D-50）：任务 / 状态 / 关键词。
 * `docId`/`priority`/`caseType` 未实现，做了就是"筛选条件不生效"。
 *
 * ⚠️ **快捷键规则**（T08 ⑥）：
 * - `A` = 采纳，`R` = 驳回（打开原因选择器，**不立即提交**），`E` = 打开抽屉编辑
 * - 焦点在 `<input>`/`<textarea>` 时三键全部忽略
 * - 提交后 `selectedIndex` 前进 1；到最后一条时停在原地并提示"已是最后一条"
 */

import { useState, useEffect, useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Typography from '@mui/material/Typography';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import TablePagination from '@mui/material/TablePagination';
import Paper from '@mui/material/Paper';
import Checkbox from '@mui/material/Checkbox';
import TextField from '@mui/material/TextField';
import MenuItem from '@mui/material/MenuItem';
import Chip from '@mui/material/Chip';
import Alert from '@mui/material/Alert';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import PageHeader from '@/components/common/PageHeader';
import StatusChip from '@/components/common/StatusChip';
import EmptyState from '@/components/common/EmptyState';
import CaseReviewDrawer from '@/pages/cases/CaseReviewDrawer';
import CaseExportDialog from '@/pages/cases/CaseExportDialog';
import RejectReasonPicker from '@/pages/cases/RejectReasonPicker';
import { listCases, reviewCase, batchReviewCases } from '@/api/cases';
import { usePageQuery } from '@/hooks/usePageQuery';
import { toast } from '@/utils/toast';
import { CASE_STATUS } from '@/constants/status';

/**
 * 用例评审看板。
 *
 * @returns 页面
 */
export default function CaseBoard(): JSX.Element {
  const [searchParams] = useSearchParams();
  const taskIdFromUrl = searchParams.get('taskId') ?? '';
  const queryClient = useQueryClient();

  const [taskIdFilter, setTaskIdFilter] = useState(taskIdFromUrl);
  const [statusFilter, setStatusFilter] = useState('');
  const [keyword, setKeyword] = useState('');
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [selectedCaseId, setSelectedCaseId] = useState<string | null>(null);
  const [showRejectPicker, setShowRejectPicker] = useState(false);
  const [rejectReasonCode, setRejectReasonCode] = useState('');
  const [exportOpen, setExportOpen] = useState(false);
  const [batchResult, setBatchResult] = useState<{ updated: number; skipped: Array<{ id: string; reason: string }> } | null>(null);
  const { page, size, setPage, setSize, query, resetPage } = usePageQuery();

  // 自动下一条：当前焦点行
  const [selectedIndex, setSelectedIndex] = useState(-1);

  const { data, isLoading, refetch } = useQuery({
    queryKey: ['cases', query.page, query.size, taskIdFilter, statusFilter, keyword],
    queryFn: () =>
      listCases({
        taskId: taskIdFilter || undefined,
        status: statusFilter || undefined,
        keyword: keyword || undefined,
        ...query,
      }),
  });

  const cases = useMemo(() => data?.records ?? [], [data]);
  const total = data?.total ?? 0;

  // 快捷键
  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      // 焦点在输入框时忽略
      const tag = (e.target as HTMLElement)?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;

      const key = e.key.toUpperCase();
      const currentCase = cases[selectedIndex];

      if (key === 'A' && currentCase) {
        e.preventDefault();
        reviewCase(currentCase.id, { action: 'ACCEPT' })
          .then((updated) => {
            queryClient.setQueryData(['case', currentCase.id], updated);
            toast('已采纳', 'success');
            // 自动下一条
            setSelectedIndex((i) => Math.min(i + 1, cases.length - 1));
            refetch();
          })
          .catch(() => {});
      } else if (key === 'R' && currentCase) {
        e.preventDefault();
        // 打开原因选择器，**不立即提交**
        setShowRejectPicker(true);
      } else if (key === 'E' && currentCase) {
        e.preventDefault();
        setSelectedCaseId(currentCase.id);
      }
    },
    [cases, selectedIndex, queryClient, refetch],
  );

  useEffect(() => {
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [handleKeyDown]);

  const toggleSelect = (id: string): void => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleSelectAll = (): void => {
    if (selectedIds.size === cases.length) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(cases.map((c) => c.id)));
    }
  };

  const handleBatch = async (action: 'ACCEPT' | 'REJECT'): Promise<void> => {
    if (selectedIds.size === 0) {
      toast('请先选择用例', 'warning');
      return;
    }
    if (action === 'REJECT' && !rejectReasonCode) {
      setShowRejectPicker(true);
      return;
    }
    try {
      const result = await batchReviewCases({
        ids: Array.from(selectedIds),
        action,
        ...(action === 'REJECT' ? { rejectReasonCode } : {}),
      });
      setBatchResult(result);
      // 按 id 标黄跳过的行
      if (result.skipped.length > 0) {
        toast(`已更新 ${result.updated} 条，${result.skipped.length} 条被跳过`, 'warning');
      } else {
        toast(`已更新 ${result.updated} 条`, 'success');
      }
      setSelectedIds(new Set());
      refetch();
    } catch {
      // http.ts 已统一 toast
    }
  };

  const handleRejectSingle = async (): Promise<void> => {
    const currentCase = cases[selectedIndex];
    if (!currentCase || !rejectReasonCode) return;
    try {
      const updated = await reviewCase(currentCase.id, {
        action: 'REJECT',
        rejectReasonCode,
      });
      queryClient.setQueryData(['case', currentCase.id], updated);
      toast('已驳回', 'success');
      setShowRejectPicker(false);
      setRejectReasonCode('');
      // 自动下一条
      setSelectedIndex((i) => Math.min(i + 1, cases.length - 1));
      refetch();
    } catch {
      // http.ts 已统一 toast
    }
  };

  return (
    <Box className="mx-auto max-w-7xl p-6">
      <PageHeader
        title="用例评审看板"
        subtitle={
          taskIdFilter
            ? `任务 ${taskIdFilter} 的用例`
            : '跨任务查看用例（使用上方筛选）'
        }
        actions={
          <>
            <Button
              variant="outlined"
              color="error"
              disabled={selectedIds.size === 0}
              onClick={() => handleBatch('REJECT')}
            >
              批量驳回（{selectedIds.size}）
            </Button>
            <Button
              variant="contained"
              disabled={selectedIds.size === 0}
              onClick={() => handleBatch('ACCEPT')}
            >
              批量采纳（{selectedIds.size}）
            </Button>
            <Button variant="outlined" onClick={() => setExportOpen(true)}>
              用例导出
            </Button>
          </>
        }
      />

      {/* 筛选栏：只 3 个控件（D-50） */}
      <Box className="mb-4 flex items-center gap-3">
        <TextField
          label="任务 ID"
          value={taskIdFilter}
          onChange={(e) => {
            setTaskIdFilter(e.target.value);
            resetPage();
          }}
          size="small"
          sx={{ minWidth: 140 }}
        />
        <TextField
          select
          label="状态"
          value={statusFilter}
          onChange={(e) => {
            setStatusFilter(e.target.value);
            resetPage();
          }}
          size="small"
          sx={{ minWidth: 120 }}
        >
          <MenuItem value="">全部</MenuItem>
          {Object.entries(CASE_STATUS).map(([code, meta]) => (
            <MenuItem key={code} value={code}>
              {meta.label}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          label="关键词"
          value={keyword}
          onChange={(e) => {
            setKeyword(e.target.value);
            resetPage();
          }}
          size="small"
          sx={{ minWidth: 200 }}
        />
      </Box>

      {/* 批量结果提示 */}
      {batchResult && batchResult.skipped.length > 0 && (
        <Alert severity="warning" className="mb-4" onClose={() => setBatchResult(null)}>
          已更新 {batchResult.updated} 条，{batchResult.skipped.length} 条被跳过：
          {batchResult.skipped.map((s) => ` ${s.reason}`).join('；')}
        </Alert>
      )}

      {/* 驳回原因选择器（全局） */}
      {showRejectPicker && (
        <Paper className="mb-4 p-4">
          <RejectReasonPicker
            value={rejectReasonCode}
            onChange={setRejectReasonCode}
            onConfirm={handleRejectSingle}
          />
        </Paper>
      )}

      {/* 列表 */}
      {isLoading ? (
        <EmptyState variant="loading" title="加载中…" />
      ) : cases.length === 0 ? (
        <EmptyState variant="empty" title="暂无用例" description="先运行用例生成任务" />
      ) : (
        <>
          <TableContainer component={Paper}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell padding="checkbox">
                    <Checkbox
                      checked={selectedIds.size === cases.length && cases.length > 0}
                      indeterminate={selectedIds.size > 0 && selectedIds.size < cases.length}
                      onChange={toggleSelectAll}
                    />
                  </TableCell>
                  <TableCell>#</TableCell>
                  <TableCell>标题</TableCell>
                  <TableCell>状态</TableCell>
                  <TableCell>优先级</TableCell>
                  <TableCell>类型</TableCell>
                  <TableCell>操作</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {cases.map((c, idx) => (
                  <TableRow
                    key={c.id}
                    hover
                    selected={idx === selectedIndex}
                    onClick={() => setSelectedIndex(idx)}
                    className="cursor-pointer"
                  >
                    <TableCell padding="checkbox">
                      <Checkbox
                        checked={selectedIds.has(c.id)}
                        onChange={() => toggleSelect(c.id)}
                        onClick={(e) => e.stopPropagation()}
                      />
                    </TableCell>
                    <TableCell>{(page * size) + idx + 1}</TableCell>
                    <TableCell className="max-w-xs truncate">{c.title}</TableCell>
                    <TableCell>
                      <StatusChip code={c.status} />
                    </TableCell>
                    <TableCell>
                      <Chip label={c.priority} size="small" variant="outlined" />
                    </TableCell>
                    <TableCell>
                      <Chip label={c.caseType} size="small" variant="outlined" />
                    </TableCell>
                    <TableCell>
                      <Button
                        size="small"
                        onClick={(e) => {
                          e.stopPropagation();
                          setSelectedCaseId(c.id);
                        }}
                      >
                        详情
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>

          <TablePagination
            component="div"
            count={total}
            page={page}
            onPageChange={(_, p) => setPage(p)}
            rowsPerPage={size}
            onRowsPerPageChange={(e) => setSize(Number(e.target.value))}
            rowsPerPageOptions={[10, 20, 50]}
            labelRowsPerPage="每页"
            labelDisplayedRows={({ from, to, count }) => `${from}-${to} / ${count}`}
          />

          {/* 快捷键提示 */}
          <Box className="mt-2 text-center">
            <Typography variant="caption" color="text.secondary">
              快捷键：A 采纳 · R 驳回 · E 编辑 · 焦点在输入框时忽略
            </Typography>
          </Box>
        </>
      )}

      {/* 评审抽屉 */}
      <CaseReviewDrawer
        caseId={selectedCaseId}
        onClose={() => setSelectedCaseId(null)}
        onReviewed={(updated) => {
          // 自动下一条
          setSelectedIndex((i) => Math.min(i + 1, cases.length - 1));
          refetch();
          // 不关抽屉，方便连续评审
          void updated;
        }}
      />

      {/* 用例导出弹窗（T09 #35/#36，与批量评审并列的独立入口） */}
      <CaseExportDialog
        open={exportOpen}
        onClose={() => setExportOpen(false)}
        defaultTaskId={taskIdFilter}
      />
    </Box>
  );
}
