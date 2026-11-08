package com.aittest.generation.service;

import com.aittest.common.enums.TaskScene;
import com.aittest.common.enums.TaskStatus;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.domain.GenTaskItem;

/**
 * 任务子项的执行策略：**引擎不认识"抽点"还是"生成"，只认识"执行一个 item"**。
 *
 * <p><b>为什么要抽这个接口</b>：状态机、调度、认领、退避、进度推送、SSE——这六件事在
 * 抽点和生成之间<b>完全一样</b>，只有"这一条 item 具体干什么"不同。写死在 worker 里就会长出
 * {@code if (scene == TESTPOINT_EXTRACT) ... else ...}，而第三种场景出现时必漏一条分支，
 * 且漏的方式是"编译通过、运行时静默什么都不做"。</p>
 *
 * <p><b>两阶段语义由实现方自己保证</b>：T05 的去重是单元级、取代是文档级（见 §11.1 D-26），
 * 引擎<b>不介入</b>这个边界——它只负责"把 N 个 item 跑完"。需要在全部 item 完成后做一次
 * 收尾动作的场景，覆写 {@link #onTaskFinalize}。</p>
 *
 * <p><b>收尾动作的调用时机（B1 修复后）</b>：引擎先用带状态守卫的
 * {@code GenTaskMapper.markTerminal} 抢"收尾权"，<b>只有抢到（返回 1）且置终态成功之后</b>
 * 才调用 {@code onTaskFinalize}，并把推导出的终态显式传入。这保证：
 * ① 一个任务最多一次任务级收尾；② 已被用户取消的任务（{@code CANCELED}）不会再触发
 * 文档级的 {@code supersedeMissing}——那会把整篇文档的 ACTIVE 测试点误判成"消失了"。</p>
 *
 * @author 寇豆码
 */
public interface TaskItemHandler {

    /**
     * 本处理器负责的场景。
     *
     * @return 场景编码，用于路由（同场景只允许一个实现，注册时冲突即启动失败）
     */
    TaskScene scene();

    /**
     * 执行一条 item。
     *
     * <p><b>抛异常即视为该 item 失败</b>，由 worker 按退避策略决定是重试还是置 {@code FAILED}。
     * 预期内的失败（模型坏输出、schema 不合规）<b>应当收成 {@link ItemOutcome#failed} 而不是抛出</b>——
     * 与 T05 的 {@code safeExtract} 同一条纪律：批量执行要把 N 条的结果汇总成一份报告，
     * 抛异常只能表达"第一条失败"。</p>
     *
     * @param task 所属任务（只读上下文：docId / provider / model）
     * @param item 待执行的子项
     * @return 执行结果与度量
     * @throws Exception 程序错误（NPE 之类）——会走退避重试
     */
    ItemOutcome execute(GenTask task, GenTaskItem item) throws Exception;

    /**
     * 任务全部 item 收尾、且<b>收尾权已被抢占成功后</b>调用一次的任务级动作。
     *
     * <p>抽点场景在这里做"取代消失的测试点"（{@code supersedeMissing}）——它是文档级动作，
     * 必须等所有单元跑完（D-26）。默认什么都不做。</p>
     *
     * <p><b>实现方必须自己判 {@code terminal == CANCELED} 并直接返回</b>：引擎侧已经用
     * {@code markTerminal} 的状态守卫挡住了绝大多数取消竞态，但"已取消任务"的语义是
     * "用户主动放弃这一轮抽取"，此时没有任何证据能说明原文里的点消失了，做取代就是数据破坏。
     * 这条 guard 是第二道防线，不能省。</p>
     *
     * @param task     所属任务（引擎已在内存里把 {@code status} 置成 {@code terminal.code()}）
     * @param terminal 本任务刚刚落库的终态
     */
    default void onTaskFinalize(GenTask task, TaskStatus terminal) {
        // 多数场景不需要任务级收尾
    }

    /**
     * 一条 item 的执行结果与度量。
     *
     * @param success         是否成功（预期内的失败也走 {@code false}，但不抛异常）
     * @param produced        产出条数（抽点=测试点数，生成=用例数）
     * @param degraded        是否走了宽松解析降级（1 是，用例置信度 0.5）
     * @param promptTokens    prompt token
     * @param completionTokens completion token
     * @param costMicro       成本（微元）
     * @param latencyMs       耗时（毫秒）
     * @param errorCode       失败错误码（成功为 {@code null}）
     * @param errorMsg        面向用户的中文失败原因（成功为 {@code null}）
     */
    record ItemOutcome(boolean success, int produced, int degraded,
                       int promptTokens, int completionTokens, long costMicro, int latencyMs,
                       String errorCode, String errorMsg) {

        /** 成功结果。 */
        public static ItemOutcome ok(int produced, int promptTokens, int completionTokens,
                                     long costMicro, int latencyMs) {
            return new ItemOutcome(true, produced, 0, promptTokens, completionTokens,
                    costMicro, latencyMs, null, null);
        }

        /** 走了降级的成功结果（产出可信度打折）。 */
        public static ItemOutcome degraded(int produced, int promptTokens, int completionTokens,
                                           long costMicro, int latencyMs) {
            return new ItemOutcome(true, produced, 1, promptTokens, completionTokens,
                    costMicro, latencyMs, null, null);
        }

        /** 预期内的失败（<b>不抛异常</b>，由 worker 记账并决定是否重试）。 */
        public static ItemOutcome failed(String errorCode, String errorMsg, int latencyMs) {
            return new ItemOutcome(false, 0, 0, 0, 0, 0, latencyMs, errorCode, errorMsg);
        }
    }
}
