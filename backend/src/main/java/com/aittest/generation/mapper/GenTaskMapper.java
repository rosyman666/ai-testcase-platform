package com.aittest.generation.mapper;

import com.aittest.generation.domain.GenTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code gen_task} 的数据访问。
 *
 * <p>常规查询与写入走 {@link BaseMapper}；只有<b>进度计数</b>与<b>状态守卫写入</b>必须手写——
 * 见 {@link #bumpCounters} / {@link #markRunningByItemIds} / {@link #markTerminal}。</p>
 *
 * <p><b>{@code created_at}/{@code updated_at} 应用侧一律不填</b>：前者靠
 * {@code DEFAULT CURRENT_TIMESTAMP(3)}，后者靠 {@code ON UPDATE CURRENT_TIMESTAMP(3)}，
 * 两者都取自会话时区（JDBC 已钉 {@code +00:00}，见 §3.2.1.1）。在实体里赋值等于引入
 * "JVM 时钟"这第二个来源。</p>
 *
 * @author 寇豆码
 */
@Mapper
public interface GenTaskMapper extends BaseMapper<GenTask> {

    /**
     * 原子累加 item 计数（SQL 里 {@code = col + n}，<b>不是</b>读出来改完再写回）。
     *
     * <p><b>为什么必须原子</b>：8 个 worker 线程同时收尾各自 item 时，"读-改-写"会互相覆盖，
     * 表现为 {@code done_items} 最终比实际少几条、进度条卡在 97% 不动——
     * 而每条日志都写着"更新成功"。{@code = col + n} 由 InnoDB 行锁保证原子，
     * 没有竞态窗口。</p>
     *
     * <p><b>{@code produced} 累加到 {@code produced_items}（V6）</b>，不是
     * {@code accepted_cases}：后者是 T07 评审后回写的采纳数，语义、时点、分母都不同。</p>
     *
     * @param id       任务
     * @param done     done_items 增量（恒为 1）
     * @param success  success_items 增量（0 或 1）
     * @param failed   failed_items 增量（0 或 1）
     * @param produced produced_items 增量（本条 item 的产出条数）
     * @return 更新条数
     */
    int bumpCounters(@Param("id") Long id,
                     @Param("done") int done,
                     @Param("success") int success,
                     @Param("failed") int failed,
                     @Param("produced") int produced);

    /**
     * 落库进度百分比（冗余字段，供列表页直读）。
     *
     * <p>只由 {@code ProgressPublisher} 调用；任何人另开写入路径，列表页与详情页的
     * 进度就会对不上，且不会报错。</p>
     *
     * @param id       任务
     * @param progress 0-100
     * @return 更新条数
     */
    int updateProgress(@Param("id") Long id, @Param("progress") int progress);

    /**
     * 置终态并记下结束时间与任务级错误。
     *
     * <p>调用前<b>必须</b>先过 {@code TaskStateMachine.assertTransition}——
     * 本方法不做状态校验，是"最后一笔写"，不是"状态决策点"。SQL 自带
     * {@code status IN ('PENDING','RUNNING')} 守卫，是并发收尾的抢占点。</p>
     *
     * @param id         任务
     * @param status     终态编码
     * @param errorCode  任务级错误码（可空）
     * @param errorMsg   任务级错误说明（可空）
     * @param finishedAt 进入终态时间（UTC，应用侧算）
     * @return 更新条数（0 = 没抢到收尾权，调用方不得再做任务级动作）
     */
    int markTerminal(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("errorCode") String errorCode,
                     @Param("errorMsg") String errorMsg,
                     @Param("finishedAt") LocalDateTime finishedAt);

    /**
     * 首次认领到该任务的 item 时，把 {@code gen_task.status} 从 PENDING 置成 RUNNING
     * 并写下 {@code started_at}（I6：让状态机、注释、DB 三者一致）。
     *
     * <p><b>{@code AND status='PENDING'} 是必须的</b>：一个任务的 item 会分批认领，
     * 第二批再来时不能把 {@code started_at} 覆盖掉；同时已 CANCELED 的任务绝不能被
     * 在途的调度器"复活"成 RUNNING。</p>
     *
     * <p><b>为什么收显式 taskIds、而不是内联 {@code gen_task_item} 子查询</b>：
     * {@code claimBatch} 事务此时已持有这批 item 行的排他锁，UPDATE 里的子查询又会对
     * {@code gen_task_item} 加共享锁，等于在已锁的表上再叠一条加锁路径——它是"建任务
     * 60 条 INSERT ↔ dispatch 认领"死锁的另一半。改成普通快照读先取 taskIds（
     * {@code GenTaskItemMapper.selectTaskIdsByItemIds}，不加锁）再显式 IN 更新，
     * UPDATE 只碰 {@code gen_task} 一张表。</p>
     *
     * @param taskIds 本轮刚认领到的 item 所属任务 id（去重）
     * @param nowUtc  开始时间（UTC，应用侧算，SQL 里不求当前时间）
     * @return 实际更新条数（0 表示这些任务都已不在 PENDING，属正常）
     */
    int markRunningByItemIds(@Param("taskIds") List<Long> taskIds,
                             @Param("nowUtc") LocalDateTime nowUtc);

    /**
     * 找出已超时且仍未进入终态的任务（I7 的 deadline 回收，由 dispatcher 每 60s 节流调用）。
     *
     * <p>只查 {@code PENDING/RUNNING}，终态任务不参与回收；{@code deadline_at IS NULL}
     * 的历史行跳过，避免全表扫描时把它们一并捞出来。</p>
     *
     * @param nowUtc 当前时刻（UTC，应用侧算）
     * @return 超时任务 id
     */
    List<Long> selectExpiredTaskIds(@Param("nowUtc") LocalDateTime nowUtc);
}
