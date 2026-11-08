package com.aittest.generation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 异步任务主表（{@code gen_task}）：抽点与用例生成**共用同一个状态机**。
 *
 * <p><b>为什么不引 MQ、用数据库当队列</b>：单机部署下引入消息中间件是把"运维面"从 1 个变成 2 个，
 * 而要解决的只是"长耗时任务别阻塞 HTTP"。`gen_task_item` 的
 * {@code idx_gen_task_item_claim(status, next_run_at)} 配合 {@code FOR UPDATE SKIP LOCKED}
 * 已经能给出安全的多线程认领；真正的分布式需求（多实例广播 SSE）写在 §4.3 的已知限制里，
 * 到那天再换 Redis Pub/Sub，而不是今天先付成本。</p>
 *
 * <p><b>{@code scene} 存在的理由是复用</b>：抽点和生成是两件事，但它们的状态机、调度器、
 * 进度推送、超时退避<b>完全一样</b>。不加 scene 就要写两套，而两套必然在某天不一致。
 * 代价是 {@code gen_task_item.test_point_id} 在抽点场景为 {@code NULL}（抽点的 item 是
 * "一个接口/一个章节"，不是"一个测试点"），这个不对称是刻意换来的。</p>
 *
 * <p><b>三条改代码前必须知道的约束</b>：</p>
 * <ol>
 *   <li><b>{@code progress} 是冗余字段（0-100）</b>，为的是列表页不聚合子表。
 *       写它的地方只有 {@code ProgressPublisher} 一处；任何人另开写入路径，
 *       列表页与详情页的进度就会对不上，且不会报错。</li>
 *   <li><b>{@code deadline_at} 是任务级超时</b>（默认创建后 30 分钟），防僵尸任务。
 *       它和 item 级的 {@code next_run_at} 退避是两个不同层面的东西，不要互相替代。</li>
 *   <li><b>终态判定看 {@code TaskStatus.isTerminal()}</b>，不要自己写
 *       {@code status.equals("SUCCESS")}——终态集合会变，散落的字符串比较改起来必漏。</li>
 * </ol>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString
@TableName("gen_task")
public class GenTask {

    /** 主键（雪花 ID，Jackson 全局序列化成字符串）。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务单号 {@code T+yyyyMMdd+6位序号}，由 {@code IdGen} 分配（播种 + 重试）。 */
    private String taskNo;

    /** 场景：{@code TESTPOINT_EXTRACT} / {@code CASE_GENERATE}，见 {@code TaskScene}。 */
    private String scene;

    /** 关联文档，{@code NULL} 表示文档已被物理删（外键 {@code ON DELETE SET NULL}）。 */
    private Long docId;

    /** 所用提示词模板，{@code NULL} 表示模板已被删。 */
    private Long templateId;

    /** 模型供应商：{@code mock} / {@code openai-compatible}。 */
    private String provider;

    /** 模型名。 */
    private String model;

    /** 状态，见 {@code TaskStatus}（**用 {@code isTerminal()} 判终态，别写字符串比较**）。 */
    private String status;

    /** item 总数。 */
    private Integer totalItems;

    /** 已完成 item 数（含成功与失败）。 */
    private Integer doneItems;

    /** 成功 item 数。 */
    private Integer successItems;

    /** 失败 item 数。 */
    private Integer failedItems;

    /**
     * 本轮<b>产出</b>条数（抽点=测试点数，生成=用例数），由
     * {@code GenTaskMapper.bumpCounters} 逐条 item 原子累加（V6）。
     *
     * <p><b>与 {@link #acceptedCases} 语义严格分离</b>：后者是 T07 评审后回写的"已采纳用例数"，
     * 分母、时点、写入方都不同。历史实现把 produced 直读 accepted_cases，导致 VO/快照恒 0；
     * 若反过来把产出累加进 accepted_cases，则 T07 的采纳率分母会被本轮产出污染。</p>
     */
    private Integer producedItems;

    /** 本任务已采纳用例数（评审后回写，T07 起使用）。 */
    private Integer acceptedCases;

    /** 进度百分比 0-100。<b>冗余字段</b>，只有 {@code ProgressPublisher} 可以写。 */
    private Integer progress;

    /** 已重试轮次。 */
    private Integer retryRound;

    /** 任务级错误码（如 {@code E_TASK_DEADLINE}）。 */
    private String errorCode;

    /** 任务级错误说明（面向用户的中文）。 */
    private String errorMsg;

    /** 任务级超时时间（UTC，默认创建后 30 分钟），防僵尸任务。 */
    private LocalDateTime deadlineAt;

    /** 开始执行时间（UTC）。 */
    private LocalDateTime startedAt;

    /** 进入终态时间（UTC）。 */
    private LocalDateTime finishedAt;

    /** 创建人，关联 {@code app_user.id}。 */
    private Long createdBy;

    /** 创建时间（UTC，由数据库默认值写入，应用侧<b>不填</b>）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC，{@code ON UPDATE CURRENT_TIMESTAMP(3)}，应用侧<b>不填</b>）。 */
    private LocalDateTime updatedAt;
}
