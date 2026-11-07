import { get, post, put } from '@/api/http';

/**
 * 提示词模板 API（唯一出口，见 §8.5）。
 *
 * 纪律：组件里**不得**直接 `axios.get`。走 `api/http.ts` 才能拿到统一的 401 处理、
 * `code !== 0` 归一化与 traceId 透出。
 *
 * 字段与后端 `aicore/prompt/dto/*` 一一对应；`Boolean` 字段后端返回 true/false
 * （不是 0/1），因为 VO 转换时已经归一化过——前端不要写 `isActive === 1` 这种判断。
 */

/** 模板列表项（不含正文）。 */
export interface PromptTemplateVO {
  /** 主键（后端 Long → 字符串，避免 JS 精度丢失）。 */
  id: string;
  /** 模板编码，如 `testpoint_extract`。 */
  code: string;
  /** 版本号（同 code 自增）。 */
  version: number;
  /** 场景：TESTPOINT_EXTRACT / CASE_GENERATE / CASE_REPAIR / FAILURE_TRIAGE。 */
  scene: string;
  /** 模板名称。 */
  name: string;
  /** 输出 schema 路径。 */
  outputSchema: string;
  /** 推荐模型（仅供参考，不参与路由）。 */
  modelHint: string;
  /** 是否当前生效版本。 */
  active: boolean;
  /** 创建时间（ISO-8601 UTC）。 */
  createdAt: string;
  /** 更新时间（ISO-8601 UTC）。 */
  updatedAt: string;
}

/** 模板详情（在列表项基础上多一个正文）。 */
export interface PromptTemplateDetailVO extends PromptTemplateVO {
  /** 模板正文（含 `{{var}}` 占位符）。 */
  content: string;
}

/** 试跑入参。 */
export interface PromptPreviewReq {
  /** 变量表，key 必须与模板里的 `{{var}}` 完全一致。 */
  variables: Record<string, string>;
  /** 第几次尝试（默认 1；传 2/3 可验证 `llm_call_log.attempt_no` 递增而不是覆盖）。 */
  attemptNo?: number;
  /** 是否按 output_schema 校验（默认 true）。 */
  validateSchema?: boolean;
}

/** 试跑结果。 */
export interface PromptPreviewVO {
  provider: string;
  model: string;
  content: string;
  promptTokens: number;
  completionTokens: number;
  latencyMs: number;
  /** 输出是否被修复过（为真说明模型没按"只输出 JSON"来）。 */
  repaired: boolean;
  /** 是否发生截断补全。 */
  truncated: boolean;
  /** 是否通过 schema 校验（模板未配 schema 时为 null）。 */
  schemaValid: boolean | null;
  /** 校验失败原因（含字段路径，如 `[2].priority`）。 */
  schemaError: string | null;
  /** 解析出的元素个数。 */
  parsedCount: number | null;
}

/** 模板列表查询条件。 */
export interface PromptTemplateQuery {
  scene?: string;
  activeOnly?: boolean;
}

/**
 * 模板列表（不含正文）。
 *
 * @param query 过滤条件
 * @returns 模板列表
 */
export function listPromptTemplates(query: PromptTemplateQuery = {}): Promise<PromptTemplateVO[]> {
  return get<PromptTemplateVO[]>('/prompt-templates', {
    scene: query.scene || undefined,
    activeOnly: query.activeOnly ?? false,
  });
}

/**
 * 模板详情（含正文）。
 *
 * @param id 主键
 * @returns 详情
 */
export function getPromptTemplate(id: string): Promise<PromptTemplateDetailVO> {
  return get<PromptTemplateDetailVO>(`/prompt-templates/${id}`);
}

/**
 * 试跑模板：真实调用模型并落 1 条 `llm_call_log`。
 *
 * 注意它可能产生真实费用（`ai.provider=openai-compatible` 时），所以页面上要给出提示。
 *
 * @param id 模板主键
 * @param req 变量与选项
 * @returns 输出与质量信号
 */
export function previewPromptTemplate(id: string, req: PromptPreviewReq): Promise<PromptPreviewVO> {
  return post<PromptPreviewVO>(`/prompt-templates/${id}/preview`, req);
}

/**
 * 激活指定版本（幂等）。
 *
 * @param id 主键
 * @returns 激活后的详情
 */
export function activatePromptTemplate(id: string): Promise<PromptTemplateDetailVO> {
  return put<PromptTemplateDetailVO>(`/prompt-templates/${id}/activate`);
}
