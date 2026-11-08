package com.aittest.generation.service;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.TaskStatus;

/**
 * 任务状态机：**唯一**允许改 {@code gen_task.status} 的地方。
 *
 * <p><b>为什么状态机要单独成类</b>：状态流转规则一旦散落在 service / dispatcher / worker
 * 三处，就会出现"worker 置 FAILED 但 dispatcher 又把它推回 RUNNING"这种竞态，
 * 而且每处都只写了一半规则、单看都合理。集中之后，非法流转<b>直接抛 40901</b>，
 * 而不是静默吞掉——静默吞掉的状态机等于没有状态机。</p>
 *
 * <p><b>合法流转</b>（终态见 {@link TaskStatus#isTerminal()}）：</p>
 * <pre>
 *   PENDING ──► RUNNING ──► SUCCESS
 *      │           │   ├──► PARTIAL
 *      │           │   ├──► FAILED
 *      │           │   └──► CANCELED
 *      └───────────┴──────► CANCELED
 * </pre>
 * 注意 {@code PENDING → CANCELED} 是合法的：任务刚建出来还没被认领就能取消。
 * 反过来 {@code SUCCESS → RUNNING} 之类一律非法，包括"终态之间互跳"。
 *
 * <p><b>{@code CANCELED} 只能由用户显式触发</b>，其它终态是系统判定的结果。
 * 把两者混在一起（比如超时也算 CANCELED）会让"用户主动放弃"与"系统判定失败"
 * 在指标里分不开，而这两件事的处置动作完全不同。</p>
 *
 * @author 寇豆码
 */
public final class TaskStateMachine {

    private TaskStateMachine() {
    }

    /**
     * 校验一次状态流转是否合法。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @throws BizException {@code 40901} 流转非法（含消息：from → to）
     */
    public static void assertTransition(TaskStatus from, TaskStatus to) {
        if (from == to) {
            // 幂等：已是目标状态，视为合法（多 worker 同时收尾时不报错）
            return;
        }
        if (from.isTerminal()) {
            throw new BizException(ErrorCode.E_STATE_ILLEGAL,
                    "任务已进入终态「" + from.label() + "」，不能再变更为「" + to.label() + "」");
        }
        boolean legal = switch (from) {
            case PENDING -> to == TaskStatus.RUNNING || to == TaskStatus.CANCELED;
            case RUNNING -> to == TaskStatus.SUCCESS || to == TaskStatus.PARTIAL
                    || to == TaskStatus.FAILED || to == TaskStatus.CANCELED;
            default -> false;
        };
        if (!legal) {
            throw new BizException(ErrorCode.E_STATE_ILLEGAL,
                    "非法的任务状态流转：" + from.code() + " → " + to.code());
        }
    }

    /**
     * 按 item 收尾情况推导任务终态。
     *
     * <p>规则：{@code success == total} → SUCCESS；{@code success == 0} → FAILED；
     * 其余 → PARTIAL。<b>PARTIAL 不是 FAILED 的子集</b>——它表示"有产出、但有失败"，
     * 而 T07 的验收④ 明确要求"部分 item 失败时任务终态是 PARTIAL 而非 FAILED"，
     * 因为两者的重试策略不同（PARTIAL 只重跑失败项）。</p>
     *
     * @param total   item 总数
     * @param success 成功 item 数
     * @return 终态
     */
    public static TaskStatus terminalOf(int total, int success) {
        if (total <= 0) {
            return TaskStatus.FAILED;
        }
        if (success >= total) {
            return TaskStatus.SUCCESS;
        }
        return success == 0 ? TaskStatus.FAILED : TaskStatus.PARTIAL;
    }
}
