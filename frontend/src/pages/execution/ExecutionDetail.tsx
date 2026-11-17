/**
 * 执行结果批次详情（`/executions/:id`）。
 *
 * <p><b>这一页的读者</b>：刚跑完一轮用例、准备处理失败的人。所以顺序刻意是
 * "这一批是什么 → 多少条通过/失败/跳过 → 失败都归到了哪几类 → 逐条明细"，
 * 而不是先甩一张大表。</p>
 *
 * <p><b>口径纪律</b>：三个状态数字卡的数据来自 `statusCounts`，失败分类来自
 * `categoryDistribution`，都是**后端聚合**的结果；页面**不得**用明细数组
 * `filter().length` 自己统计——两套口径迟早对不上，而对不上的时候没人能判断谁对。</p>
 *
 * <p><b>映射率</b>只做 `× 100` 展示（`mappedRate` 是后端算好的 0~1 小数），
 * 并且必须带分子分母说明：只给 `85%` 用户没法判断差的那 15% 是多少条。</p>
 *
 * <p><b>分类下拉是「字典 ∪ 本批分布」的合成</b>（见 {@link buildCategoryOptions}）：
 * 只取字典会漏掉"已停用但本批确实有记录"的分类（选了就筛不出来），
 * 只取分布会丢掉"本批没出现但可选"的分类。</p>
 *
 * <p><b>归因纠正</b>：`PATCH /api/executions/results/{id}` 返回**更新后的整行**，
 * 前端用它**就地替换**表格里那一行（`setQueriesData` 按前缀匹配），并失效详情
 * （`categoryDistribution` / `statusCounts` 变了）。**明细是否重拉按当前筛选决定**：
 * 改完仍符合筛选（筛选=全部，或新分类就是当前筛选值）时不重拉，保住滚动位置与页码；
 * 不再符合筛选时**必须重拉**，否则列表会"自称按 A 筛选、却躺着一条 B"。</p>
 *
 * <p><b>重跑映射</b>（#42）只改映射关系，不动失败分类与人工纠正；未关联任务的批次
 * 后端会 40001，所以 UI 侧 `taskId === null` 时直接禁用（理由确定，不是"先禁了再说"）。</p>
 *
 * <p>本页**不 import recharts**：饼图在 `FailurePie.tsx` 里按需加载。</p>
 */

import { useMemo, useState } from 'react';
import { useNavigate, useParams, Link as RouterLink } from 'react-router-dom';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Chip from '@mui/material/Chip';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Divider from '@mui/material/Divider';
import IconButton from '@mui/material/IconButton';
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
import ArrowBackRounded from '@mui/icons-material/ArrowBackRounded';
import EditRounded from '@mui/icons-material/EditRounded';
import dayjs from 'dayjs';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import PageHeader from '@/components/common/PageHeader';
import EmptyState from '@/components/common/EmptyState';
import StatusChip from '@/components/common/StatusChip';
import FailurePie from '@/pages/execution/FailurePie';
import { describeMappedRate, formatDurationMs, formatMappedRate } from '@/pages/execution/format';
import {
  fetchExecutionSourceTypes,
  fetchFailureCategories,
  getExecution,
  listExecutionResults,
  remapExecution,
  triageResult,
} from '@/api/execution';
import { ApiError } from '@/api/http';
import { usePageQuery } from '@/hooks/usePageQuery';
import { EXECUTION_STATUS } from '@/constants/status';
import { toast } from '@/utils/toast';
import {
  TRIAGE_SOURCE_LABEL,
  type ExecutionCategoryCountVO,
  type ExecutionResultVO,
} from '@/types/execution';
import type { CodeLabel, PageResult } from '@/types/common';

/** 失败信息在表格里最多展示的字数（超出折叠，全文放 tooltip）。 */
const FAILURE_MESSAGE_PREVIEW = 80;

/** 每页条数选项。 */
const ROWS_PER_PAGE_OPTIONS = [10, 20, 50];

/**
 * 兜底分类码。
 *
 * `UNKNOWN` 表示"归因不出来"，人工纠正时选它等于没纠正，所以**必须从纠正下拉里排除**；
 * 筛选下拉里保留它（筛"所有没归好类的"是合法需求）。
 */
