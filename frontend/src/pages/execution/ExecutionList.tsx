/**
 * 执行结果批次列表（`/executions`）。
 *
 * <p><b>页面职责</b>：把"导过哪些批次的执行结果、各批次的通过/失败/映射情况"一眼说清，
 * 并提供导入入口。失败明细要进详情页看，列表**不展开**明细（一屏塞两套信息，
 * 两套都读不清）。</p>
 *
 * <p><b>三态顺序</b>：`isLoading → isError → rows.length === 0 → 表格`。
 * 请求失败时 `data?.records ?? []` 同样是空数组，顺序反了就会把「加载失败」
 * 显示成「还没有导入过执行结果」，还配一句"点右上角『导入结果』"——
 * 用户会去做一件刚失败过的事（同 `CaseBoard.tsx` / `TestPointReviewPage.tsx` 的教训）。</p>
 *
 * <p><b>字典容错</b>：来源格式下拉来自 `/dict/execution-source-types`，
 * 这个接口挂了只让下拉退化成「全部」，**不能整页报错**——列表数据本身还能看。</p>
 */

import { useMemo, useState } from 'react';
import { useNavigate, Link as RouterLink } from 'react-router-dom';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Link from '@mui/material/Link';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TablePagination from '@mui/material/TablePagination';
import TableRow from '@mui/material/TableRow';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import dayjs from 'dayjs';
import { useQuery } from '@tanstack/react-query';
import PageHeader from '@/components/common/PageHeader';
import EmptyState from '@/components/common/EmptyState';
import ExecutionImportDialog from '@/pages/execution/ExecutionImportDialog';
import { describeMappedRate, formatDurationMs, formatMappedRate } from '@/pages/execution/format';
import { fetchExecutionSourceTypes, listExecutions } from '@/api/execution';
import { usePageQuery } from '@/hooks/usePageQuery';

/** 每页条数选项（与其它列表页一致）。 */
const ROWS_PER_PAGE_OPTIONS = [10, 20, 50];

/**
 * 执行结果批次列表页。
 *
 * @returns 页面
 */
