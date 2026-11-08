package com.aittest.generation.mapper;

import com.aittest.generation.domain.GenTaskItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code gen_task_item} 的数据访问：<b>数据库当队列</b>的核心在这里。
 *
 * <p><b>为什么认领要手写 SQL</b>：{@code BaseMapper} 拼不出 {@code FOR UPDATE SKIP LOCKED}。
 * 这一句是"不引 MQ"这个决定能成立的技术前提——靠 MySQL 8 的行锁跳过保证多线程/多实例
 * 不重复消费同一行。</p>
 *
 * <p><b>{@code nowUtc} 必须由调用方算好传进来，SQL 里禁止出现 {@code NOW()} /
 * {@code CURRENT_TIMESTAMP} / {@code DATE_ADD} / 任何时间字面量</b>（验收⑧）。
 * 理由见 {@link GenTaskItem} 类注释：比较基准一旦跟会话时区漂，认领窗口会整体偏移，
 * 而且<b>日志里看不出来</b>。参数化后连 DB 时钟都不依赖。</p>
 *
 * @author 寇豆码
 */
@Mapper
public interface GenTaskItemMapper extends BaseMapper<GenTaskItem> {

    /**
     * 认领一批可执行的 item：{@code SELECT ... FOR UPDATE SKIP LOCKED}。
     *
     * <p><b>调用方必须处在事务里</b>（{@code @Transactional}），否则行锁在语句结束就释放，
     * {@code SKIP LOCKED} 等于没用，并发下两个 worker 会拿到同一批 id。
     * 认领后通常紧接着 {@link #markRunning}，两者必须在同一事务边界内。</p>
     *
     * @param limit   本次最多认领条数（= 线程池可用并发）
     * @param nowUtc  当前时刻（<b>UTC</b>，由应用侧计算后转成 {@code LocalDateTime}；
     *                <b>不得</b>让 SQL 自己求时间）
     * @return 被认领的 item id（顺序即 {@code ORDER BY id} 的顺序）；空列表表示没有可执行的
     */
    List<Long> claimPending(@Param("limit") int limit, @Param("nowUtc") LocalDateTime nowUtc);

    /**
     * 把已认领的 item 置为 {@code RUNNING}，记下 worker 标识与开始时间。
     *
     * @param ids     认领到的 id（必须是 {@link #claimPending} 刚返回的那批）
     * @param workerId worker 标识（排障用，如 {@code node1-t3}）
     * @param nowUtc  当前时刻（UTC）
     * @return 实际更新条数（应等于 {@code ids.size()}，不等说明有别的 worker 抢走了——
     *         那是事务边界写错的信号，调用方应记 WARN）
     */
    int markRunning(@Param("ids") List<Long> ids,
                    @Param("workerId") String workerId,
                    @Param("nowUtc") LocalDateTime nowUtc);

    /**
     * 失败退避：把 item 退回 {@code PENDING} 并把 {@code next_run_at} 后移。
     *
     * <p><b>退避靠后移 {@code next_run_at}，不靠 {@code sleep}</b>——sleep 会占住线程池里的线程，
     * 8 个并发线程很快全在睡觉，队列看着有货却没人认领。</p>
     *
     * @param id        item
     * @param attempts  新的尝试次数
     * @param nextRunAt 下次可被认领时间（UTC，由调用方按 attempts 算好退避间隔）
     * @param errorCode 错误码
     * @param errorMsg  面向用户的中文说明
     * @return 更新条数
     */
    int rescheduleForRetry(@Param("id") Long id,
                           @Param("attempts") int attempts,
                           @Param("nextRunAt") LocalDateTime nextRunAt,
                           @Param("errorCode") String errorCode,
                           @Param("errorMsg") String errorMsg);

    /**
     * 统计某任务下各状态的 item 数（进度聚合用，避免 N+1）。
     *
     * @param taskId 任务
     * @return {@code [PENDING, RUNNING, SUCCESS, FAILED, SKIPPED]} 各自条数（key 为 status）
     */
    List<java.util.Map<String, Object>> countByStatus(@Param("taskId") Long taskId);