const UNKNOWN_CATEGORY = 'UNKNOWN';

/** 分类下拉的一项（字典项或本批分布项）。 */
export interface CategoryOption {
  /** 分类码。 */
  code: string;
  /** 中文文案。 */
  label: string;
  /** 本批出现次数；**字典独有项为 `null`**（本批没出现过，没有次数）。 */
  count: number | null;
}

/**
 * 合成分类下拉选项：**本批分布 ∪ 字典**。
 *
 * 三条规则，都是为了"下拉里的每一项都能筛出东西"：
 * 1. **本批出现过的分类排前面、按 `count` 降序**——用户最先想看的就是最多的那几类；
 * 2. 字典里本批没出现过的项排在后面、按 `sortNo`（保证顺序稳定，不随批次抖动）；
 * 3. 同码以**分布项**为准（它的 label 与 count 来自这一批的真实聚合）。
 *
 * 为什么不是二选一：`enabledOnly=true` 的字典**不含已停用分类**，而历史批次里
 * 已停用分类的记录仍在（`categoryDistribution` 里能看见）——只取字典会让这些记录
 * "看得见但筛不出来"；反过来只取分布，则"本批没出现但允许选的分类"会消失，
 * 用户会以为分类被删了。
 *
 * @param dict 失败分类字典（`enabledOnly=true`）
 * @param distribution 本批的失败分类分布（后端聚合，可能为空数组）
 * @returns 合成后的下拉项（分布项在前、字典独有项在后）
 */
export function buildCategoryOptions(
  dict: ReadonlyArray<CodeLabel>,
  distribution: ReadonlyArray<ExecutionCategoryCountVO>,
): CategoryOption[] {
  const appeared: CategoryOption[] = [...distribution]
    .sort((left, right) => right.count - left.count)
    .map((item) => ({ code: item.category, label: item.label, count: item.count }));

  const appearedCodes = new Set(appeared.map((item) => item.code));
  const dictOnly: CategoryOption[] = [...dict]
    .sort((left, right) => left.sortNo - right.sortNo)
    .filter((item) => !appearedCodes.has(item.code))
    .map((item) => ({ code: item.code, label: item.label, count: null }));

  return [...appeared, ...dictOnly];
}

/**
 * 分类下拉的展示文案：有次数就带上，让用户知道"筛出来大概几条"。
 *
 * @param option 下拉项
 * @returns 形如 `断言失败（12）` 或 `环境问题`
 */
function categoryOptionLabel(option: CategoryOption): string {
  return option.count === null ? option.label : `${option.label}（${option.count}）`;
}

/**
 * 截断失败信息（**仅展示折叠，不是数据截断**）。
 *
 * 后端返回的 `failureMessage` 是完整消息（被截到 500 字的是 `stackTrace`），
 * 但一行放不下整段异常，所以表格与弹窗摘要只显示前 80 字、全文放 tooltip；
 * 点行打开的堆栈弹窗里必须给出后端原样内容。
 *
 * @param message 原文
 * @returns 最多 80 字 + 省略号
 */
function previewMessage(message: string): string {
  return message.length <= FAILURE_MESSAGE_PREVIEW
    ? message
    : `${message.slice(0, FAILURE_MESSAGE_PREVIEW)}…`;
}

/**
 * 执行结果批次详情页。
 *
 * @returns 页面
 */
