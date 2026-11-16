package com.aittest.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.execution.domain.ExecutionResult;
import com.aittest.execution.domain.ExecutionRun;
import com.aittest.execution.dto.ExecutionRemapResult;
import com.aittest.execution.dto.ExecutionResultVO;
import com.aittest.execution.mapper.ExecutionResultMapper;
import com.aittest.execution.mapper.ExecutionRunMapper;
import com.aittest.generation.support.MybatisPlusLambdaCache;
import com.aittest.system.domain.FailureCategory;
import com.aittest.system.mapper.FailureCategoryMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 人工纠正（#41）与重跑映射（#42）的契约测试（纯 Mockito）。
 *
 * <p><b>本类要挡住的具体事故</b>：</p>
 * <ul>
 *   <li><b>重跑映射把人工纠正冲掉</b>：用户花 20 分钟改了 40 条分类，一次 remap 全被写回
 *       RULE 规则判定，页面上只看到"映射率更新了"——静默数据丢失，本类用
 *       {@code never().markFailureCategory(...)} 把它钉住；</li>
 *   <li><b>remap 只补不清</b>：用例重新导出换了编号后，旧 {@code case_id} 会永远指向一条
 *       实际没跑过的用例，而 {@code mapped_count} 还会变小，看起来像"修好了"；</li>
 *   <li><b>给非 FAILED 的行写分类</b>：饼图合计会大于失败条数；</li>
 *   <li><b>接受停用的分类</b>：停用等于"以后别再用它"，写进去会让停用失效，
 *       而且这条数据在下拉里选不出来。</li>
 * </ul>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class ExecutionTriageServiceTest {

    /** 批次主键。 */
    private static final Long RUN_ID = 8_200L;

    /** 任务主键。 */
    private static final Long TASK_ID = 7_100L;

    /** 明细主键。 */
    private static final Long RESULT_ID = 9_001L;

    @Mock
    private ExecutionRunMapper executionRunMapper;

    @Mock
    private ExecutionResultMapper executionResultMapper;

    @Mock
    private FailureCategoryMapper failureCategoryMapper;

    @Mock
    private JunitRefLookup junitRefLookup;

    private ExecutionTriageService service;

    /**
     * 安装 MyBatis-Plus 的 lambda 列缓存：本类会构造 {@code LambdaQueryWrapper} /
     * {@code LambdaUpdateWrapper}，纯单测没有启动期扫描，缺缓存会抛
     * {@code can not find lambda cache for this entity}。
     */
    @BeforeAll
    static void installLambdaCache() {
        MybatisPlusLambdaCache.install();
    }

    @BeforeEach
    void setUp() {
        service = new ExecutionTriageService(executionRunMapper, executionResultMapper,
                failureCategoryMapper, junitRefLookup);
    }

    @Test
    @DisplayName("明细不存在抛 40401（不能把归因写到一条不存在的行上）")
    void rejectsMissingResult() {
        when(executionResultMapper.selectById(RESULT_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.updateFailureCategory(RESULT_ID, "DATA"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_NOT_FOUND));
        verify(executionResultMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("非 FAILED 的行拒绝纠正（给通过/跳过的行写分类会让饼图合计大于失败条数）")
    void rejectsNonFailedRow() {
        when(executionResultMapper.selectById(RESULT_ID))
                .thenReturn(row(RESULT_ID, "PASSED", null));

        assertThatThrownBy(() -> service.updateFailureCategory(RESULT_ID, "DATA"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有失败的结果可以人工归因")
                .hasMessageContaining("PASSED");
        verify(executionResultMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("分类为空抛 40001 并列出可选值（空值不是『取消归因』，撤销应改成 UNKNOWN）")
    void rejectsBlankCategory() {
        when(executionResultMapper.selectById(RESULT_ID))
                .thenReturn(row(RESULT_ID, "FAILED", null));
        givenEnabledCategories();

        assertThatThrownBy(() -> service.updateFailureCategory(RESULT_ID, "  "))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_PARAM_INVALID))
                .hasMessageContaining("TIMEOUT/ASSERTION/ENV");
    }

    @Test
    @DisplayName("分类不存在或已停用抛 40001（停用后仍可写入等于让停用失效），且报错带可选值")
    void rejectsUnknownOrDisabledCategory() {
        when(executionResultMapper.selectById(RESULT_ID))
                .thenReturn(row(RESULT_ID, "FAILED", null));
        givenEnabledCategories();

        // ENABLED 列表里没有 LEGACY：等价于"字典里没有或已停用"
        assertThatThrownBy(() -> service.updateFailureCategory(RESULT_ID, "LEGACY"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("失败分类取值不合法或已停用")
                .hasMessageContaining("TIMEOUT/ASSERTION/ENV");
        verify(executionResultMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("纠正成功返回更新后的整行：classification + label + triageSource=MANUAL 都是新值")
    void updatesCategoryAndReturnsWholeRow() {
        when(executionResultMapper.selectById(RESULT_ID))
                .thenReturn(row(RESULT_ID, "FAILED", "断言失败：期望 200 实际 400"));
        givenEnabledCategories();

        ExecutionResultVO updated = service.updateFailureCategory(RESULT_ID, " DATA ");

        assertThat(updated.failureCategory()).isEqualTo("DATA");
        // label 必须回新分类的中文：回 null 前端 toast 会显示成「改为『未归因』」
        assertThat(updated.failureLabel()).isEqualTo("测试数据问题");
        assertThat(updated.triageSource()).isEqualTo("MANUAL");
        assertThat(updated.id()).isEqualTo(RESULT_ID);
        assertThat(updated.status()).isEqualTo("FAILED");
        // 证据列（消息/堆栈）不在这个端点的可写范围内
        assertThat(updated.failureMessage()).isEqualTo("断言失败：期望 200 实际 400");
        verify(executionResultMapper).update(any(), any());
    }

    @Test
    @DisplayName("批次不存在时重跑映射抛 40401")
    void remapRejectsMissingRun() {
        when(executionRunMapper.selectById(RUN_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.remap(RUN_ID))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_NOT_FOUND));
    }

    @Test
    @DisplayName("批次未关联任务时抛 40001（不能静默返回原值让用户以为已重算）")
    void remapRejectsRunWithoutTask() {
        ExecutionRun run = new ExecutionRun();
        run.setId(RUN_ID);
        run.setTaskId(null);
        when(executionRunMapper.selectById(RUN_ID)).thenReturn(run);

        assertThatThrownBy(() -> service.remap(RUN_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("该批次未关联任务");
        verify(junitRefLookup, never()).load(anyLong());
        verify(executionResultMapper, never()).markCaseMapped(any(), anyList());
    }

    @Test
    @DisplayName("重算：新命中的回填、失效的旧映射清空、只有 case_id 被改（人工归因一行都不动）")
    void remapRecomputesAndKeepsManualTriage() {
        ExecutionRun run = new ExecutionRun();
        run.setId(RUN_ID);
        run.setTaskId(TASK_ID);
        run.setTotalCount(3);
        when(executionRunMapper.selectById(RUN_ID)).thenReturn(run);
        when(junitRefLookup.load(TASK_ID)).thenReturn(Map.of("GeneratedCase_001", 101L));
        when(executionResultMapper.selectList(any())).thenReturn(List.of(
                // 新命中：原本没映射上
                resultRef(1L, "com.aittest.generated.GeneratedCase_001", null),
                // 编号已不存在：旧的 case_id 必须被清掉（只补不清会留下错映射）
                resultRef(2L, "com.aittest.generated.GeneratedCase_009", 555L),
                // 已映射且仍命中：不必再 UPDATE
                resultRef(3L, "com.aittest.generated.GeneratedCase_001", 101L)));

        ExecutionRemapResult result = service.remap(RUN_ID);

        assertThat(result.mappedCount()).isEqualTo(2);
        assertThat(result.totalCount()).isEqualTo(3);
        assertThat(result.mappedRate()).isEqualByComparingTo(new BigDecimal("0.6667"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExecutionResultMapper.CaseMapping>> pairs =
                ArgumentCaptor.forClass(List.class);
        verify(executionResultMapper).markCaseMapped(any(), pairs.capture());
        assertThat(pairs.getValue()).hasSize(1);
        assertThat(pairs.getValue().get(0).getResultId()).isEqualTo(1L);
        assertThat(pairs.getValue().get(0).getCaseId()).isEqualTo(101L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> cleared = ArgumentCaptor.forClass(List.class);
        verify(executionResultMapper).clearCaseMapped(any(), cleared.capture());
        assertThat(cleared.getValue()).containsExactly(2L);

        verify(executionRunMapper).update(any(), any());
        // 重跑映射不是重新归因：绝不能碰 failure_category / triage_source
        verify(executionResultMapper, never()).markFailureCategory(any(), anyString(), anyList());
    }

    /**
     * 让字典查询返回五个启用的分类（顺序与 sort_no 一致）。
     */
    private void givenEnabledCategories() {
        when(failureCategoryMapper.selectList(any())).thenReturn(List.of(
                category("TIMEOUT", "超时", 10),
                category("ASSERTION", "断言失败", 20),
                category("ENV", "环境问题", 30),
                category("DATA", "测试数据问题", 50),
                category("UNKNOWN", "未归类", 999)));
    }

    /**
     * 构造字典行。
     *
     * @param code   码
     * @param label  文案
     * @param sortNo 排序号
     * @return 字典行
     */
    private static FailureCategory category(String code, String label, int sortNo) {
        FailureCategory entity = new FailureCategory();
        entity.setCode(code);
        entity.setLabel(label);
        entity.setSortNo(sortNo);
        entity.setEnabled(Boolean.TRUE);
        return entity;
    }

    /**
     * 构造一条失败明细。
     *
     * @param id      主键
     * @param status  状态
     * @param message 失败消息
     * @return 明细
     */
    private static ExecutionResult row(Long id, String status, String message) {
        ExecutionResult entity = new ExecutionResult();
        entity.setId(id);
        entity.setRunId(RUN_ID);
        entity.setStatus(status);
        entity.setFailureMessage(message);
        entity.setClassName("com.aittest.generated.GeneratedCase_001");
        entity.setCaseName("case_001");
        entity.setTriageSource("RULE");
        return entity;
    }

    /**
     * 构造一条只带 id / 类名 / 现有映射的明细（模拟 remap 的部分查询）。
     *
     * @param id        主键
     * @param className 类名
     * @param caseId    已有映射（可 null）
     * @return 明细
     */
    private static ExecutionResult resultRef(Long id, String className, Long caseId) {
        ExecutionResult entity = new ExecutionResult();
        entity.setId(id);
        entity.setRunId(RUN_ID);
        entity.setClassName(className);
        entity.setCaseId(caseId);
        return entity;
    }
}
