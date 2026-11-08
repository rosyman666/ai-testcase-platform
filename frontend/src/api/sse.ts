/**
 * SSE 传输层：拼 URL、帧解析、形状判别、退避序列。**不含 React**。
 *
 * 与 `useTaskStream.ts` 分两层的理由：帧序列（snapshot 覆盖、item 追加、终态关闭、
 * 退避重连）是纯逻辑，分出来后可以用假 EventSource 在 vitest 里跑完整个重连剧本，
 * 不需要渲染 React；混在一个 hook 里，每一个"断线三次后恢复"的场景都要
 * renderHook + 假定时器 + 假 EventSource 三件事同时对齐，成本高十倍。
 *
 * ⚠️ **三种最易出错的契约**（D-47/D-48/§4.5）：
 * 1. `done` 帧是**完整 snapshot**，没有 `summary` 子对象；
 * 2. `error` 帧有**两种形状**，判别只能用 `'code' in payload`；
 * 3. **终态后必须 `es.close()`**，否则浏览器自动重连造成无限循环。
 */

import { API_BASE, handleAuthExpired } from '@/api/http';
import { useAuthStore } from '@/store/authStore';
import type {
  ClassifiedFrame,
  SnapshotPayload,
  ItemFramePayload,
  ExecErrorPayload,
  RejectErrorPayload,
  HeartbeatPayload,
} from '@/types/task';

// ==================== 常量 ====================

/**
 * 服务端心跳间隔（`SseEmitterRegistry` 的 `@Scheduled(fixedDelay=20_000)`）。
 * **修改服务端 tick 时只改这一个常量**。
 */
export const SERVER_HEARTBEAT_TICK_MS = 20_000;

/**
 * 静默超时阈值：恒等于服务端心跳 tick 的 **2 倍**（留半拍余量）。
 *
 * 为什么不是 20s：心跳与订阅起点不对齐，最坏情况订阅后要等满 20s 才收到第一帧心跳。
 * 取 2 倍留余量，取 20s 会在正常连接上误杀。
 *
 * ⚠️ **绝不写字面量**（如 `45_000`）——散落的字面量一定有一处忘改，
 * 而漏改的表现是"连接好好的却每 45s 重连一次"，没人会想到是常量没跟着变。
 */
export const SILENCE_TIMEOUT_MS = 2 * SERVER_HEARTBEAT_TICK_MS;

/** 退避序列（秒）：1 → 2 → 4 → 8 → 8 → 8 → 放弃。总尝试 6 次。 */
const BACKOFF_SEQUENCE_S: readonly (number | null)[] = [1, 2, 4, 8, 8, 8, null];

// ==================== 纯函数 ====================

/**
 * 构建 SSE 订阅 URL。
 *
 * **鉴权走 `?token=`，不带 header**（EventSource 不支持自定义 header）。
 * 服务端从不发 `id:` 行，因此不需要 `Last-Event-ID`。
 *
 * @param taskId 任务 ID
 * @returns 完整的 SSE URL
 */
export function buildStreamUrl(taskId: string): string {
  const token = useAuthStore.getState().token ?? '';
  return `${API_BASE}/tasks/${taskId}/stream?token=${encodeURIComponent(token)}`;
}

/**
 * 判断 `error` 帧的形状（D-48 的**唯一**正确判别方式）。
 *
 * - 有 `code` 字段 → 订阅被拒（`ApiResponse.fail` 同形）；
 * - 有 `errorCode` 字段 → 执行失败（`ProgressPublisher.finish` 推的）；
 * - 两者都有或都没有 → `unknown`（宁可判 unknown 也不能把执行失败当鉴权失败）。
 *
 * ⚠️ **绝对不能写** `payload.code === 0` 或 `'code' in payload` 以外的方式——
 * 把执行失败（无 `code`）当订阅被拒处理，会把用户踢回登录页。
 *
 * @param payload error 事件的载荷
 * @returns `execError` 或 `rejectError` 或 `unknown`
 */
function classifyErrorFrame(
  payload: Record<string, unknown>,
): ClassifiedFrame {
  const hasCode = 'code' in payload;
  const hasErrorCode = 'errorCode' in payload;

  if (hasCode && !hasErrorCode) {
    return { kind: 'rejectError', payload: payload as unknown as RejectErrorPayload };
  }
  if (hasErrorCode && !hasCode) {
    return { kind: 'execError', payload: payload as unknown as ExecErrorPayload };
  }
  // 两种都有或都没有：形状不符合任何一种，拒绝猜
  return { kind: 'unknown', payload };
}

/**
 * 将 SSE 原始帧（事件名 + 载荷）分类为强类型帧。
 *
 * **`done` 帧是完整 snapshot**（D-47）：直接按 `SnapshotPayload` 收窄，
 * 不要试图读 `payload.summary`——它不存在。
 *
 * @param eventName SSE 事件名（`snapshot` / `progress` / `item` / `done` / `error` / `heartbeat`）
 * @param raw 事件载荷（已 JSON.parse）
 * @returns 判别后的帧
 */
