package com.aittest.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.aittest.execution.domain.ExecutionResult;
import com.aittest.execution.mapper.ExecutionResultMapper;
import com.aittest.execution.mapper.ExecutionRunMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 明细写入 + 规则归因落库的契约测试（纯 Mockito）。
 *
 * <p><b>本类要挡住的具体事故</b>：</p>
 * <ul>
 *   <li><b>逐行 UPDATE</b>：5000 条失败结果就是 5000 次往返与行锁，导入会在锁等待里超时，
 *       而现象只是"导入慢"；</li>
 *   <li><b>把 PASSED/SKIPPED 也写分类</b>：{@code categoryDistribution} 的合计会大于失败数，
 *       饼图与"失败 N 条"卡片永久对不上，且没人能解释多出来那一块；</li>
 *   <li><b>不覆盖 runId</b>：明细会撞 FK 或挂到别的批次上（跨批次污染比报错更难查）。</li>
 * </ul>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class ExecutionWriteServiceTest {

    /** 批次主键。 */
    private static final Long RUN_ID = 8_200L;

    @Mock
    private ExecutionRunMapper executionRunMapper;

    @Mock
    private ExecutionResultMapper executionResultMapper;

    private ExecutionWriteService service;

    @BeforeEach
    void setUp() {
        service = new ExecutionWriteService(executionRunMapper, executionResultMapper);
    }

    @Test
    @DisplayName("归因按分类分组批量落库：三类各一条 UPDATE，PASSED 行不参与")
    void triagesFailedRowsByCategory() {
        List<ExecutionResult> rows = List.of(
                row(1L, "PASSED", null, null),
                row(2L, "FAILED", "断言失败：期望 200 实际 400",
                        "java.lang.AssertionError: 期望 200 实际 400"),
                row(3L, "FAILED", "Connection refused",
                        "java.net.ConnectException: Connection refused"),
                row(4L, "FAILED", "用例执行超时",
                        "java.util.concurrent.TimeoutException: 超过 30 秒"));

        service.saveResults(RUN_ID, rows, List.of());

        verify(executionResultMapper).markFailureCategory(RUN_ID, "ASSERTION", List.of(2L));
        verify(executionResultMapper).markFailureCategory(RUN_ID, "ENV", List.of(3L));
        verify(executionResultMapper).markFailureCategory(RUN_ID, "TIMEOUT", List.of(4L));
        // 三条失败 = 三条语句；PASSED 那条不产生任何分类写入
        verify(executionResultMapper, times(3)).markFailureCategory(any(), anyString(), anyList());
        // 内存对象同步成"将要落库的值"，日志与调用方读到的不是猜测
        assertThat(rows.get(1).getFailureCategory()).isEqualTo("ASSERTION");
        assertThat(rows.get(0).getFailureCategory()).isNull();
    }

    @Test
    @DisplayName("同一分类超过 500 条时按 500 切片（一条语句塞 5000 个占位符会顶到语句长度上限）")
    void chunksLargeCategoryLists() {
        List<ExecutionResult> rows = new ArrayList<>();
        for (long index = 1; index <= ExecutionWriteService.BATCH_SIZE + 1L; index++) {
            rows.add(row(index, "FAILED", "断言失败", "java.lang.AssertionError: 失败"));
        }

        service.saveResults(RUN_ID, rows, List.of());

        verify(executionResultMapper).markFailureCategory(eq(RUN_ID), eq("ASSERTION"),
                org.mockito.ArgumentMatchers.argThat(ids -> ids.size() == ExecutionWriteService.BATCH_SIZE));
        verify(executionResultMapper).markFailureCategory(eq(RUN_ID), eq("ASSERTION"),
                org.mockito.ArgumentMatchers.argThat(ids -> ids.size() == 1));
    }

    @Test
    @DisplayName("saveResults 覆写 runId：调用方传错也不会把明细挂到别的批次上")
    void overwritesRunId() {
        List<ExecutionResult> rows = new ArrayList<>(List.of(
                row(1L, "PASSED", null, null),
                row(2L, "PASSED", null, null)));

        service.saveResults(RUN_ID, rows, List.of());

        assertThat(rows).allSatisfy(item -> assertThat(item.getRunId()).isEqualTo(RUN_ID));
        verify(executionResultMapper).insertBatch(rows);
    }

    @Test
    @DisplayName("全部通过时不产生任何分类 UPDATE（空语句也要发的话会白白拿行锁）")
    void skipsTriageWhenNoFailure() {
        List<ExecutionResult> rows = List.of(row(1L, "PASSED", null, null),
                row(2L, "SKIPPED", null, null));

        service.saveResults(RUN_ID, rows, List.of());

        verify(executionResultMapper, never()).markFailureCategory(any(), anyString(), anyList());
        verify(executionResultMapper, never()).markCaseMapped(any(), anyList());
    }

    /**
     * 构造一条明细。
     *
     * @param id       主键
     * @param status   状态
     * @param message  失败消息（可 null）
     * @param stackTrace 堆栈（可 null）
     * @return 明细
     */
    private static ExecutionResult row(Long id, String status, String message, String stackTrace) {
        ExecutionResult result = new ExecutionResult();
        result.setId(id);
        result.setStatus(status);
        result.setFailureMessage(message);
        result.setStackTrace(stackTrace);
        result.setClassName("com.aittest.generated.GeneratedCase_00" + id);
        result.setCaseName("case_00" + id);
        return result;
    }
}