    /**
     * 超时回收：把某任务下<b>仍卡在 RUNNING</b> 的 item 置成 FAILED（I7）。
     *
     * <p><b>错误码/文案必须非空</b>：这两列是 {@code NOT NULL}，worker 崩溃后没有人会再来
     * 补写它们；传 null 会被 MySQL 严格模式拒掉，而异常又被 dispatcher 的兜底 catch 吞成
     * 一条日志——表现为"回收器每 60s 跑一次，任务却永远不收尾"。</p>
     *
     * <p><b>只处理 RUNNING</b>；同样属于"未收尾"的 PENDING item 由
     * {@link #failPendingItemsOfTask} 在超时回收路径里一并处理。正常执行中的任务不会走
     * 这两个方法，所以退避重试不会被打断——只有 {@code deadline_at} 已过的任务才会被回收。</p>
     *
     * @param taskId    超时任务
     * @param nowUtc    当前时刻（UTC，应用侧算）
     * @param errorCode 任务级超时错误码（非空）
     * @param errorMsg  任务级超时说明（非空）
     * @return 实际置 FAILED 的条数（供调用方 {@code bumpCounters} 补齐计数）
     */
    int failRunningItemsOfTask(@Param("taskId") Long taskId,
                               @Param("nowUtc") LocalDateTime nowUtc,
                               @Param("errorCode") String errorCode,
                               @Param("errorMsg") String errorMsg);

    /**
     * 超时回收（<b>I7 PENDING 收口</b>）：把某超时任务下仍停在 {@code PENDING} 的 item 置成 FAILED。
     *
     * <p><b>为什么必须有这一条</b>：{@code GenTaskMapper.selectExpiredTaskIds} 选的是
     * {@code status IN ('PENDING','RUNNING')} 的任务，超时任务可能同时留有 PENDING item
     * （退避中 / 尚未认领）。只回收 RUNNING 的话，{@code GenTaskWorker.maybeFinalize}
     * 会因 {@code pending > 0} 直接 return，任务永远不收敛、回收器每 60s 空转。
     * 任务已过 deadline，这些 item 不会再有执行机会，必须一并终态。</p>
     *
     * <p>{@code error_code}/{@code error_msg} 同样非空（NOT NULL 列）。
     * <b>{@code attempts} 不 +1</b>：这条 item 本轮根本没执行过，凭空记一次尝试会污染
     * T11 的首次通过率口径。</p>
     *
     * @param taskId    超时任务
     * @param nowUtc    当前时刻（UTC，应用侧算）
     * @param errorCode 任务级超时错误码（非空）
     * @param errorMsg  任务级超时说明（非空）
     * @return 实际置 FAILED 的条数
     */
    int failPendingItemsOfTask(@Param("taskId") Long taskId,
                               @Param("nowUtc") LocalDateTime nowUtc,
                               @Param("errorCode") String errorCode,
                               @Param("errorMsg") String errorMsg);

    /**
     * 按 item id 反查它们所属的（去重）任务 id——供 {@code claimBatch} 置任务 RUNNING 用。
     *
     * <p><b>刻意不加 {@code FOR UPDATE}</b>：这里要的是 MVCC 快照读，不是加锁。调用方
     * {@code claimBatch} 已在本事务里对这批 item 行持有排他锁，再加共享锁既无意义、
     * 又会让 {@code UPDATE gen_task} 那条语句的加锁路径与建任务侧的 INSERT 交叉。
     * 取到 taskIds 后由 {@code GenTaskMapper.markRunningByItemIds} 显式 IN 更新，
     * {@code UPDATE} 只碰 {@code gen_task} 一张表。</p>
     *
     * <p>与旧的"内联子查询"等价，但把它拆成独立快照读后，锁窗口显著变小。</p>
     *
     * @param itemIds 本轮刚认领到的 item id（非空，由 {@code claimPending} 保证）
     * @return 去重后的 task id（可能为空——理论上不会，但空列表要跳过更新）
     */
    List<Long> selectTaskIdsByItemIds(@Param("itemIds") List<Long> itemIds);
}
