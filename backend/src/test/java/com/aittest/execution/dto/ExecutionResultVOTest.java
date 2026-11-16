package com.aittest.execution.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.aittest.execution.domain.ExecutionResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 明细出参的归因语义：<b>归因只存在于失败结果</b>。
 *
 * <p><b>本类要挡住的具体事故</b>：</p>
 * <ul>
 *   <li><b>通过/跳过的行声称"来源=规则"</b>：{@code execution_result.triage_source} 是
 *       {@code NOT NULL DEFAULT 'RULE'}，插入时没赋值就被数据库补成 {@code 'RULE'}。
 *       原样透出会让详情弹窗渲染出「归因：未归因（规则）」——同一行里两句话互相打脸，
 *       用户会以为归因系统抽风；按 {@code triage_source = 'RULE'} 统计"归因覆盖率"时
 *       还会把通过的行算进去，得到一个偏高且看起来合理的数；</li>
 *   <li><b>顺手把 FAILED 行也一起清空</b>：那是更糟的方向——「归因来源」整列变成「—」，
 *       比偶尔显示错更难被发现。所以本类必须<b>同时</b>断言两侧：非 FAILED 为 null，
 *       FAILED 原样保留。</li>
 * </ul>
 *
 * @author 寇豆码
 */
class ExecutionResultVOTest {

    @Test
    @DisplayName("只有 FAILED 行透出 triageSource，PASSED/SKIPPED 归一为 null（DB 默认值不是语义）")
    void normalizesTriageSourceForNonFailedRows() {
        // 三条的实体值都写 'RULE'：这正是库里通过/跳过行被 NOT NULL DEFAULT 补上的值
        ExecutionResult passed = row(1L, "PASSED", "RULE");
        ExecutionResult failed = row(2L, "FAILED", "RULE");
        ExecutionResult skipped = row(3L, "SKIPPED", "RULE");

        List<ExecutionResultVO> vos = List.of(
                ExecutionResultVO.from(passed, null),
                ExecutionResultVO.from(failed, "断言失败"),
                ExecutionResultVO.from(skipped, null));

        assertThat(vos.get(0).triageSource()).as("通过行没有归因，来源不能说成规则").isNull();
        assertThat(vos.get(2).triageSource()).as("跳过行同理").isNull();
        // 反向断言：FAILED 行不能被一起清空，否则「归因来源」整列消失
        assertThat(vos.get(1).triageSource()).isEqualTo("RULE");
        assertThat(vos.get(1).failureCategory()).isEqualTo("ASSERTION");
        assertThat(vos.get(1).failureLabel()).isEqualTo("断言失败");
    }

    @Test
    @DisplayName("人工纠正过的 FAILED 行必须透出 MANUAL（不能被归一逻辑顺手改成 null）")
    void keepsManualSourceOnFailedRow() {
        ExecutionResult corrected = row(9L, "FAILED", "MANUAL");
        corrected.setFailureCategory("DATA");

        ExecutionResultVO vo = ExecutionResultVO.from(corrected, "测试数据问题");

        assertThat(vo.status()).isEqualTo("FAILED");
        assertThat(vo.triageSource()).isEqualTo("MANUAL");
        assertThat(vo.failureCategory()).isEqualTo("DATA");
        assertThat(vo.failureLabel()).isEqualTo("测试数据问题");
    }

    /**
     * 构造一条明细（只带本类关心的字段）。
     *
     * @param id           主键
     * @param status       状态
     * @param triageSource 归因来源（模拟库里的值）
     * @return 明细
     */
    private static ExecutionResult row(Long id, String status, String triageSource) {
        ExecutionResult entity = new ExecutionResult();
        entity.setId(id);
        entity.setRunId(8_200L);
        entity.setStatus(status);
        entity.setTriageSource(triageSource);
        entity.setFailureCategory("FAILED".equals(status) ? "ASSERTION" : null);
        entity.setClassName("com.aittest.generated.GeneratedCase_00" + id);
        entity.setCaseName("case_00" + id);
        entity.setDurationMs(12L);
        return entity;
    }
}
