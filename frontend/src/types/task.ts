/**
 * 任务域类型（REST VO + SSE 帧载荷）。
 *
 * **契约来源**：`GenTaskVO.java` / `GenTaskItemVO.java` / `GenTaskCreateReq.java`（record 字段逐一对应）
 * + `ProgressPublisher.java` 的 SSE 帧构造点（`snapshotOf` / `onItemDone` / `onItemSkipped` / `finish`）。
 *
 * **所有 Long 型 ID 在 JSON 里都是字符串**（后端 JacksonConfig 全局序列化为 String），
 * 与 `types/common.ts` 的约定一致，绝不要写 `number`。
 *
 * ⚠️ **SSE 帧与 REST VO 不同形**：`snapshot` 帧比 `GenTaskVO` 少 `provider/model/docId/errorMsg/createdAt`
 * 字段（`snapshotOf` 只推 14 个键）；`item` 帧不是 `GenTaskItemVO`（字段名与数量都不同）。
 * 照 VO 写帧类型会在 `done` 帧上读到 `undefined`（D-47）。
 */

// ==================== REST VO ====================

/**
 * 任务出参（列表与详情共用）。对应 `GenTaskVO` 的 18 个字段。
 *
 * ⚠️ **不含 tokens / cost**（D-51）：成本只在 `llm_call_log` 与指标接口里，
 * 顶部汇总不要渲染 token/成本，会显示空值被误判成"后端没算成本"。
 */
export interface GenTaskVO {
  taskId: string;
  taskNo: string;
  scene: string;
  status: TaskStatusCode;
  progress: number;
  done: number;
  total: number;
  success: number;
  failed: number;
  produced: number;
  provider: string;
  model: string;
  docId: string | null;
  errorCode: string;
  errorMsg: string;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string | null;
}

/**
 * 任务子项出参（`GET /api/tasks/{id}/items` 的元素）。对应 `GenTaskItemVO` 的 15 个字段。
 *
 * ⚠️ `degraded` 是**真布尔**（后端把 TINYINT 0/1 转成了 boolean），
 * 不要写 `if (item.degraded === 1)`；`errorCode` 原样透出（空串 = 无错误），
 * 是 item 级那个码（D-44 已修好），与任务级 `errorCode`（恒 `E_INTERNAL`，D-42）不是一个东西。
 */
export interface GenTaskItemVO {
  id: string;
  testPointId: string | null;
  unitRef: string | null;
  status: ItemStatusCode;
  attempts: number;
  caseCount: number;
  degraded: boolean;
  promptTokens: number;
  completionTokens: number;
  costMicro: number;
  latencyMs: number;
  errorCode: string;
  errorMsg: string;
  startedAt: string | null;
  finishedAt: string | null;
}

/**
 * 创建任务入参。对应 `GenTaskCreateReq`。
 *
 * ⚠️ **没有 `templateCode` / `timeoutSec`**（D-49）：模板由 `scene` 决定，多传的未知字段会被静默忽略。
 * `unitRefs` 与 `testPointIds` **二选一**，都传或都不传 → `40001`。
 */
export interface GenTaskCreateReq {
  /** 场景：`TESTPOINT_EXTRACT` / `CASE_GENERATE`（必填） */
  scene: TaskSceneCode;
  /** 关联文档 ID */
  docId: string;
  /** 模型供应商（可选，缺省 mock） */
  provider?: string;
  /** 模型名（可选） */
  model?: string;
  /** 待分析单元定位（抽点场景） */
  unitRefs?: string[];
  /** 关联测试点（生成场景） */
  testPointIds?: string[];
}

/** 建任务出参（`{taskId, taskNo}`，不是文档写的 `{taskId, taskNo, totalItems}`，D-49）。 */
export interface GenTaskCreateResult {
  taskId: string;
  taskNo: string;
}

/** 取消任务出参。 */
export interface GenTaskCancelResult {
  [key: string]: unknown;
}

// ==================== 状态码联合 ====================

/** 任务状态（6 个，**没有 SKIPPED**）。`TaskStatus.java` 的全部常量。 */
export type TaskStatusCode = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED' | 'CANCELED';

/** item 状态（5 个，字符串字面量，无枚举类）。 */
export type ItemStatusCode = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED';

/** 任务场景。 */
export type TaskSceneCode = 'TESTPOINT_EXTRACT' | 'CASE_GENERATE';

/** 终态集合：进入终态后 SSE 必须 `close()`，不再重连。 */
export const TERMINAL_TASK_STATUS: ReadonlySet<TaskStatusCode> = new Set<TaskStatusCode>([
  'SUCCESS',
  'PARTIAL',
  'FAILED',
  'CANCELED',
]);

// ==================== SSE 帧载荷 ====================

