/**
 * 文档解析状态与文档类型的展示映射（**唯一真源**）。
 *
 * 为什么从 `pages/docs/DocList.tsx` 抽出来：
 * 「需求文档」列表与「测试点评审落地页」都要把同一份 `parseStatus` 渲染成中文 + 颜色，
 * 各写一份必然出现"同状态不同色"——`constants/status.ts` 就是为这类漂移建立的，
 * 文档状态没有理由例外。放常量层而不是 `components/common`，因为它只含纯数据、
 * 不需要渲染，任何层都能安全引用。
 *
 * 后端枚举只有三档（`common.enums.ParseStatus`，见 `DocSource.parseStatus` 的注释）：
 * `PENDING / PARSED / FAILED`。**不要自己加第四档**，加了就与后端契约脱节。
 */

/** 单个解析状态的展示元数据。 */
export interface ParseStatusMeta {
  /** 中文文案。 */
  label: string;
  /** MUI Chip color。 */
  color: 'default' | 'success' | 'error' | 'warning';
}

/**
 * 解析状态 → 中文与配色。
 *
 * ⚠️ `PARSED` **不在** `constants/status.ts` 的 `ALL_STATUS` 里（那张表覆盖的是
 * 任务 / 用例 / 执行结果三个域）。所以 `StatusChip code="PARSED"` 会走"未知 code"分支，
 * 渲染成灰色 outlined 加裸英文 `PARSED`——文档状态必须走下面的 `getParseStatusMeta`，
 * 不要图省事塞给 `StatusChip`，否则列表页会出现「待解析 / PARSED / 解析失败」这种混排。
 */
export const PARSE_STATUS_META: Record<string, ParseStatusMeta> = {
  PENDING: { label: '待解析', color: 'warning' },
  PARSED: { label: '已解析', color: 'success' },
  FAILED: { label: '解析失败', color: 'error' },
};

/**
 * 文档类型编码 → 中文名。
 *
 * 后端 `DocType` 新增类型时这里会漏，所以查不到时调用方要回退显示裸 code
 * （`DOC_TYPE_LABEL[code] ?? code`），而不是显示空白。
 */
export const DOC_TYPE_LABEL: Record<string, string> = {
  MD: 'Markdown',
  TXT: '纯文本',
  DOCX: 'Word',
  OPENAPI_JSON: 'OpenAPI',
};

/**
 * 按解析状态码查展示元数据。
 *
 * 未知 code（例如后端将来新增了状态而前端没同步）回退成"裸 code + 灰色"，
 * 而不是显示空白或崩溃——列表页宁可露出原始编码，也不要静默丢信息。
 *
 * @param code 解析状态码，如 `PARSED`
 * @returns 展示元数据
 */
export function getParseStatusMeta(code: string): ParseStatusMeta {
  return PARSE_STATUS_META[code] ?? { label: code, color: 'default' };
}