export default function ExecutionList(): JSX.Element {
  const navigate = useNavigate();
  const { page, size, setPage, setSize, query, resetPage } = usePageQuery();

  const [sourceType, setSourceType] = useState<string>('');
  const [keyword, setKeyword] = useState<string>('');
  const [importOpen, setImportOpen] = useState<boolean>(false);

  const listQuery = useQuery({
    queryKey: ['executions', 'list', query.page, query.size, sourceType, keyword],
    queryFn: () =>
      listExecutions({
        page: query.page,
        size: query.size,
        sourceType: sourceType || undefined,
        keyword: keyword || undefined,
      }),
  });

  // 字典：失败时不抛给页面，只让下拉退化（见文件头说明）
  const sourceTypeQuery = useQuery({
    queryKey: ['executions', 'dict', 'source-types'],
    queryFn: () => fetchExecutionSourceTypes(),
    retry: false,
    staleTime: 5 * 60_000,
  });

  /** 字典项按 sortNo 排序后展示（后端可能按 code 序返回，下拉顺序应与字典配置一致）。 */
  const sourceTypeOptions = useMemo(
    () => [...(sourceTypeQuery.data ?? [])].sort((left, right) => left.sortNo - right.sortNo),
    [sourceTypeQuery.data],
  );

  /** code → label；字典不可用时返回空表，单元格退回显示裸 code（排障时它是唯一线索）。 */
  const sourceTypeLabels = useMemo(() => {
    const map: Record<string, string> = {};
    for (const item of sourceTypeOptions) {
      map[item.code] = item.label;
    }
    return map;
  }, [sourceTypeOptions]);

  const rows = listQuery.data?.records ?? [];
  // 分页 `total` 的口径已经统一成 number：共享类型 `PageResult.total: number` 就是这么声明的，
  // 后端对应地在 `common/PageResult.java` 的 `total` 上加了字段级 `@JsonSerialize` 覆盖，不让它
  // 被全局 Long→String 命中（否则同一个分页体里会出现 `"total":"1"` 与 `"totalPages":1` 自相矛盾）。
  // 这里保留 `Number()` 不是修正，而是防御：某个域若又把计数序列化成字符串，这个数字不会以 "1" 的
  // 形态流进 MUI 的 `count`（隐式转换"看起来能用"，但类型是假的）。
  const total = Number(listQuery.data?.total ?? 0);

  /** 打开某一批次的明细。 */
  const openDetail = (id: string): void => {
    navigate(`/executions/${id}`);
  };

  return (
    <Box className="mx-auto max-w-7xl p-6">
      <PageHeader
        title="执行结果"
        subtitle="导入 JUnit XML / CSV，按 classname 映射回用例"
        actions={
          <Button variant="contained" onClick={() => setImportOpen(true)}>
            导入结果
          </Button>
        }
      />

      {/* 筛选条：来源格式 + 关键词（后端只支持这 2 个筛选） */}
      <Box className="mb-4 flex flex-wrap items-center gap-3">
        <TextField
          select
          label="来源格式"
          value={sourceType}
          onChange={(event) => {
            setSourceType(event.target.value);
            resetPage();
          }}
          size="small"
          sx={{ minWidth: 150 }}
          helperText={sourceTypeQuery.isError ? '字典加载失败，仅可按全部查看' : undefined}
        >
          <MenuItem value="">全部</MenuItem>
          {sourceTypeOptions.map((option) => (
            <MenuItem key={option.code} value={option.code}>
              {option.label}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          label="关键词"
          value={keyword}
          onChange={(event) => {
            setKeyword(event.target.value);
            resetPage();
          }}
          size="small"
          sx={{ minWidth: 240 }}
          placeholder="任务号 / 批次号 / 批次名 / 文件名"
          helperText="同时匹配任务号、批次号、批次名与原始文件名"
        />
      </Box>

      {/* 三态：loading → error → empty → 表格。error 必须排在 empty 之前 */}
      {listQuery.isLoading ? (
        <EmptyState variant="loading" title="加载中…" />
      ) : listQuery.isError ? (
        <EmptyState
          variant="error"
          title="执行结果加载失败"
          description={
            listQuery.error instanceof Error
              ? `${listQuery.error.message}；这不等于「还没有导入过结果」`
              : '请检查网络或后端服务是否已启动；这不等于「还没有导入过结果」'
          }
          action={<Button onClick={() => void listQuery.refetch()}>重试</Button>}
        />
      ) : rows.length === 0 ? (
        <EmptyState
          variant="empty"
          title="还没有导入过执行结果"
          description="点右上角『导入结果』，上传 JUnit XML 或 CSV 结果文件"
          action={
            <Button variant="outlined" onClick={() => setImportOpen(true)}>
              导入结果
            </Button>
          }
        />
      ) : (
        <>
          <TableContainer component={Paper}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>单号</TableCell>
                  <TableCell>批次名</TableCell>
                  <TableCell>任务</TableCell>
                  <TableCell>来源</TableCell>
                  <TableCell align="right">总数</TableCell>
                  <TableCell align="right">通过 / 失败 / 跳过</TableCell>
                  <TableCell>映射率</TableCell>
                  <TableCell align="right">耗时</TableCell>
                  <TableCell>导入时间</TableCell>
                  <TableCell>操作</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((run) => (
                  <TableRow
                    key={run.id}
                    hover
                    className="cursor-pointer"
                    onClick={() => openDetail(run.id)}
                  >
                    <TableCell>{run.runNo}</TableCell>
                    <TableCell className="max-w-xs truncate">{run.name}</TableCell>
                    <TableCell>
                      {run.taskId && run.taskNo ? (
                        <Link
                          component={RouterLink}
                          to={`/tasks/${run.taskId}`}
                          underline="hover"
                          // 行本身可点进批次详情，任务链接必须拦住冒泡，
                          // 否则用户点任务号却跳到执行结果详情（两个目的地完全无关）
                          onClick={(event) => event.stopPropagation()}
                          sx={{ fontFamily: 'ui-monospace, Menlo, Consolas, monospace', fontSize: 13 }}
                        >
                          {run.taskNo}
                        </Link>
                      ) : (
                        // `taskId` 为 null 有两种成因，前端拿不到判别依据：
                        // 1) 任务被删（`ON DELETE SET NULL`）；2) V7 迁移前导入的历史批次
                        //    （当时表里还没有这一列）。
                        // 所以只能写中性词「未关联」——写「任务已删除」就是替其中一种成因
                        // 断言了另一件无法证明的事（文案比事实更确定，比留白更难被发现）。
                        <Tooltip title="任务已被删除，或该批次导入时还没有任务关联字段">
                          <Typography variant="body2" color="text.secondary">
                            未关联
                          </Typography>
                        </Tooltip>
                      )}
                    </TableCell>
                    <TableCell>
                      <Chip
                        size="small"
                        variant="outlined"
                        label={sourceTypeLabels[run.sourceType] ?? run.sourceType}
                      />
                    </TableCell>
                    <TableCell align="right">{run.totalCount}</TableCell>
                    <TableCell align="right">
                      <Box className="flex items-center justify-end gap-1">
                        <Typography variant="body2" color="success.main">
                          {run.passedCount}
                        </Typography>
                        <Typography variant="body2" color="text.secondary">
                          /
                        </Typography>
                        <Typography
                          variant="body2"
                          color={run.failedCount > 0 ? 'error.main' : 'text.secondary'}
                        >
                          {run.failedCount}
                        </Typography>
                        <Typography variant="body2" color="text.secondary">
                          /
                        </Typography>
                        <Typography variant="body2" color="text.secondary">
                          {run.skippedCount}
                        </Typography>
                      </Box>
                    </TableCell>
                    <TableCell>
                      <Tooltip
                        title={`${describeMappedRate(run.mappedCount, run.totalCount)}；映射不上的记录仍会保留，caseId 为空`}
                      >
                        <Chip
                          size="small"
                          color={run.mappedRate >= 0.999 ? 'success' : 'default'}
                          variant={run.mappedRate >= 0.999 ? 'filled' : 'outlined'}
                          label={formatMappedRate(run.mappedRate, run.totalCount)}
                        />
                      </Tooltip>
                    </TableCell>
                    <TableCell align="right">{formatDurationMs(run.durationMs)}</TableCell>
                    <TableCell>{dayjs(run.createdAt).format('YYYY-MM-DD HH:mm')}</TableCell>
                    <TableCell>
                      <Button
                        size="small"
                        onClick={(event) => {
                          event.stopPropagation();
                          openDetail(run.id);
                        }}
                      >
                        查看明细
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
            onPageChange={(_, next) => setPage(next)}
            rowsPerPage={size}
            onRowsPerPageChange={(event) => setSize(Number(event.target.value))}
            rowsPerPageOptions={ROWS_PER_PAGE_OPTIONS}
            labelRowsPerPage="每页"
            labelDisplayedRows={({ from, to, count }) => `${from}-${to} / ${count}`}
          />
        </>
      )}

      <ExecutionImportDialog open={importOpen} onClose={() => setImportOpen(false)} />
    </Box>
  );
}