export default function ExecutionDetail(): JSX.Element {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { page, size, setPage, setSize, query, resetPage } = usePageQuery();

  const [statusFilter, setStatusFilter] = useState<string>('');
  const [categoryFilter, setCategoryFilter] = useState<string>('');
  const [selectedResult, setSelectedResult] = useState<ExecutionResultVO | null>(null);
  // 归因纠正弹窗（PATCH /api/executions/results/{id}）
  const [triageTarget, setTriageTarget] = useState<ExecutionResultVO | null>(null);
  const [triageCategory, setTriageCategory] = useState<string>('');
  // 重跑映射的二次确认（会改动映射关系，必须让用户看清"不会动已有归因"再点）
  const [remapConfirmOpen, setRemapConfirmOpen] = useState<boolean>(false);

  const detailQuery = useQuery({
    queryKey: ['executions', 'detail', id],
    queryFn: () => getExecution(id),
    enabled: Boolean(id),
  });

  const resultsQuery = useQuery({
    queryKey: ['executions', 'results', id, query.page, query.size, statusFilter, categoryFilter],
    queryFn: () =>
      listExecutionResults(id, {
        page: query.page,
        size: query.size,
        status: statusFilter || undefined,
        category: categoryFilter || undefined,
      }),
    enabled: Boolean(id),
  });

  // 两个字典都**必须容忍失败**：挂了只是下拉少几个选项，页面主体照常可用
  const sourceTypeQuery = useQuery({
    queryKey: ['executions', 'dict', 'source-types'],
    queryFn: () => fetchExecutionSourceTypes(),
    retry: false,
    staleTime: 5 * 60_000,
  });
  const categoryQuery = useQuery({
    queryKey: ['executions', 'dict', 'failure-categories'],
    queryFn: () => fetchFailureCategories(),
    retry: false,
    staleTime: 5 * 60_000,
  });

  const categories = categoryQuery.data ?? [];
  const sourceTypeLabel =
    (sourceTypeQuery.data ?? []).find((item) => item.code === detailQuery.data?.sourceType)?.label ??
    detailQuery.data?.sourceType ??
    '';

  const rows = resultsQuery.data?.records ?? [];
  // 分页 `total` 的口径是 number（共享类型 `PageResult.total` 如此声明，后端用字段级
  // `@JsonSerialize` 覆盖了全局 Long→String）。这里保留 `Number()` 只作防御，不依赖隐式转换。
  const resultsTotal = Number(resultsQuery.data?.total ?? 0);

  /** 筛选下拉：分布 ∪ 字典（含已停用但有历史记录的分类）。 */
  const categoryOptions = useMemo(
    () => buildCategoryOptions(categories, detailQuery.data?.categoryDistribution ?? []),
    [categories, detailQuery.data],
  );

  /** 纠正下拉：同源选项，但**排除 UNKNOWN**（选它等于没归因）。 */
  const triageOptions = useMemo(
    () => categoryOptions.filter((option) => option.code !== UNKNOWN_CATEGORY),
    [categoryOptions],
  );

  /**
   * 该行是否可人工纠正。
   *
   * 只有"失败且还没被人工纠正过"的行才给入口：通过/跳过的行没有失败归因可改，
   * 已人工纠正的行再点一次只会覆盖自己的判断（且后端 T10-3 的口径是
   * "人工结论不被自动归因覆盖"，UI 不该鼓励反复改）。
   *
   * @param row 明细行
   * @returns 可纠正返回 true
   */
  const canTriage = (row: ExecutionResultVO): boolean =>
    row.status === 'FAILED' && row.triageSource !== 'MANUAL';

  /**
   * 打开归因纠正弹窗（预选当前分类；当前是 UNKNOWN 或空则不预选）。
   *
   * @param row 明细行
   */
  const openTriage = (row: ExecutionResultVO): void => {
    setTriageTarget(row);
    setTriageCategory(
      row.failureCategory && row.failureCategory !== UNKNOWN_CATEGORY ? row.failureCategory : '',
    );
  };

  /**
   * 人工纠正提交（`PATCH /api/executions/results/{id}`，返回更新后的整行）。
   *
   * 成功后**就地替换那一行**；要不要重拉明细，**按当前筛选决定**（团队裁决）：
   * - 筛选是「全部」或新分类 == 当前筛选值 → 这行改完仍然符合筛选，
   *   重拉只会无谓地打乱滚动位置与页码，所以不重拉；
   * - 否则（用户按「断言失败」筛着，而这一行被改成「环境问题」）→ **必须重拉**：
   *   留在列表里就是一份"自称按断言失败筛选、却躺着一条环境问题"的假结果，
   *   用户会开始怀疑整个页面。这一行消失不会让人以为操作失败——toast 已经说清了。
   *
   * 详情（`categoryDistribution` / `statusCounts`）两种情况都要刷新。
   */
  const triageMutation = useMutation({
    mutationFn: (payload: { resultId: string; failureCategory: string }) =>
      triageResult(payload.resultId, { failureCategory: payload.failureCategory }),
    onSuccess: async (updated) => {
      queryClient.setQueriesData<PageResult<ExecutionResultVO>>(
        // 前缀匹配：不关心当前翻到第几页、选了什么筛选，所有明细缓存里同 id 的行都替换
        { queryKey: ['executions', 'results', id] },
        (previous) =>
          previous
            ? {
                ...previous,
                records: previous.records.map((row) => (row.id === updated.id ? updated : row)),
              }
            : previous,
      );

      const stillMatchesFilter =
        categoryFilter === '' || categoryFilter === updated.failureCategory;

      const refreshes: Array<Promise<unknown>> = [
        queryClient.invalidateQueries({ queryKey: ['executions', 'detail', id] }),
      ];
      if (!stillMatchesFilter) {
        refreshes.push(queryClient.invalidateQueries({ queryKey: ['executions', 'results', id] }));
      }
      await Promise.all(refreshes);

      toast(
        `已把「${updated.caseName}」的失败分类改为「${updated.failureLabel ?? '未归因'}」，归因来源标记为人工`,
        'success',
      );
      setTriageTarget(null);
      setTriageCategory('');
    },
    onError: (error: unknown) => {
      // 拦截器已统一提示（含 traceId），这里只兜"没经过归一化"的意外异常
      if (error instanceof ApiError) {
        return;
      }
      toast(error instanceof Error ? error.message : '保存失败，请稍后重试', 'error');
    },
  });

  /**
   * 重跑映射（#42）。成功后详情与明细都要刷新：映射关系变了，
   * 明细的「映射到的用例」列与映射率都会变。
   */
  const remapMutation = useMutation({
    mutationFn: () => remapExecution(id),
    onSuccess: async (result) => {
      toast(`重跑完成：映射 ${result.mappedCount} / ${result.totalCount} 条`, 'success');
      setRemapConfirmOpen(false);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['executions', 'detail', id] }),
        queryClient.invalidateQueries({ queryKey: ['executions', 'results', id] }),
      ]);
    },
    onError: (error: unknown) => {
      setRemapConfirmOpen(false);
      if (error instanceof ApiError) {
        return;
      }
      toast(error instanceof Error ? error.message : '重跑映射失败，请稍后重试', 'error');
    },
  });

  /**
   * 返回列表。
   */
  const backToList = (): void => {
    navigate('/executions');
  };

  // ---------- 页面级三态（顺序：loading → error → 无数据 → 内容） ----------
  if (detailQuery.isLoading) {
    return (
      <Box className="mx-auto max-w-7xl p-6">
        <EmptyState variant="loading" title="加载中…" />
      </Box>
    );
  }

  if (detailQuery.isError) {
    return (
      <Box className="mx-auto max-w-7xl p-6">
        <EmptyState
          variant="error"
          title="批次详情加载失败"
          // 展示后端 message：404 时后端回的是「执行批次不存在或已被删除」（HTTP 404 + 带 body），
          // 比前端自己猜「可能被删了或后端没起来」准确得多
          description={
            detailQuery.error instanceof Error
              ? detailQuery.error.message
              : '请检查网络或后端服务是否已启动'
          }
          action={
            <Box className="flex gap-2">
              <Button onClick={() => void detailQuery.refetch()}>重试</Button>
              <Button onClick={backToList}>返回列表</Button>
            </Box>
          }
        />
      </Box>
    );
  }

  const detail = detailQuery.data;
  if (!detail) {
    return (
      <Box className="mx-auto max-w-7xl p-6">
        <EmptyState
          variant="empty"
          title="没有找到这个批次"
          description="请返回列表确认批次是否还存在"
          action={<Button onClick={backToList}>返回列表</Button>}
        />
      </Box>
    );
  }

  // 三值都出现（0 也保留），所以这里不需要补默认项；`statusCounts` 仍做空数组兜底，
  // 避免后端某次异常返回导致整页崩在 `.map` 上
  const statusCounts = detail.statusCounts ?? [];

  return (
    <Box className="mx-auto max-w-7xl p-6">
      <Box className="mb-2 flex items-center gap-2">
        <Button size="small" startIcon={<ArrowBackRounded />} onClick={backToList}>
          返回列表
        </Button>
        <Typography variant="caption" color="text.secondary">
          执行结果 / {detail.runNo}
        </Typography>
      </Box>

      <PageHeader title={detail.name} subtitle={`批次单号 ${detail.runNo}`} />

      {/* ---------- 批次信息 + 映射率 ---------- */}
      <Paper variant="outlined" className="mb-6 p-4">
        <Box className="grid gap-6 md:grid-cols-3">
          <Box className="grid grid-cols-2 gap-3 md:col-span-2">
            <Box>
              <Typography variant="caption" color="text.secondary">
                来源格式
              </Typography>
              <Typography variant="body1">{sourceTypeLabel}</Typography>
            </Box>
            <Box>
              <Typography variant="caption" color="text.secondary">
                关联任务
              </Typography>
              {detail.taskId && detail.taskNo ? (
                <Typography variant="body1">
                  <Link component={RouterLink} to={`/tasks/${detail.taskId}`} underline="hover">
                    {detail.taskNo}
                  </Link>
                </Typography>
              ) : (
                // 任务被删或本批未关联：说清"为什么这里是空的"，不要只留一片空白
                <Typography variant="body1" color="text.secondary">
                  任务已删除或未关联
                </Typography>
              )}
            </Box>
            <Box>
              <Typography variant="caption" color="text.secondary">
                原始文件名
              </Typography>
              <Typography variant="body1" className="break-all">
                {detail.fileName}
              </Typography>
            </Box>
            <Box>
              <Typography variant="caption" color="text.secondary">
                导入时间
              </Typography>
              <Typography variant="body1">
                {dayjs(detail.createdAt).format('YYYY-MM-DD HH:mm:ss')}
              </Typography>
            </Box>
            <Box>
              <Typography variant="caption" color="text.secondary">
                总耗时
              </Typography>
              <Typography variant="body1">{formatDurationMs(detail.durationMs)}</Typography>
            </Box>
            <Box>
              <Typography variant="caption" color="text.secondary">
                结果总条数
              </Typography>
              <Typography variant="body1">{detail.totalCount}</Typography>
            </Box>
          </Box>

          <Box>
            <Typography variant="caption" color="text.secondary">
              映射率
            </Typography>
            <Box className="flex items-baseline gap-2">
              <Typography variant="h3">{formatMappedRate(detail.mappedRate, detail.totalCount)}</Typography>
              <Chip size="small" variant="outlined" label={`${detail.mappedCount} 条已映射`} />
            </Box>
            <Typography variant="caption" color="text.secondary">
              {describeMappedRate(detail.mappedCount, detail.totalCount)}；映射不上的记录仍会保留，
              <code>case_id</code> 为空，不会被丢弃
            </Typography>
            {/* 重跑映射（#42）：次级按钮，不抢"映射率"的主角位置 */}
            <Box className="mt-2">
              <Tooltip
                title={
                  detail.taskId
                    ? '按该任务最近一次导出的编号重新匹配用例（只更新映射关系）'
                    : '该批次未关联任务，无法重跑映射'
                }
              >
                {/* disabled 的按钮不触发鼠标事件，必须用 span 包一层 tooltip 才出得来 */}
                <span>
                  <Button
                    size="small"
                    variant="outlined"
                    disabled={!detail.taskId || remapMutation.isPending}
                    onClick={() => setRemapConfirmOpen(true)}
                  >
                    {remapMutation.isPending ? '重跑中…' : '重跑映射'}
                  </Button>
                </span>
              </Tooltip>
            </Box>
          </Box>
        </Box>
      </Paper>

      {/* ---------- 状态分布（数据来自后端 statusCounts，页面不再统计） ---------- */}
      <Box className="mb-6 grid gap-4 sm:grid-cols-3">
        {statusCounts.map((item) => (
          <Card key={item.status} variant="outlined">
            <CardContent>
              {/* 文案与颜色统一由 StatusChip 提供（`constants/status.ts` 是唯一真源）；
                  后端同时给了 `label`，值相同，这里不重复渲染成两块文字 */}
              <StatusChip code={item.status} />
              <Typography variant="h4" className="mt-2">
                {item.count}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                条结果（后端聚合，与明细同源）
              </Typography>
            </CardContent>
          </Card>
        ))}
      </Box>

      {/* ---------- 失败分类分布 ---------- */}
      <Box className="mb-6">
        <FailurePie
          data={detail.categoryDistribution ?? []}
          emptyText="尚未归因：这批失败记录还没有分类"
        />
      </Box>

      <Divider className="mb-4" />

      {/* ---------- 明细筛选 ---------- */}
      <Box className="mb-4 flex flex-wrap items-center gap-3">
        <Typography variant="subtitle1" component="h2">
          执行明细
        </Typography>
        <TextField
          select
          label="状态"
          value={statusFilter}
          onChange={(event) => {
            setStatusFilter(event.target.value);
            resetPage();
          }}
          size="small"
          sx={{ minWidth: 130 }}
        >
          <MenuItem value="">全部</MenuItem>
          {Object.entries(EXECUTION_STATUS).map(([code, meta]) => (
            <MenuItem key={code} value={code}>
              {meta.label}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          select
          label="失败分类"
          value={categoryFilter}
          onChange={(event) => {
            setCategoryFilter(event.target.value);
            resetPage();
          }}
          size="small"
          sx={{ minWidth: 180 }}
          helperText={categoryQuery.isError ? '字典加载失败，仅可按全部筛选' : '选项 = 本批分布 ∪ 字典'}
        >
          <MenuItem value="">全部</MenuItem>
          {categoryOptions.map((option) => (
            <MenuItem key={option.code} value={option.code}>
              {categoryOptionLabel(option)}
            </MenuItem>
          ))}
        </TextField>
      </Box>

      {/* ---------- 明细三态：loading → error → empty → 表格 ---------- */}
      {resultsQuery.isLoading ? (
        <EmptyState variant="loading" title="加载中…" />
      ) : resultsQuery.isError ? (
        <EmptyState
          variant="error"
          title="执行明细加载失败"
          description={
            resultsQuery.error instanceof Error
              ? resultsQuery.error.message
              : '请检查网络或后端服务是否已启动'
          }
          action={<Button onClick={() => void resultsQuery.refetch()}>重试</Button>}
        />
      ) : rows.length === 0 ? (
        <EmptyState
          variant="empty"
          title="没有匹配的执行明细"
          description="如果刚设置了筛选条件，先清空筛选再看看；空结果文件同样会走到这里"
        />
      ) : (
        <>
          <TableContainer component={Paper}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>类名</TableCell>
                  <TableCell>方法名</TableCell>
                  <TableCell>状态</TableCell>
                  <TableCell align="right">耗时</TableCell>
                  <TableCell>失败信息</TableCell>
                  <TableCell>映射到的用例</TableCell>
                  <TableCell>失败分类</TableCell>
                  <TableCell>归因来源</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((row) => (
                  <TableRow
                    key={row.id}
                    hover
                    className="cursor-pointer"
                    onClick={() => setSelectedResult(row)}
                  >
                    <TableCell>
                      {/*
                        1280 宽下表格会横向溢出（内层 table 比容器宽 100+px），溢出的正好是
                        右边的「映射到的用例 / 失败分类 / 归因来源」——T10 的核心价值列。
                        截断宽度是溢出主因，所以这三列收窄到 200/220px；收窄后必须配 Tooltip，
                        否则全名彻底看不到（失败信息那列本来就有 Tooltip）。
                      */}
                      <Tooltip title={row.className}>
                        <Typography variant="body2" className="max-w-[200px] truncate">
                          {row.className}
                        </Typography>
                      </Tooltip>
                    </TableCell>
                    <TableCell>
                      <Tooltip title={row.caseName}>
                        <Typography variant="body2" className="max-w-[200px] truncate">
                          {row.caseName}
                        </Typography>
                      </Tooltip>
                    </TableCell>
                    <TableCell>
                      <StatusChip code={row.status} />
                    </TableCell>
                    <TableCell align="right">{formatDurationMs(row.durationMs)}</TableCell>
                    <TableCell>
                      {row.failureMessage ? (
                        <Tooltip title={row.failureMessage}>
                          <Typography variant="body2" className="max-w-[220px] truncate">
                            {previewMessage(row.failureMessage)}
                          </Typography>
                        </Tooltip>
                      ) : (
                        <Typography variant="body2" color="text.secondary">
                          —
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell>
                      {row.caseId ? (
                        <Typography variant="body2">用例 #{row.caseId}</Typography>
                      ) : (
                        <Typography variant="body2" color="text.secondary">
                          未映射
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell>
                      <Box className="flex items-center gap-1">
                        <Typography variant="body2">{row.failureLabel ?? '未归因'}</Typography>
                        {canTriage(row) && (
                          <Tooltip title="纠正失败分类（人工纠正后归因来源会变为 MANUAL）">
                            <IconButton
                              size="small"
                              aria-label={`纠正 ${row.caseName} 的失败分类`}
                              onClick={(event) => {
                                // 行点击是打开堆栈弹窗，纠正按钮必须拦住冒泡
                                event.stopPropagation();
                                openTriage(row);
                              }}
                            >
                              <EditRounded fontSize="inherit" />
                            </IconButton>
                          </Tooltip>
                        )}
                      </Box>
                    </TableCell>
                    <TableCell>
                      {/*
                        归因只存在于失败结果。通过 / 跳过的行 DB 里也会有 `'RULE'`
                        （`triage_source VARCHAR(16) NOT NULL DEFAULT 'RULE'` + 批量插入显式带列），
                        但那个值的意思是"没人判定过它"，显示「规则」等于替一次没发生过的
                        判定作证——按 `triageSource` 判空防不住这种情况，必须按 `status` 判。
                        这样后端 VO 是否把非 FAILED 行归一成 null，都不影响这里对不对。
                      */}
                      {row.status !== 'FAILED' ? (
                        <Typography variant="caption" color="text.secondary">
                          —
                        </Typography>
                      ) : row.triageSource === 'MANUAL' ? (
                        <Tooltip title="人工纠正过的归因不会被后续自动归因覆盖">
                          <Chip size="small" color="info" label="已人工" />
                        </Tooltip>
                      ) : (
                        <Typography variant="caption" color="text.secondary">
                          {row.triageSource
                            ? (TRIAGE_SOURCE_LABEL[row.triageSource] ?? row.triageSource)
                            : '—'}
                        </Typography>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>

          <TablePagination
            component="div"
            count={resultsTotal}
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

      <Typography variant="caption" color="text.secondary" className="mt-2 block">
        提示：单击任意一行查看完整失败信息与堆栈；失败行的铅笔图标可纠正归因分类。
      </Typography>

      {/* ---------- 单条明细弹窗（完整堆栈） ---------- */}
      <Dialog open={selectedResult !== null} onClose={() => setSelectedResult(null)} fullWidth maxWidth="md">
        <DialogTitle>
          {selectedResult ? `${selectedResult.className} · ${selectedResult.caseName}` : ''}
        </DialogTitle>
        <DialogContent dividers>
          {selectedResult && (
            <Box className="space-y-3">
              <Box className="flex flex-wrap items-center gap-3">
                <StatusChip code={selectedResult.status} />
                <Typography variant="body2" color="text.secondary">
                  耗时 {formatDurationMs(selectedResult.durationMs)}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {selectedResult.caseId ? `映射到用例 #${selectedResult.caseId}` : '未映射到用例'}
                </Typography>
                {/* 归因只存在于失败结果：通过 / 跳过的行根本没有"归因"这个概念，
                    渲染成「归因：—（—）」会被读成"归因失败了"——把"没有这个概念"
                    显示成"值缺失"是最容易误导人的一种诚实 */}
                {selectedResult.status === 'FAILED' && (
                  <Typography variant="body2" color="text.secondary">
                    归因：{selectedResult.failureLabel ?? '未归因'}
                    {/* 只有真归过因（有分类）才补来源，否则「未归因（规则）」在同一行里自相矛盾 */}
                    {selectedResult.failureCategory && selectedResult.triageSource
                      ? `（${
                          TRIAGE_SOURCE_LABEL[selectedResult.triageSource] ??
                          selectedResult.triageSource
                        }）`
                      : ''}
                  </Typography>
                )}
              </Box>

              <Box>
                <Typography variant="subtitle2" gutterBottom>
                  失败信息
                </Typography>
                <Typography variant="body2" className="whitespace-pre-wrap break-words">
                  {selectedResult.failureMessage ?? '（这条结果没有失败信息：通过或跳过的结果不带失败信息）'}
                </Typography>
              </Box>

              <Box>
                <Typography variant="subtitle2" gutterBottom>
                  堆栈
                </Typography>
                <Box
                  component="pre"
                  sx={{
                    m: 0,
                    p: 2,
                    bgcolor: 'grey.50',
                    borderRadius: 1,
                    // 等宽字体 + 可滚动 + 最高 40vh：堆栈动辄上百行，撑开整页会让人找不到关闭按钮
                    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
                    fontSize: 12,
                    lineHeight: 1.5,
                    maxHeight: '40vh',
                    overflow: 'auto',
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-word',
                  }}
                >
                  {selectedResult.stackTrace ?? '（这条结果没有堆栈；后端只保留前 500 字）'}
                </Box>
              </Box>
            </Box>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setSelectedResult(null)}>关闭</Button>
        </DialogActions>
      </Dialog>

      {/* ---------- 归因纠正弹窗（PATCH /api/executions/results/{id}） ---------- */}
      <Dialog open={triageTarget !== null} onClose={() => setTriageTarget(null)} fullWidth maxWidth="sm">
        <DialogTitle>纠正失败归因</DialogTitle>
        <DialogContent dividers>
          {triageTarget && (
            <Box className="space-y-3">
              <Typography variant="body2" color="text.secondary">
                {triageTarget.className} · {triageTarget.caseName}
              </Typography>

              <Box>
                <Typography variant="subtitle2" gutterBottom>
                  当前失败信息
                </Typography>
                <Typography variant="body2" className="break-words">
                  {triageTarget.failureMessage
                    ? previewMessage(triageTarget.failureMessage)
                    : '（这条结果没有失败信息）'}
                </Typography>
              </Box>

              <TextField
                select
                fullWidth
                label="失败分类"
                value={triageCategory}
                onChange={(event) => setTriageCategory(event.target.value)}
                helperText="选最贴切的分类；UNKNOWN 是兜底值，不作为纠正目标"
              >
                <MenuItem value="">
                  <em>请选择分类</em>
                </MenuItem>
                {triageOptions.map((option) => (
                  <MenuItem key={option.code} value={option.code}>
                    {option.label}
                  </MenuItem>
                ))}
              </TextField>

              <Alert severity="info">
                人工纠正后归因来源会变为 <strong>MANUAL</strong>，后续自动归因不会再覆盖它。
              </Alert>
            </Box>
          )}
        </DialogContent>
        <DialogActions>
          <Button
            onClick={() => {
              setTriageTarget(null);
              setTriageCategory('');
            }}
            disabled={triageMutation.isPending}
          >
            取消
          </Button>
          <Button
            variant="contained"
            // 没选分类不发请求（后端会 40001，多一次往返拿一句"参数校验失败"毫无价值）
            disabled={!triageCategory || triageMutation.isPending}
            onClick={() => {
              if (!triageTarget || !triageCategory) {
                return;
              }
              triageMutation.mutate({ resultId: triageTarget.id, failureCategory: triageCategory });
            }}
          >
            {triageMutation.isPending ? '提交中…' : '确定'}
          </Button>
        </DialogActions>
      </Dialog>

      {/* ---------- 重跑映射二次确认 ---------- */}
      <Dialog open={remapConfirmOpen} onClose={() => setRemapConfirmOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>重跑映射</DialogTitle>
        <DialogContent dividers>
          <Typography variant="body2">
            将按该任务最近一次导出的编号重新匹配用例，只更新映射关系，不会改变已有的失败分类与人工纠正。
          </Typography>
          <Alert severity="info" sx={{ mt: 2 }}>
            如果期间重新导出过 junit.zip，编号已经变了，重跑会按<strong>最新一次</strong>导出重新映射——
            之前手工纠正过的归因不受影响，但「映射到的用例」可能变化。
          </Alert>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRemapConfirmOpen(false)} disabled={remapMutation.isPending}>
            取消
          </Button>
          <Button variant="contained" disabled={remapMutation.isPending} onClick={() => remapMutation.mutate()}>
            {remapMutation.isPending ? '重跑中…' : '确认重跑'}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