export function classifyFrame(
  eventName: string,
  raw: Record<string, unknown>,
): ClassifiedFrame {
  switch (eventName) {
    case 'snapshot':
      return { kind: 'snapshot', payload: raw as unknown as SnapshotPayload };
    case 'progress':
      return { kind: 'progress', payload: raw as unknown as SnapshotPayload };
    case 'item':
      return { kind: 'item', payload: raw as unknown as ItemFramePayload };
    case 'done':
      // D-47：done 是完整 snapshot，不是 `{taskId,status,summary:{...}}`
      return { kind: 'done', payload: raw as unknown as SnapshotPayload };
    case 'error':
      return classifyErrorFrame(raw);
    case 'heartbeat':
      return { kind: 'heartbeat', payload: raw as unknown as HeartbeatPayload };
    default:
      return { kind: 'unknown', payload: raw };
  }
}

/**
 * 获取下一次重连的退避秒数。
 *
 * 退避序列：1s → 2s → 4s → 8s → 8s → 8s → 放弃（`null`）。总尝试 **6 次**。
 *
 * @param attempt 已尝试次数（0 = 第一次重连）
 * @returns 等待秒数；超过上限返回 `null`（调用方置 `connection='lost'` 并停止重试）
 */
export function nextBackoff(attempt: number): number | null {
  if (attempt < 0 || attempt >= BACKOFF_SEQUENCE_S.length) {
    return null;
  }
  return BACKOFF_SEQUENCE_S[attempt];
}

// ==================== 事件流封装 ====================

/** SSE 事件处理器。 */
export interface StreamHandlers {
  /** 收到一帧（已判别）。返回 `true` 表示已进入终态，调用方应 `close()`。 */
  onFrame: (frame: ClassifiedFrame) => boolean;
  /** 连接状态变化。 */
  onConnectionChange: (state: 'connecting' | 'open' | 'lost' | 'closed') => void;
  /** 任何一帧到达（含 heartbeat）→ 刷新静默计时器。 */
  onAnyFrame: () => void;
}

/** 事件流控制器句柄。 */
export interface StreamHandle {
  /** 主动关闭连接（终态后必须调用）。 */
  close: () => void;
  /** 当前是否处于活跃连接。 */
  readonly active: boolean;
}

/**
 * 启动 SSE 订阅。返回可关闭的句柄。
 *
 * **三条硬约束**（§4.5）：
 * 1. 终态后必须 `close()`（收到 done / execError / snapshot.status ∈ 终态）；
 * 2. 不用 `Last-Event-ID`（服务端从不发 `id:`，浏览器手里永远空值）；
 * 3. 不依赖浏览器自带重连（它无法退避、分不清断线和 401）——自己 close、自己退避、自己 new。
 *
 * @param taskId 任务 ID
 * @param handlers 帧处理器
 * @returns 句柄
 */
export function openTaskStream(taskId: string, handlers: StreamHandlers): StreamHandle {
  let es: EventSource | null = null;
  let closed = false;
  let retryTimer: ReturnType<typeof setTimeout> | null = null;
  let attempt = 0;

  const cleanup = (): void => {
    if (retryTimer !== null) {
      clearTimeout(retryTimer);
      retryTimer = null;
    }
    if (es !== null) {
      es.close();
      es = null;
    }
  };

  const handleFrame = (eventName: string, data: string): void => {
    if (closed) return;
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(data) as Record<string, unknown>;
    } catch {
      // 脏帧不致命，跳过
      return;
    }
    const frame = classifyFrame(eventName, parsed);
    handlers.onAnyFrame();

    // 订阅被拒帧：处理鉴权/权限后进入终态
    if (frame.kind === 'rejectError') {
      const { code, message } = frame.payload;
      if (code === 40101 || code === 40102) {
        handleAuthExpired(message);
      }
      handlers.onFrame(frame);
      closed = true;
      cleanup();
      handlers.onConnectionChange('closed');
      return;
    }

    const terminal = handlers.onFrame(frame);
    if (terminal) {
      closed = true;
      cleanup();
      handlers.onConnectionChange('closed');
    }
  };

  const connect = (): void => {
    if (closed) return;
    handlers.onConnectionChange('connecting');
    const url = buildStreamUrl(taskId);
    es = new EventSource(url);

    // EventSource 的 addEventListener 接自定义事件名
    for (const name of ['snapshot', 'progress', 'item', 'done', 'error', 'heartbeat']) {
      es.addEventListener(name, (event) => {
        handleFrame(name, (event as MessageEvent).data);
      });
    }

    es.onopen = (): void => {
      attempt = 0;
      handlers.onConnectionChange('open');
    };

    es.onerror = (): void => {
      // 终态后不再重连（§4.5 硬约束 1）：服务端对终态任务推完 snapshot 就 complete()，
      // 浏览器 EventSource 在 EOF 后自动重连 → 无限循环。onerror 里也要判 closed。
      if (closed) return;

      cleanup();

      const delay = nextBackoff(attempt);
      if (delay === null) {
        // 退避 6 次后放弃
        closed = true;
        handlers.onConnectionChange('lost');
        return;
      }

      attempt += 1;
      retryTimer = setTimeout(() => {
        retryTimer = null;
        connect();
      }, delay * 1000);
    };
  };

  connect();

  return {
    close: (): void => {
      if (closed) return;
      closed = true;
      cleanup();
      handlers.onConnectionChange('closed');
    },
    get active(): boolean {
      return !closed;
    },
  };
}
