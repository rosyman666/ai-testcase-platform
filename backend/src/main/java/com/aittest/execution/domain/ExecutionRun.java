package com.aittest.execution.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 执行批次（{@code execution_run}）：<b>一次导入 = 一批</b>。
 *
 * <p><b>计数列在插入时一次写全，不做事后 UPDATE</b>：总数/通过/失败/跳过/映射数/总耗时
 * 全部由解析与映射阶段在内存里算完（{@code ExecutionImportService}），随 {@code INSERT}
 * 一起落库。先插 0 再 UPDATE 会留一个窗口，那里批次显示"0 条"，而明细已经在写了——
 * 页面上看起来像"导入成功但一条结果都没有"。</p>
 *
 * <p><b>{@code mapped_count} 是"映射到用例的结果条数"，不是"映射率"</b>：映射率
 * （{@code mappedRate}）在 VO 里由 {@code mapped_count / total_count} 现算。存比率会
 * 引入 {@code DECIMAL} 精度口径问题，而且 {@code total_count} 变了比率就失真。</p>
 *
 * <p><b>{@code created_at} / {@code updated_at} 由数据库写</b>（DEFAULT /
 * ON UPDATE CURRENT_TIMESTAMP(3)）：应用侧填等于给审计列引入 JVM 时钟这第二个来源。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString
@TableName("execution_run")
public class ExecutionRun {

    /** 主键（雪花 ID）。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 业务单号：{@code R+yyyyMMdd+6位序号}（{@code uk_execution_run_run_no}）。
     *
     * <p>由 {@code IdGen.generateWithRetry(NoType.RUN, ...)} 分配：撞键时由它<b>重新播种</b>
     * 后再取号，而不是简单 {@code ++}（多实例下 {@code ++} 会连续撞）。因此插入动作必须是
     * 独立事务边界——见 {@code ExecutionWriteService#insertRun}。</p>
     */
    private String runNo;

    /**
     * 关联任务，关联 {@code gen_task.id}（{@code ON DELETE SET NULL}）。
     *
     * <p><b>为什么必须落库</b>：导入时接收的 {@code taskId} 若只用于当次映射，事后就查不出
     * "这批结果属于哪个任务"——而这正是用户排查"结果和用例对不上"时问的第一句；
     * 更致命的是 {@code POST /api/executions/{id}/remap}（重跑映射）拿不到任务，
     * 只能退化成"必须重新上传文件"，那不是重跑映射。</p>
     *
     * <p><b>{@code ON DELETE SET NULL} 而不是 CASCADE</b>：执行结果是外部跑出来的<b>事实</b>，
     * 不属于任务，只是曾经关联过。任务被删就把执行历史一起抹掉，等于"删了任务就查不到
     * 当时跑成什么样"；与 {@code execution_result.case_id} 的口径一致：事实保留，引用置空。</p>
     */
    private Long taskId;

    /** 批次名称（用户可填；未填时由导入服务给出默认名）。 */
    private String name;

    /** 结果来源格式，取值见 {@code common.enums.ExecutionSourceType}（JUNIT_XML/CSV）。 */
    private String sourceType;

    /** 原始文件名（排障用：用户说"我导的是那个文件"时能对上）。 */
    private String fileName;

    /** 结果总数。 */
    private Integer totalCount;

    /** 通过数。 */
    private Integer passedCount;

    /** 失败数。 */
    private Integer failedCount;

    /** 跳过数。 */
    private Integer skippedCount;

    /** 成功映射到用例的结果条数（映射率的分子）。 */
    private Integer mappedCount;

    /** 批次总耗时（毫秒，各条 {@code duration_ms} 之和）。 */
    private Long durationMs;

    /** 创建时间（UTC，由数据库默认值写入，应用侧<b>不填</b>）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC，{@code ON UPDATE CURRENT_TIMESTAMP(3)}，应用侧<b>不填</b>）。 */
    private LocalDateTime updatedAt;
}