/**
 * `snapshot` / `progress` / `done` 三类事件的载荷（`ProgressPublisher.snapshotOf`）。
 *
 * ⚠️ **14 个字段**（规格初稿写 13，漏了 `errorMsg`，以代码为准）。
 * `done` 就是这个完整 snapshot（D-47：**没有 `summary` 子对象**，也没有
 * `totalCases`/`totalCostMicro`/`avgLatencyMs` 三个字段）。
 */
export interface SnapshotPayload {
  taskId: string;
  taskNo?: string;
  scene?: string;
  status: TaskStatusCode | string;
  progress: number;
  done: number;
  total: number;
  success: number;
  failed: number;
  produced: number;
  startedAt?: string | null;
  finishedAt?: string | null;
  errorCode?: string;
  errorMsg?: string;
}

/**
 * `item` 事件载荷（`ProgressPublisher.onItemDone`，9 个键）。
 *
 * ⚠️ **被取消跳过的 item 只有 3 个键**（`onItemSkipped` 只放 `itemId/unitRef/status`），
 * 所以 `caseCount`/`attempts`/`errorCode`/`latencyMs` 等全部可选，
 * 前端必须容忍 `undefined`（用 `??` 兜底，不要用非空断言 `!`）。
 */
export interface ItemFramePayload {
  itemId: string;
  testPointId?: string | null;
  unitRef?: string | null;
  status: ItemStatusCode | string;
  caseCount?: number;
  attempts?: number;
  errorCode?: string;
  errorMsg?: string;
  latencyMs?: number;
}

/** 执行失败的 `error` 帧（`ProgressPublisher.finish` 的 FAILED/CANCELED 分支）。 */
export interface ExecErrorPayload {
  taskId: string;
  errorCode: string;
  errorMsg: string;
  summary: SnapshotPayload;
}

/**
 * 订阅被拒的 `error` 帧（`SseErrorResponder` → `ApiResponse.fail` 同形）。
 *
 * ⚠️ **两种 error 帧的判别方式是 `'code' in payload`**（D-48）：
 * 有 `code` → 订阅被拒；有 `errorCode` → 执行失败。
 * 写成 `payload.code === 0` 之类的判断会两种都判错。
 */
export interface RejectErrorPayload {
  code: number;
  message: string;
  data: null;
  traceId: string;
}

/** `heartbeat` 事件载荷。只重置本地静默计时器，**不 setState**。 */
export interface HeartbeatPayload {
  ts: number;
}

/** 所有 SSE 帧的原始载荷联合（未经判别）。 */
export type RawSsePayload =
  | SnapshotPayload
  | ItemFramePayload
  | ExecErrorPayload
  | RejectErrorPayload
  | HeartbeatPayload
  | Record<string, unknown>;

/**
 * 判别后的帧分类（`classifyFrame` 的返回值）。
 *
 * `kind` 六选一，`payload` 按 kind 收窄。页面层只消费判别后的帧，不碰 RawSsePayload。
 */
export type ClassifiedFrame =
  | { kind: 'snapshot'; payload: SnapshotPayload }
  | { kind: 'progress'; payload: SnapshotPayload }
  | { kind: 'item'; payload: ItemFramePayload }
  | { kind: 'done'; payload: SnapshotPayload }
  | { kind: 'execError'; payload: ExecErrorPayload }
  | { kind: 'rejectError'; payload: RejectErrorPayload }
  | { kind: 'heartbeat'; payload: HeartbeatPayload }
  | { kind: 'unknown'; payload: Record<string, unknown> };

// ==================== 连接状态 ====================

/**
 * SSE 连接状态（`useTaskStream` 的 `connection` 字段）。
 *
 * - `connecting`：首次连接或重连中
 * - `open`：连接正常
 * - `lost`：退避 6 次后放弃，页面显示"重新连接"按钮
 * - `closed`：终态后主动关闭（正常结束）
 */
export type ConnectionState = 'connecting' | 'open' | 'lost' | 'closed';

/** `useTaskStream` 的完整返回值。 */
export interface TaskStreamState {
  /** 最新快照（收到 snapshot/progress/done/error 的 summary 时覆盖）。 */
  snapshot: SnapshotPayload | null;
  /** 逐 item 累积（以 itemId 去重覆盖）。**权威来源是 `/items`，这里只是增量加速**。 */
  items: ItemFramePayload[];
  /** 是否已进入终态（done / execError / snapshot.status ∈ 终态）。 */
  terminal: boolean;
  /** 连接状态。 */
  connection: ConnectionState;
  /** 订阅被拒帧（40101/40102/40301/40401 等），非 null 时页面应展示 EmptyState。 */
  errorFrame: RejectErrorPayload | null;
}
