import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  classifyFrame,
  nextBackoff,
  buildStreamUrl,
  openTaskStream,
  SERVER_HEARTBEAT_TICK_MS,
  SILENCE_TIMEOUT_MS,
} from '@/api/sse';
import { useAuthStore } from '@/store/authStore';
import type { ClassifiedFrame, SnapshotPayload } from '@/types/task';

// ==================== classifyFrame ====================

describe('classifyFrame — 六类帧形状判别', () => {
  it('snapshot → snapshot', () => {
    const payload = { taskId: '1', status: 'RUNNING', progress: 50, done: 5, total: 10, success: 4, failed: 1, produced: 8 };
    const frame = classifyFrame('snapshot', payload);
    expect(frame.kind).toBe('snapshot');
  });

  it('progress → progress', () => {
    const payload = { taskId: '1', status: 'RUNNING', progress: 60, done: 6, total: 10, success: 5, failed: 1, produced: 9 };
    const frame = classifyFrame('progress', payload);
    expect(frame.kind).toBe('progress');
  });

  it('item → item', () => {
    const payload = { itemId: '100', status: 'SUCCESS', caseCount: 3, attempts: 1, errorCode: '', errorMsg: '', latencyMs: 500 };
    const frame = classifyFrame('item', payload);
    expect(frame.kind).toBe('item');
  });

  it('item（被取消跳过，只有 3 个键）→ item，其余字段 undefined', () => {
    const payload = { itemId: '101', unitRef: 'operation:foo', status: 'SKIPPED' };
    const frame = classifyFrame('item', payload);
    expect(frame.kind).toBe('item');
    if (frame.kind === 'item') {
      expect(frame.payload.caseCount).toBeUndefined();
      expect(frame.payload.attempts).toBeUndefined();
      expect(frame.payload.errorCode).toBeUndefined();
      expect(frame.payload.latencyMs).toBeUndefined();
    }
  });

  it('done → done，payload 是完整 snapshot（D-47：没有 summary 子对象）', () => {
    const payload: Record<string, unknown> = {
      taskId: '1', taskNo: 'T-001', scene: 'CASE_GENERATE', status: 'SUCCESS',
      progress: 100, done: 10, total: 10, success: 10, failed: 0, produced: 25,
      startedAt: '2026-01-01T00:00:00Z', finishedAt: '2026-01-01T00:01:00Z',
      errorCode: '', errorMsg: '',
    };
    const frame = classifyFrame('done', payload);
    expect(frame.kind).toBe('done');
    if (frame.kind === 'done') {
      const snap = frame.payload as SnapshotPayload;
      expect(snap.status).toBe('SUCCESS');
      expect(snap.produced).toBe(25);
      // D-47 的核心断言：没有 summary
      expect('summary' in payload).toBe(false);
    }
  });

  it('heartbeat → heartbeat', () => {
    const frame = classifyFrame('heartbeat', { ts: 1234567890 });
    expect(frame.kind).toBe('heartbeat');
  });

  it('未知事件名 → unknown', () => {
    const frame = classifyFrame('something-new', { foo: 'bar' });
    expect(frame.kind).toBe('unknown');
  });
});

describe('classifyFrame — error 帧双形状判别（D-48）', () => {
  it('执行失败：有 errorCode 无 code → execError', () => {
    const payload = {
      taskId: '1',
      errorCode: 'E_LLM_UPSTREAM',
      errorMsg: '上游模型超时',
      summary: { taskId: '1', status: 'FAILED', progress: 50, done: 5, total: 10, success: 3, failed: 2, produced: 6 },
    };
    const frame = classifyFrame('error', payload);
    expect(frame.kind).toBe('execError');
  });

  it('订阅被拒：有 code 无 errorCode → rejectError', () => {
    const payload = {
      code: 40101,
      message: '登录已失效',
      data: null,
      traceId: 'trace-001',
    };
    const frame = classifyFrame('error', payload);
    expect(frame.kind).toBe('rejectError');
  });

  it('两种 error 帧分类结果不同（核心判别）', () => {
    const execFrame = classifyFrame('error', {
      taskId: '1', errorCode: 'E_INTERNAL', errorMsg: 'err', summary: {},
    });
    const rejectFrame = classifyFrame('error', {
      code: 40301, message: '无权限', data: null, traceId: '',
    });
    expect(execFrame.kind).toBe('execError');
    expect(rejectFrame.kind).toBe('rejectError');
    expect(execFrame.kind).not.toBe(rejectFrame.kind);
  });

  it('两种都有 code 和 errorCode → unknown（宁可判 unknown 也不误判）', () => {
    const frame = classifyFrame('error', { code: 40101, errorCode: 'E_INTERNAL' });
    expect(frame.kind).toBe('unknown');
  });

  it('两种都没有 → unknown', () => {
    const frame = classifyFrame('error', { foo: 'bar' });
    expect(frame.kind).toBe('unknown');
  });
});

// ==================== nextBackoff ====================

describe('nextBackoff — 退避序列', () => {
  it('0..6 = [1, 2, 4, 8, 8, 8, null]', () => {
    const results = Array.from({ length: 7 }, (_, i) => nextBackoff(i));
    expect(results).toEqual([1, 2, 4, 8, 8, 8, null]);
  });

  it('attempt < 0 返回 null', () => {
    expect(nextBackoff(-1)).toBeNull();
  });

  it('attempt > 6 返回 null', () => {
    expect(nextBackoff(7)).toBeNull();
    expect(nextBackoff(100)).toBeNull();
  });
});

// ==================== 常量 ====================

