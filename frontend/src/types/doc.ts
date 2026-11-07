/**
 * 文档模块的 TypeScript 契约（与后端 `doc/dto/*` 一一对应，见架构文档 §4.2 / §4.4）。
 *
 * **关键约定**：后端把 Long 型 ID 全局序列化成**字符串**，所以这里的 id 一律 `string`，
 * 绝不要写 `number`——雪花 ID 超过 JS `MAX_SAFE_INTEGER`，用 number 会被静默改写。
 */

/** 解析状态（与后端 `ParseStatus` 编码一致）。 */
export type ParseStatus = 'PENDING' | 'PARSED' | 'FAILED';

/** 文档类型（与后端 `DocType` 编码一致）。 */
export type DocTypeCode = 'MD' | 'TXT' | 'DOCX' | 'OPENAPI_JSON';

/** 文档列表项（`GET /api/docs` 的 records 元素）。 */
export interface DocSourceVO {
  id: string;
  docNo: string;
  fileName: string;
  docType: string;
  fileSize: number;
  parseStatus: string;
  parseError: string;
  sectionCount: number;
  operationCount: number;
  /** ISO-8601 UTC 字符串，展示时用 dayjs 转本地时区。 */
  createdAt: string;
  updatedAt: string;
}

/** 章节（详情内嵌时为预览，`contentLength` 是原始长度）。 */
export interface DocSectionVO {
  id: string;
  heading: string;
  level: number;
  seqNo: number;
  content: string;
  contentLength: number;
}

/**
 * 接口操作。
 *
 * 三个 schema 字段**只在详情里出现**（后端用 `@JsonInclude(NON_NULL)` 保证列表不返回），
 * 所以这里声明为可选。
 */
export interface ApiOperationVO {
  id: string;
  method: string;
  path: string;
  summary: string;
  tags: string;
  seqNo: number;
  requestSchema?: string;
  responseSchema?: string;
  paramSchema?: string;
  schemaTruncated: boolean;
}

/** 文档详情（`GET /api/docs/{id}`）。 */
export interface DocDetailVO {
  id: string;
  docNo: string;
  fileName: string;
  docType: string;
  fileSize: number;
  parseStatus: string;
  parseError: string;
  sectionCount: number;
  operationCount: number;
  /**
   * 章节/接口的**真实总数**（后端 `COUNT(*)`）。
   * `sections` / `operations` 各只内嵌前 200 条，用这两个字段判断是否被截断；
   * 拿 `sections.length` 顶替会把"被截了"误显示成"只有这么多"。
   */
  totalSectionCount: number;
  totalOperationCount: number;
  createdAt: string;
  updatedAt: string;
  sections: DocSectionVO[];
  operations: ApiOperationVO[];
}

/** 上传/重解析返回（`DocService.UploadResult`）。 */
export interface DocUploadResult {
  docId: string;
  docNo: string;
  parseStatus: string;
  sectionCount: number;
  operationCount: number;
  /** true 表示"没有新建文档行"（命中同内容文档），**不代表没做事**。 */
  reused: boolean;
}

/** 文档列表查询参数。 */
export interface DocListQuery {
  page: number;
  size: number;
  keyword?: string;
  docType?: string;
  parseStatus?: string;
}
