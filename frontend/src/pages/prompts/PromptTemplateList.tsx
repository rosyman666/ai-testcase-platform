import { useMemo, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Container from '@mui/material/Container';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Divider from '@mui/material/Divider';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  activatePromptTemplate,
  getPromptTemplate,
  listPromptTemplates,
  previewPromptTemplate,
  type PromptPreviewVO,
  type PromptTemplateVO,
} from '@/api/prompt';
import { toast } from '@/utils/toast';

/**
 * 提示词模板列表与试跑页（§4.2 第 19~22 号端点 + 试跑端点，见 §11.1 D-24）。
 *
 * 三个设计取舍：
 *
 * 1. **列表不取正文**：正文一条几千字，列表接口刻意不返回它（§4.4）。点"查看"才拉详情，
 *    避免首屏把几百 KB 塞进浏览器。
 * 2. **试跑要显式提示"会真实调用模型"**：`ai.provider=openai-compatible` 时每次试跑都花钱，
 *    按钮上写清楚，比事后解释账单便宜得多。
 * 3. **变量用 JSON 文本框而不是动态表单**：变量名来自模板正文，后端没有一个"变量声明"接口，
 *    动态生成表单就得在前端解析 `{{var}}`——那等于把模板语法实现两遍，两边迟早不一致。
 *    文本框 + "填入示例变量"按钮，是精度和成本之间更划算的一档。
 */

/** 场景中文名（后端编码是唯一真源，这里只做展示映射）。 */
const SCENE_LABEL: Record<string, string> = {
  TESTPOINT_EXTRACT: '测试点抽取',
  CASE_GENERATE: '用例生成',
  CASE_REPAIR: '输出修复',
  FAILURE_TRIAGE: '失败归因',
};

/** 各模板的示例变量：只为让人"点一下就能跑通"，不代表真实业务数据。 */
const SAMPLE_VARIABLES: Record<string, Record<string, string>> = {
  testpoint_extract: {
    doc_title: '用户中心 OpenAPI 3.0',
    operation_json: 'POST /api/users 创建用户；请求体 name(必填,≤64)、age(整数)、email；'
      + '响应 200 {code,data:{id}}；错误码 40001 参数校验失败、40902 唯一键冲突',
    categories: 'FUNCTIONAL/BOUNDARY/EXCEPTION/SECURITY/PERFORMANCE/COMPATIBILITY',
    max_points: '5',
  },
  case_generate: {
    api_spec: 'POST /api/users 创建用户；name 必填且 ≤64 字符；重复 name 返回 40902',
    test_point: 'name 长度达到上限 64 字符时创建成功',
    priority_rules: 'P0 资金/权限/数据一致性；P1 主流程；P2 次要分支；P3 文案与日志',
    case_types: 'FUNCTIONAL/BOUNDARY/EXCEPTION/SECURITY/PERFORMANCE/COMPATIBILITY',
    few_shot: '（无历史用例）',
  },
  case_repair: {
    original_output: '{"cases":[{"title":"缺少 name 返回 40001","priority":"高",}]}',
    validation_error: '[0].priority 必须是 P0/P1/P2/P3 之一；第 1 个元素后存在尾逗号',
    schema: '{"type":"array","items":{"required":["title","precondition","steps","expected","priority","caseType"]}}',
  },
  failure_triage: {
    failure_message: 'java.net.SocketTimeoutException: Read timed out after 30000ms '
      + 'at okhttp3.internal.http2.Http2Stream',
    case_context: '用例：创建用户成功；前置：服务已启动',
    categories: 'TIMEOUT/ASSERTION/ENV/DEPENDENCY/DATA/UNKNOWN',
  },
};

/**
 * 提示词模板列表页。
 */