describe('心跳常量', () => {
  it('SILENCE_TIMEOUT_MS 恒等于 SERVER_HEARTBEAT_TICK_MS 的 2 倍', () => {
    expect(SILENCE_TIMEOUT_MS).toBe(2 * SERVER_HEARTBEAT_TICK_MS);
  });

  it('SERVER_HEARTBEAT_TICK_MS = 20 秒', () => {
    expect(SERVER_HEARTBEAT_TICK_MS).toBe(20_000);
  });
});

// ==================== buildStreamUrl ====================

describe('buildStreamUrl', () => {
  it('包含 token 参数', () => {
    useAuthStore.setState({ token: 'test-jwt-token' });
    const url = buildStreamUrl('42');
    expect(url).toContain('/api/tasks/42/stream');
    expect(url).toContain('token=test-jwt-token');
  });

  it('token 为空时仍然构建 URL（不带 undefined）', () => {
    useAuthStore.setState({ token: null });
    const url = buildStreamUrl('42');
    expect(url).not.toContain('undefined');
    expect(url).toContain('token=');
  });
});

// ==================== openTaskStream 生命周期 ====================

/** 假 EventSource：可手动触发事件。 */
class FakeEventSource {
  static instances: FakeEventSource[] = [];
  url: string;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;
  private listeners = new Map<string, Array<(e: MessageEvent) => void>>();

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  addEventListener(name: string, fn: (e: MessageEvent) => void): void {
    const arr = this.listeners.get(name) ?? [];
    arr.push(fn);
    this.listeners.set(name, arr);
  }

  close(): void {
    this.closed = true;
  }

  /** 模拟服务端推送。 */
  emit(name: string, data: string): void {
    for (const fn of this.listeners.get(name) ?? []) {
      fn({ data } as MessageEvent);
    }
  }

  /** 模拟网络断开。 */
  triggerError(): void {
    this.onerror?.();
  }
}

describe('openTaskStream — 终态后 close() 不重连（§4.5 硬约束 1）', () => {
  let OriginalEventSource: typeof EventSource;

  beforeEach(() => {
    OriginalEventSource = globalThis.EventSource;
    FakeEventSource.instances = [];
    globalThis.EventSource = FakeEventSource as unknown as typeof EventSource;
  });

  afterEach(() => {
    globalThis.EventSource = OriginalEventSource;
    vi.restoreAllMocks();
  });

  it('收到 done 帧后 close() 且不再重连', () => {
    const frames: ClassifiedFrame[] = [];
    const handle = openTaskStream('1', {
      onFrame: (f) => {
        frames.push(f);
        return f.kind === 'done';
      },
      onConnectionChange: () => {},
      onAnyFrame: () => {},
    });

    const es = FakeEventSource.instances[0];
    expect(es).toBeDefined();

    // 收到 done → 终态
    es.emit('done', JSON.stringify({ taskId: '1', status: 'SUCCESS', progress: 100, done: 5, total: 5, success: 5, failed: 0, produced: 10 }));
    expect(es.closed).toBe(true);
    expect(handle.active).toBe(false);

    // onerror 不应触发新的 EventSource
    const countBefore = FakeEventSource.instances.length;
    es.triggerError();
    expect(FakeEventSource.instances.length).toBe(countBefore);
  });

  it('收到 execError 后 close() 且不再重连', () => {
    const handle = openTaskStream('1', {
      onFrame: (f) => f.kind === 'execError',
      onConnectionChange: () => {},
      onAnyFrame: () => {},
    });

    const es = FakeEventSource.instances[0];
    es.emit('error', JSON.stringify({ taskId: '1', errorCode: 'E_LLM_UPSTREAM', errorMsg: '超时', summary: { status: 'FAILED' } }));
    expect(es.closed).toBe(true);
    expect(handle.active).toBe(false);
  });

  it('收到 snapshot.status=SUCCESS 时也判终态', () => {
    openTaskStream('1', {
      onFrame: (f) => f.kind === 'snapshot' && (f.payload as SnapshotPayload).status === 'SUCCESS',
      onConnectionChange: () => {},
      onAnyFrame: () => {},
    });

    const es = FakeEventSource.instances[0];
    es.emit('snapshot', JSON.stringify({ taskId: '1', status: 'SUCCESS', progress: 100, done: 3, total: 3, success: 3, failed: 0, produced: 5 }));
    expect(es.closed).toBe(true);
  });

  it('断线后按退避重连（attempt 0 → 1s）', () => {
    vi.useFakeTimers();
    const handle = openTaskStream('1', {
      onFrame: () => false,
      onConnectionChange: () => {},
      onAnyFrame: () => {},
    });

    const es1 = FakeEventSource.instances[0];
    es1.triggerError();

    // 1s 后重连
    vi.advanceTimersByTime(1000);
    expect(FakeEventSource.instances.length).toBe(2);
    handle.close();
    vi.useRealTimers();
  });

  it('退避 6 次后置 lost 并停止重连', () => {
    vi.useFakeTimers();
    const states: string[] = [];
    const handle = openTaskStream('1', {
      onFrame: () => false,
      onConnectionChange: (s) => states.push(s),
      onAnyFrame: () => {},
    });

    // 触发 7 次 error（0..6 次退避，第 7 次应该 lost）
    for (let i = 0; i < 7; i++) {
      const es = FakeEventSource.instances[FakeEventSource.instances.length - 1];
      es.triggerError();
      vi.advanceTimersByTime(10_000); // 足够覆盖任何退避
    }

    expect(states).toContain('lost');
    expect(handle.active).toBe(false);
    vi.useRealTimers();
  });
});
