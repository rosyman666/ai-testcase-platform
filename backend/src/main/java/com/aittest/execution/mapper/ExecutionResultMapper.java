package com.aittest.execution.mapper;

import com.aittest.execution.domain.ExecutionResult;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code execution_result} 的数据访问：批量写入与映射回填必须手写。
 *
 * <p><b>为什么批量插入要手写</b>：一次导入动辄几千条明细，逐条 {@code insert} 就是几千次
 * 往返 + 几千次行锁（与 {@code GenTaskItemMapper} 的批量动机一致）。</p>
 *
 * <p><b>为什么回填 {@code case_id} 用 {@code CASE id WHEN ...}</b>：明细的 id 由调用方生成，
 * 因此"哪一行映射到哪条用例"在插入前就已知；一条 {@code UPDATE} 能写 500 行，
 * 而逐条 UPDATE 会退化成同样的 N 次往返。</p>
 *
 * @author 寇豆码
 */
@Mapper
public interface ExecutionResultMapper extends BaseMapper<ExecutionResult> {

    /**
     * 批量插入明细（一条语句写完一批）。
     *
     * <p><b>列清单里没有 {@code created_at}</b>：交给数据库默认值
     * （{@code DEFAULT CURRENT_TIMESTAMP(3)}，会话时区已由 JDBC 钉在 UTC）。
     * 应用侧填等于给审计列引入 JVM 时钟这第二个来源。</p>
     *
     * <p><b>{@code id} 必须由调用方赋值</b>：绕开 {@code BaseMapper.insert} 就走不到 MP 的
     * 主键填充，而 {@code id} 是 NOT NULL 无默认值。</p>
     *
     * @param list 待写入明细（非空；{@code id}/{@code runId} 必须已赋值）
     * @return 插入行数
     */
    int insertBatch(@Param("list") List<ExecutionResult> list);

    /**
     * 回填 {@code case_id}（把"第 N 条结果 → 哪条用例"写成一条批量 CASE 更新）。
     *
     * <p>{@code WHERE run_id = #{runId}} 是刻意加的：{@code id IN (...)} 已经够精确，
     * 但带上 run_id 能让每一条更新都走 {@code idx_execution_result_run_status} 前缀，
     * 也顺带挡住"调用方传了不属于本批次的 id"这种编程错误（更新 0 行而不是改别人的数据）。</p>
     *
     * @param runId 批次 ID
     * @param pairs 结果行 id ↔ 用例 id 配对（非空；只应包含命中项）
     * @return 影响行数
     */
    int markCaseMapped(@Param("runId") Long runId,
                       @Param("pairs") List<CaseMapping> pairs);

    /**
     * 清掉一批明细的 {@code case_id}（重跑映射时用）。
     *
     * <p><b>为什么需要"清"</b>：重跑映射要的是<b>重算</b>，不是"补漏"。若只做新增命中，
     * 用例被重新导出换了编号之后，旧的 {@code case_id} 会永远留在库里，指向一条
     * 实际没跑过的用例——而 {@code mapped_count} 还会跟着变小，页面上看起来像
     * "映射修好了"，实际是"旧映射变成了错映射"。{@code case_id IS NOT NULL} 是为了
     * 少写无意义的行（MySQL 里把 NULL 改成 NULL 也会记 binlog）。</p>
     *
     * @param runId 批次 ID
     * @param ids   要清空的明细 id（非空，调用方按批切）
     * @return 影响行数
     */
    int clearCaseMapped(@Param("runId") Long runId, @Param("ids") List<Long> ids);

    /**
     * 按分类批量改写 {@code failure_category} 与 {@code triage_source}（规则归因落库）。
     *
     * <p><b>为什么必须批量</b>：一次导入 5000 条失败结果，逐行 UPDATE 就是 5000 次往返 +
     * 5000 次行锁，在导入这种已有大事务的场景里会把锁持有时间放大到秒级。
     * 按分类分组后只有 6 条语句（分类最多 6 种）。</p>
     *
     * <p><b>为什么带 {@code triage_source <> 'MANUAL'}</b>：导入时不可能有 MANUAL 行，
     * 但这条 SQL 将来可能被别处复用（例如重跑归因）。"人工结论不会被机器覆盖"这条规则
     * 写进语句本身，比写在调用方注释里更可靠。</p>
     *
     * @param runId    批次 ID
     * @param category 分类字典码
     * @param ids      明细 id（非空，调用方按批切）
     * @return 影响行数
     */
    int markFailureCategory(@Param("runId") Long runId,
                            @Param("category") String category,
                            @Param("ids") List<Long> ids);

    /**
     * 失败分类分布（按条数倒序）。
     *
     * <p><b>为什么返回 {@code Map} 而不是新建一个 DTO</b>：结果只在本 mapper 与
     * {@code ExecutionQueryService} 之间传一次，service 立刻转成
     * {@code ExecutionRunDetailVO.CategoryPoint}（record）。为了被 MyBatis 反序列化一次
     * 而新建一个公共类型不划算——与 {@code TestCaseMapper#selectFewShot} 复用实体的动机同一口径。
     * 值为 {@code category}(String) / {@code label}(String) / {@code cnt}(Long)。</p>
     *
     * <p><b>空列表是"这批没有归因数据"，不是占位</b>：规则归因在导入时落库，
     * 人工纠正随后可能改写；一条失败都没归因时返回 0 行是正确结论。用真实的聚合 SQL
     * （而不是硬编码空数组）意味着归因写入后这里<b>自动</b>有数据，不需要再回来改代码——
     * 也就不会留下一个"永远返回空"的假绿字段（D-39 / D-41 那类事故）。</p>
     *
     * @param runId 批次 ID
     * @return 分布行（无归因数据时为空列表，不会为 {@code null}）
     */
    List<Map<String, Object>> selectCategoryDistribution(@Param("runId") Long runId);

    /**
     * {@code case_id} 回填的配对载体：一条明细一个用例。
     *
     * <p>与 {@code TestCaseMapper.JunitRef} 同一口径：用带 getter 的普通类而不是 record，
     * 因为 MyBatis 通过 {@code getXxx()} 读属性。</p>
     */
    @Getter
    @AllArgsConstructor
    class CaseMapping {

        /** 明细主键（{@code execution_result.id}）。 */
        private final Long resultId;

        /** 用例主键（{@code test_case.id}）。 */
        private final Long caseId;
    }
}
