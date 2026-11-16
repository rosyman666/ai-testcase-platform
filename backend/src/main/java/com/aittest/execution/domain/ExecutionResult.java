package com.aittest.execution.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 单条执行结果（{@code execution_result}）。
 *
 * <p><b>{@code case_id} 允许为 NULL，这是设计而不是缺陷</b>：映射不上就空着，
 * 让 {@code mapped_count / total_count} 在页面上如实显示。早期若为了"每行都有 case_id"
 * 而随手挂到一条近似用例上，就再也分不清"跑过了"和"跑的是哪条"——这不是数据完整性问题，
 * 是把统计口径做坏了。</p>
 *
 * <p><b>只有 {@code created_at}，没有 {@code updated_at}</b>：DDL 里就没有这一列
 * （明细一旦写入不再修改，唯一的例外是下一批的归因会更新
 * {@code failure_category / triage_source}，那属于下一批的语义）。实体上多定义一个
 * 不存在的列，MyBatis-Plus 拼 SELECT 时会抛 {@code Unknown column}。</p>
 *
 * <p><b>{@code id} 由调用方生成</b>（{@code IdWorker}）：{@code insertBatch} 是手写 XML，
 * 绕开 {@code BaseMapper.insert} 就走不到 MP 的主键填充，而 {@code id} 是 NOT NULL 无默认值；
 * 更关键的是 —— 有了 id 才能用一条 {@code UPDATE ... SET case_id = CASE id WHEN ...} 回填
 * 映射结果（见 {@code ExecutionResultMapper#markCaseMapped}），否则只能逐条 UPDATE。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString(exclude = {"failureMessage", "stackTrace"})
@TableName("execution_result")
public class ExecutionResult {

    /** 主键（雪花 ID，由调用方生成）。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属批次，关联 {@code execution_run.id}（{@code ON DELETE CASCADE}）。 */
    private Long runId;

    /** 测试类全名（JUnit 的 {@code classname}；CSV 的 {@code class_name} 列）。 */
    private String className;

    /** 测试方法名（JUnit 的 {@code name}；CSV 的 {@code case_name} 列）。 */
    private String caseName;

    /** 结果：PASSED / FAILED / SKIPPED，取值见 {@code common.enums.ExecutionStatus}。 */
    private String status;

    /** 单条耗时（毫秒）。 */
    private Long durationMs;

    /** 失败断言消息（页面只截前 500 字展示）。 */
    private String failureMessage;

    /** 堆栈（MEDIUMTEXT；接口只返回前 500 字）。 */
    private String stackTrace;

    /** 映射到的用例，关联 {@code test_case.id}；映射不上为 {@code null}。 */
    private Long caseId;

    /** 失败分类字典码，关联 {@code failure_category.code}；导入时由规则写入，可被人工纠正覆盖。 */
    private String failureCategory;

    /** 归因来源：RULE/LLM/MANUAL，取值见 {@code common.enums.TriageSource}。 */
    private String triageSource;

    /** 创建时间（UTC，由数据库默认值写入，应用侧<b>不填</b>）。 */
    private LocalDateTime createdAt;
}
