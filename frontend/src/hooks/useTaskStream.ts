/**
 * 任务 SSE 流状态层：把帧序列折叠成 `{snapshot, items[], terminal, connection, errorFrame}`。
 *
 * **不许依赖页面**（§3.3 判断 2）：不 setQueryData、不 navigate、不 toast。
 * 副作用全部留在页面里——否则钩子无法在单测里跑（一 setQueryData 就要 QueryClientProvider）。
 *
 * ⚠️ **三条硬约束**：
 * 1. **`/items` 必须先于 SSE 增量**（§4.6）：终态后打开页面时 SSE 只有一帧 snapshot、
 *    明细一条都拿不到。渲染顺序反了会出现"先 3 条、200ms 后变 40 条"。
 *    因此本 hook 不负责拉 `/items`——那是 `TaskDetail` 的事，这里只做增量叠加。
 * 2. **45s 静默看门狗**（§4.3）：任何帧（含 heartbeat）都刷新 `lastEventAtRef`；
 *    超时判"连接假死"主动重连。**不能用"progress 是否还在来"当存活判据**——
 *    60s 的慢 item 期间 progress 可能一次都不来但连接是好的；
 *    心跳只依赖"有订阅者"，与任务是否在推进无关。
 * 3. **终态后必须 `close()`**（§4.5）：否则浏览器自动重连造成无限循环。
 */

import { useState, useEffect, useRef, useCallback } from 'react';
import {
  openTaskStream,
  SILENCE_TIMEOUT_MS,
  type StreamHandle,
} from '@/api/sse';
import {
  TERMINAL_TASK_STATUS,
  type ClassifiedFrame,
  type SnapshotPayload,
  type ItemFramePayload,
  type TaskStreamState,
} from '@/types/task';

/** hook 返回值。 */
export interface UseTaskStreamResult extends TaskStreamState {
  /** 手动重连（`connection === 'lost'` 时页面显示"重新连接"按钮调用）。 */
  reconnect: () => void;
}

/**
 * 订阅任务 SSE 流。
 *
 * @param taskId 任务 ID（为空时不订阅）
 * @returns 流状态 + 手动重连
 */
export function useTaskStream(taskId: string | undefined): UseTaskStreamResult {
  const [state, setState] = useState<TaskStreamState>({
    snapshot: null,
    items: [],
    terminal: false,
    connection: 'connecting',
    errorFrame: null,
  });
  const [reconnectKey, setReconnectKey] = useState(0);

  // 用 ref 保存帧数据避免闭包过期
  const snapshotRef = useRef<SnapshotPayload | null>(null);
  const itemsRef = useRef<Map<string, ItemFramePayload>>(new Map());
  const terminalRef = useRef(false);
  const lastEventAtRef = useRef(Date.now());
  const handleRef = useRef<StreamHandle | null>(null);

  const reconnect = useCallback(() => {
    terminalRef.current = false;
    snapshotRef.current = null;
    itemsRef.current.clear();
    lastEventAtRef.current = Date.now();
    setState({
      snapshot: null,
      items: [],
      terminal: false,
      connection: 'connecting',
      errorFrame: null,
    });
    setReconnectKey((k) => k + 1);
  }, []);

  useEffect(() => {
    if (!taskId) return;

    terminalRef.current = false;
    lastEventAtRef.current = Date.now();

    const handle = openTaskStream(taskId, {
      onFrame: (frame: ClassifiedFrame): boolean => {
        switch (frame.kind) {
          case 'snapshot':
          case 'progress':
            snapshotRef.current = frame.payload;
            setState((s) => ({
              ...s,
              snapshot: frame.payload,
              // snapshot.status ∈ 终态也算终态（§4.5：终态后必须 close）
              terminal: s.terminal || TERMINAL_TASK_STATUS.has(frame.payload.status as never),
            }));
            return TERMINAL_TASK_STATUS.has(frame.payload.status as never);

          case 'done':
            snapshotRef.current = frame.payload;
            terminalRef.current = true;
            setState((s) => ({
              ...s,
              snapshot: frame.payload,
              terminal: true,
            }));
            return true;

          case 'item':
            // 以 itemId 去重覆盖（SSE 是有损通道，同一 item 可能收到多次）
            itemsRef.current.set(frame.payload.itemId, frame.payload);
            setState((s) => ({
              ...s,
              items: Array.from(itemsRef.current.values()),
            }));
            return false;

          case 'execError':
            // 执行失败是正常业务终态（不是鉴权失败），用 summary 定终态
            snapshotRef.current = frame.payload.summary;
            terminalRef.current = true;
            setState((s) => ({
              ...s,
              snapshot: frame.payload.summary,
              terminal: true,
            }));
            return true;

          case 'rejectError':
            // 订阅被拒（40101/40102/40301/40401）——openTaskStream 内部已处理鉴权跳转
            terminalRef.current = true;
            setState((s) => ({
              ...s,
              errorFrame: frame.payload,
              terminal: true,
            }));
            return true;

          case 'heartbeat':
            // ⚠️ **只重置计时器，不 setState**（§4.3）：
            // 每 20s 一次的无意义重渲染会打断用户正在看的表格。
            // 计时器重置在 onAnyFrame 里做了，这里不需要额外操作。
            return false;

          case 'unknown':
            return false;
        }
      },
      onConnectionChange: (conn) => {
        // 终态后不再改 connection（避免 closed → connecting 抖动）
        if (terminalRef.current && conn === 'connecting') return;
        setState((s) => ({ ...s, connection: conn }));
      },
      onAnyFrame: () => {
        // 任何帧到达都刷新静默计时器（含 heartbeat）
        lastEventAtRef.current = Date.now();
      },
    });

    handleRef.current = handle;

    // 静默看门狗：定期检查是否有帧到达
    const watchdog = setInterval(() => {
      if (terminalRef.current) return;
      const elapsed = Date.now() - lastEventAtRef.current;
      if (elapsed > SILENCE_TIMEOUT_MS) {
        // 连接假死（超时没收到任何帧含 heartbeat）：清理后重启订阅
        handle.close();
        reconnect();
      }
    }, SILENCE_TIMEOUT_MS / 2);

    return () => {
      clearInterval(watchdog);
      handle.close();
      handleRef.current = null;
    };
  }, [taskId, reconnectKey, reconnect]);

  return {
    ...state,
    reconnect,
  };
}