export default function PromptTemplateList(): JSX.Element {
  const queryClient = useQueryClient();
  const [detailId, setDetailId] = useState<string | null>(null);
  const [variablesText, setVariablesText] = useState<string>('{}');
  const [preview, setPreview] = useState<PromptPreviewVO | null>(null);
  const [variablesError, setVariablesError] = useState<string | null>(null);

  const listQuery = useQuery({
    queryKey: ['prompt-templates'],
    queryFn: () => listPromptTemplates({}),
  });

  const detailQuery = useQuery({
    queryKey: ['prompt-template', detailId],
    queryFn: () => getPromptTemplate(detailId as string),
    enabled: detailId !== null,
  });

  const activateMutation = useMutation({
    mutationFn: (id: string) => activatePromptTemplate(id),
    onSuccess: () => {
      toast('已激活该版本', 'success');
      // 列表与详情都要失效：只刷列表会让详情弹窗里的"生效"标记留在旧值
      void queryClient.invalidateQueries({ queryKey: ['prompt-templates'] });
      void queryClient.invalidateQueries({ queryKey: ['prompt-template'] });
    },
  });

  const previewMutation = useMutation({
    mutationFn: (payload: { id: string; variables: Record<string, string> }) =>
      previewPromptTemplate(payload.id, { variables: payload.variables, attemptNo: 1 }),
    onSuccess: (result) => {
      setPreview(result);
      if (result.schemaValid === false) {
        toast('试跑完成，但输出未通过 schema 校验', 'warning');
      } else {
        toast('试跑完成', 'success');
      }
    },
  });

  const rows: PromptTemplateVO[] = useMemo(() => listQuery.data ?? [], [listQuery.data]);

  /**
   * 打开详情弹窗，并按模板编码预填示例变量。
   *
   * @param row 列表行
   */
  function openDetail(row: PromptTemplateVO): void {
    setDetailId(row.id);
    setPreview(null);
    setVariablesError(null);
    const sample = SAMPLE_VARIABLES[row.code];
    setVariablesText(sample ? JSON.stringify(sample, null, 2) : '{}');
  }

  /**
   * 解析变量文本框并触发试跑。
   */
  function handlePreview(): void {
    if (!detailId) {
      return;
    }
    let parsed: Record<string, string>;
    try {
      const raw = JSON.parse(variablesText) as Record<string, unknown>;
      // 统一转成字符串：后端变量表是 Map<String,String>，传数字会 40001
      parsed = Object.fromEntries(Object.entries(raw).map(([key, value]) => [key, String(value)]));
    } catch {
      setVariablesError('变量必须是合法 JSON，例如 {"doc_title":"用户中心"}');
      return;
    }
    setVariablesError(null);
    previewMutation.mutate({ id: detailId, variables: parsed });
  }

  return (
    <Container maxWidth="lg" className="py-6">
      <Box className="mb-4 flex items-end justify-between">
        <Box>
          <Typography variant="h5" component="h1">
            提示词模板
          </Typography>
          <Typography variant="body2" color="text.secondary" className="mt-1">
            模板按 code 版本化，同 code 只有一个生效版本；试跑会真实调用模型并记入调用台账。
          </Typography>
        </Box>
        <Button variant="outlined" onClick={() => void listQuery.refetch()}>
          刷新
        </Button>
      </Box>

      <Paper variant="outlined">
        {listQuery.isLoading ? (
          <Box className="flex items-center justify-center py-16">
            <CircularProgress size={28} />
          </Box>
        ) : listQuery.isError ? (
          <Box className="p-6">
            <Alert
              severity="error"
              action={
                <Button color="inherit" size="small" onClick={() => void listQuery.refetch()}>
                  重试
                </Button>
              }
            >
              模板列表加载失败
            </Alert>
          </Box>
        ) : rows.length === 0 ? (
          <Box className="p-10 text-center">
            <Typography color="text.secondary">
              没有模板。启动时会从 `resources/prompts/` 自动装载 4 个模板，若为空请查看启动日志里的 [PROMPT] 行。
            </Typography>
          </Box>
        ) : (
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>编码</TableCell>
                <TableCell>名称</TableCell>
                <TableCell>场景</TableCell>
                <TableCell align="center">版本</TableCell>
                <TableCell>输出 schema</TableCell>
                <TableCell align="center">状态</TableCell>
                <TableCell align="right">操作</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((row) => (
                <TableRow key={row.id} hover>
                  <TableCell className="font-mono text-xs">{row.code}</TableCell>
                  <TableCell>{row.name}</TableCell>
                  <TableCell>{SCENE_LABEL[row.scene] ?? row.scene}</TableCell>
                  <TableCell align="center">v{row.version}</TableCell>
                  <TableCell className="max-w-[220px] truncate font-mono text-xs">
                    {row.outputSchema || '—'}
                  </TableCell>
                  <TableCell align="center">
                    {row.active ? (
                      <Chip size="small" color="success" label="生效中" />
                    ) : (
                      <Chip size="small" label="历史版本" />
                    )}
                  </TableCell>
                  <TableCell align="right">
                    <Stack direction="row" spacing={1} justifyContent="flex-end">
                      <Button size="small" onClick={() => openDetail(row)}>
                        查看与试跑
                      </Button>
                      <Tooltip title={row.active ? '已是生效版本' : '切换为生效版本'}>
                        <span>
                          <Button
                            size="small"
                            disabled={row.active || activateMutation.isPending}
                            onClick={() => activateMutation.mutate(row.id)}
                          >
                            激活
                          </Button>
                        </span>
                      </Tooltip>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Paper>

      <Dialog open={detailId !== null} onClose={() => setDetailId(null)} maxWidth="md" fullWidth>
        <DialogTitle>
          {detailQuery.data ? `${detailQuery.data.name}（v${detailQuery.data.version}）` : '模板详情'}
        </DialogTitle>
        <DialogContent dividers>
          {detailQuery.isLoading ? (
            <Box className="flex justify-center py-10">
              <CircularProgress size={24} />
            </Box>
          ) : detailQuery.isError ? (
            <Alert severity="error">详情加载失败</Alert>
          ) : (
            <Stack spacing={2}>
              <Box>
                <Typography variant="subtitle2" className="mb-1">
                  模板正文
                </Typography>
                <Box className="max-h-[260px] overflow-auto rounded border border-solid border-slate-200 bg-slate-50 p-3">
                  <pre className="m-0 whitespace-pre-wrap break-words text-xs">
                    {detailQuery.data?.content}
                  </pre>
                </Box>
              </Box>

              <Divider />

              <Box>
                <Typography variant="subtitle2" className="mb-1">
                  试跑变量（JSON）
                </Typography>
                <TextField
                  fullWidth
                  multiline
                  minRows={6}
                  size="small"
                  value={variablesText}
                  onChange={(event) => setVariablesText(event.target.value)}
                  error={variablesError !== null}
                  helperText={variablesError ?? 'key 必须与模板里的 {{var}} 完全一致，缺一个就会报错（不做静默空串替换）'}
                  inputProps={{ className: 'font-mono text-xs' }}
                />
              </Box>

              {preview ? (
                <Box>
                  <Typography variant="subtitle2" className="mb-1">
                    试跑结果
                  </Typography>
                  <Stack direction="row" spacing={1} className="mb-2 flex-wrap">
                    <Chip size="small" label={`provider=${preview.provider}`} />
                    <Chip size="small" label={`model=${preview.model}`} />
                    <Chip size="small" label={`${preview.latencyMs} ms`} />
                    <Chip
                      size="small"
                      label={`tokens ${preview.promptTokens}/${preview.completionTokens}`}
                    />
                    <Chip
                      size="small"
                      color={preview.schemaValid === false ? 'error' : 'success'}
                      label={
                        preview.schemaValid === null
                          ? '未校验 schema'
                          : preview.schemaValid
                            ? 'schema 通过'
                            : 'schema 未通过'
                      }
                    />
                    {preview.repaired ? <Chip size="small" color="warning" label="输出被修复过" /> : null}
                    {preview.truncated ? <Chip size="small" color="warning" label="发生截断补全" /> : null}
                  </Stack>
                  {preview.schemaError ? (
                    <Alert severity="warning" className="mb-2">
                      {preview.schemaError}
                    </Alert>
                  ) : null}
                  <Box className="max-h-[240px] overflow-auto rounded border border-solid border-slate-200 bg-slate-50 p-3">
                    <pre className="m-0 whitespace-pre-wrap break-words text-xs">{preview.content}</pre>
                  </Box>
                </Box>
              ) : null}
            </Stack>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDetailId(null)}>关闭</Button>
          <Tooltip title="会真实调用模型（mock 模式免费），并落 1 条调用台账">
            <span>
              <Button
                variant="contained"
                disabled={previewMutation.isPending || detailQuery.isLoading}
                onClick={handlePreview}
              >
                {previewMutation.isPending ? '试跑中…' : '试跑'}
              </Button>
            </span>
          </Tooltip>
        </DialogActions>
      </Dialog>
    </Container>
  );
}
