package com.aittest.generation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 任务子项（{@code gen_task_item}）：<b>数据库当队列</b>的那张表。
 *
 * <p><b>认领语义</b>（见 {@code GenTaskItemMapper.claimPending}）：</p>
 * <pre>{@code
 * SELECT id FROM gen_task_item
 *  WHERE status = 'PENDING' AND next_run_at <= #{nowUtc}
 *  ORDER BY id LIMIT #{limit} FOR UPDATE SKIP LOCKED
 * }</pre>
 * 靠 MySQL 8 的行锁跳过（{@code SKIP LOCKED}）保证多线程/多实例<b>不重复消费同一行</b>，
 * 这是"不引 MQ"这个决定能成立的技术前提。</p>
 *
 * <p><b>三条改代码前必须知道的约束</b>（每条都曾真实出过事）：</p>
 * <ol>
 *   <li><b>认领 SQL 里不许求当前时间</b>，比较基准 {@code nowUtc} 必须由应用侧算好绑定为参数。
 *       {@code next_run_at} 的默认值取自<b>会话时区</b>，若比较基准也在 SQL 里求（{@code NOW()}
 *       / {@code CURRENT_TIMESTAMP}），"写"和"读"都跟会话漂——应用连接钉了 UTC 时碰巧一致，
 *       一旦有人用 CLI 排查或连接池配置被改，认领窗口整体偏移 8 小时，表现为
 *       <b>"任务迟迟不被认领"或"一次性认领过量"，日志里看不出来</b>。参数化后连 DB 时钟都不依赖。</li>
 *   <li><b>{@code attempts} 上限是 3</b>，超限置 {@code FAILED} 而不是继续重试。
 *       退避靠把 {@code next_run_at} 后移实现（{@code attempts} 越大退避越久），
 *       <b>不要</b>用 {@code sleep} 在 worker 里等——那会占住线程池里的线程，
 *       8 个并发线程很快全在睡觉，队列看着有货却没人认领。</li>
 *   <li><b>{@code degraded}=1 表示"宽松解析降级产出"</b>，是区分正常产出与兜底产出的开关。
 *       指标要按它拆开看（采纳率分开算），不能把它和正常产出混在一个分母里。</li>
 * </ol>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString
@TableName("gen_task_item")
public class GenTaskItem {

    /** 主键（雪花 ID）。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属任务，外键 {@code ON DELETE CASCADE}。 */
    private Long taskId;

    /** 关联测试点（{@code CASE_GENERATE} 场景）；{@code TESTPOINT_EXTRACT} 场景为 {@code NULL}。 */
    private Long testPointId;

    /**
     * 待分析单元定位（V5 新增）：{@code operation:<operationId>} / {@code section:<seqNo>}。
     *
     * <p>worker 拿到一条 item 时必须知道<b>该抽哪个接口/哪个章节</b>，否则只能把整篇文档
     * 重抽一遍，异步引擎就退化成了同步语义。取值就是 T05 已有的 {@code sourceRef}
     * （见 {@code TestPointExtractService}），不另造概念。</p>
     *
     * <p><b>用字符串而不是 {@code operation_id}/{@code section_id} 两个外键</b>：
     * ① {@code sourceRef} 已经是单元的规范身份（T05 验收③ 要求它能反查回 operation/section），
     * 再加两列是同一概念的第二套表达；② 重解析后行 id 会变，而 {@code operationId} 字符串与
     * {@code seqNo} 稳定，按 ref 重新对上单元更耐受重解析。</p>
     *
     * <p><b>可空 + 唯一索引 {@code uk_gen_task_item_task_unit} 取 NULL 而非空串</b>：
     * MySQL 唯一索引把多个 NULL 视为互不冲突，于是"用例生成场景不填 unit_ref"不会互相撞键。
     * 写空串的话同一任务下第二条就 1062 了。</p>
     */
    private String unitRef;

    /**
     * 本单元<b>本轮产出</b>的去重键（{@code content_hash}，逗号分隔）。
     *
     * <p><b>为什么必须持久化</b>：同步路径里 {@code kept} 是内存里跨单元累加的，
     * 但异步下每个 item 各自执行、还可能跨进程重启——收尾时的 {@code supersedeMissing}
     * 要拿"本轮产出的全集"当 kept，少了它就会把没被产出的点误判成"消失了"（D-26）。
     * 只有<b>成功</b>的 item 才写；失败的 item 在收尾时改用
     * {@code TestPointService.keptHashesOfUnit} 保全它原有的点（超时 ≠ 原文里没有了）。</p>
     *
     * <p>同时它是可审计的："这个接口抽出了哪几条"不用回查模型台账。</p>
     */
    private String keptHashes;

    /** 状态：{@code PENDING/RUNNING/SUCCESS/FAILED/SKIPPED}。 */
    private String status;

    /** 已尝试次数（上限 3）。 */
    private Integer attempts;

    /** 本条 item 产出的用例数。 */
    private Integer caseCount;

    /** 是否走了宽松解析降级：1 是（用例置信度 0.5）。 */
    private Integer degraded;

    /** 错误码。 */
    private String errorCode;

    /** 错误说明（面向用户的中文）。 */
    private String errorMsg;

    /** 累计 prompt token。 */
    private Integer promptTokens;

    /** 累计 completion token。 */
    private Integer completionTokens;

    /** 累计成本（微元，1 元 = 1_000_000）。 */
    private Long costMicro;

    /** 耗时（毫秒）。 */
    private Integer latencyMs;

    /** 下次可被认领时间（UTC）。失败退避靠把它后移实现，<b>不是靠 sleep</b>。 */
    private LocalDateTime nextRunAt;

    /** 认领该 item 的 worker 标识（排障用）。 */
    private String workerId;

    /** 开始执行时间（UTC）。 */
    private LocalDateTime startedAt;

    /** 进入终态时间（UTC）。 */
    private LocalDateTime finishedAt;

    /** 创建时间（UTC，由数据库默认值写入，应用侧<b>不填</b>）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC，{@code ON UPDATE CURRENT_TIMESTAMP(3)}，应用侧<b>不填</b>）。 */
    private LocalDateTime updatedAt;
}
