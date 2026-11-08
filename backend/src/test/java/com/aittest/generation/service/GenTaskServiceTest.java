package com.aittest.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.IdGen;
import com.aittest.common.enums.TaskScene;
import com.aittest.common.enums.TaskStatus;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.domain.GenTaskItem;
import com.aittest.generation.dto.GenTaskCreateReq;
import com.aittest.generation.mapper.GenTaskItemMapper;
import com.aittest.generation.mapper.GenTaskMapper;
import com.aittest.generation.support.MybatisPlusLambdaCache;
import com.aittest.testpoint.domain.TestPoint;
import com.aittest.testpoint.mapper.TestPointMapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 任务创建 / 取消 / 认领 / 超时回收的契约锁（T06）。
 *
 * <p><b>本类最关键的一条</b>是"无错误用空串而不是 null"：{@code gen_task.error_code}
 * 是 {@code NOT NULL DEFAULT ''}，传 null 会被 MySQL 严格模式拒掉。</p>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class GenTaskServiceTest {

    private static final Long TASK_ID = 7001L;

    private static final Long DOC_ID = 9001L;

    private static final String TASK_NO = "T20261107-000001";

    private static Validator validator;

    @Mock
    private GenTaskMapper genTaskMapper;

    @Mock
    private GenTaskItemMapper genTaskItemMapper;

    @Mock
    private TestPointMapper testPointMapper;

    @Mock
    private IdGen idGen;

    @Mock
    private ProgressPublisher progressPublisher;

    /**
     * 死锁重试修复后，构造函数要求一个 {@link PlatformTransactionManager}：
     * {@code insertItems} 用它构造 {@link org.springframework.transaction.support.TransactionTemplate}。
     * mock 的 {@code getTransaction} 返回 null，模板照常执行 action、{@code commit(null)} 为空操作，
     * 对纯单测无副作用（插入循环仍会被真实执行，断言不受影响）。
     */
    @Mock
    private org.springframework.transaction.PlatformTransactionManager txManager;

    @InjectMocks
    private GenTaskService service;

    @BeforeAll
    static void initValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        // cancel()/countSkipped() 内部拼 LambdaUpdateWrapper，纯单测没有 MP 启动期扫描
        MybatisPlusLambdaCache.install();
    }

    private static GenTask task(String status) {
        GenTask t = new GenTask();
        t.setId(TASK_ID);
        t.setTaskNo(TASK_NO);
        t.setStatus(status);
        t.setScene(TaskScene.TESTPOINT_EXTRACT.code());
        t.setDocId(DOC_ID);
        return t;
    }

    /** 取出 LambdaUpdateWrapper 里写入的参数值（避免拼 SQL 字符串做断言）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Wrapper<GenTaskItem>> updateWrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    private static List<Object> wrapperValues(Wrapper<?> wrapper) {
        if (wrapper instanceof LambdaUpdateWrapper<?> lambda) {
            lambda.getSqlSet();
        }
        // 必须再触发一次 WHERE 段渲染：.eq(...) 的条件值是 lazy 注册的，
        // 只调 getSqlSet() 会让 paramNameValuePairs 里只剩 SET 段的值。
        wrapper.getSqlSegment();
        AbstractWrapper<?, ?, ?> base = (AbstractWrapper<?, ?, ?>) wrapper;
        return new ArrayList<>(base.getParamNameValuePairs().values());
    }

    // ------------------------------------------------------------------
    // cancel
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "终态 {0} 不允许 cancel")
    @EnumSource(value = TaskStatus.class, names = {"SUCCESS", "PARTIAL", "FAILED"})
    @DisplayName("cancel 对 SUCCESS/PARTIAL/FAILED 终态抛 40901，且不做任何写操作")
    void cancelRejectsTerminal(TaskStatus status) {
        when(genTaskMapper.selectById(TASK_ID)).thenReturn(task(status.name()));

        BizException ex = assertThrows(BizException.class, () -> service.cancel(TASK_ID));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_STATE_ILLEGAL);
        assertThat(ex.getErrorCode().getCode()).isEqualTo(40901);
        verify(genTaskMapper, never()).markTerminal(any(), any(), any(), any(), any());
        verify(genTaskItemMapper, never()).update(any(), any());
    }

    /**
     * 现状刻画（characterization test），<b>不是</b>对期望契约的背书。
     *
     * <p>任务书要求「cancel 对终态任务 SUCCESS/CANCELED/FAILED 抛 40901」，但
     * {@code TaskStateMachine.assertTransition} 先判 {@code from == to} 幂等直接 return，
     * 于是 {@code CANCELED → CANCELED} 被当成合法流转；{@code cancel()} 会继续重放一次
     * 「SKIPPED + markTerminal + finish」。本轮不改生产代码，这里锁住真实行为并在
     * 缺陷报告里列出；将来状态机一修，本用例会立刻变红提醒同步。</p>
     */
    @Test
    @DisplayName("[已知偏差] cancel 对 CANCELED 当前不抛 40901，而是幂等重放一次取消（见缺陷报告）")
    void cancelOnCanceledIsCurrentlyIdempotentReplay() {
        when(genTaskMapper.selectById(TASK_ID)).thenReturn(task(TaskStatus.CANCELED.name()));

        service.cancel(TASK_ID);

        verify(genTaskMapper).markTerminal(eq(TASK_ID), eq(TaskStatus.CANCELED.code()),
                eq(""), eq("用户取消"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("cancel 运行中任务：errorCode 空串、errorMsg 非空，且补推一次 SSE 终态（I5）")
    void cancelRunningWritesEmptyErrorCodeNotNullMessage() {
        when(genTaskMapper.selectById(TASK_ID)).thenReturn(task(TaskStatus.RUNNING.name()));

        service.cancel(TASK_ID);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(genTaskMapper).markTerminal(eq(TASK_ID), eq(TaskStatus.CANCELED.code()),
                code.capture(), msg.capture(), any(LocalDateTime.class));

        // NOT NULL 列：两者都不能是 null；"无业务错误码"用空串表达
        assertThat(code.getValue()).isNotNull().isEmpty();
        assertThat(msg.getValue()).isNotNull().isNotBlank();
        assertThat(msg.getValue()).isEqualTo("用户取消");
        // I5：不推 finish 的话订阅端会挂在 SSE 上直到 30 分钟超时
        verify(progressPublisher).finish(any(GenTask.class), eq(TaskStatus.CANCELED),
                eq(""), eq("用户取消"));
    }

    @Test
    @DisplayName("I5：cancel 只把 PENDING 置 SKIPPED，不动 RUNNING（与注释一致、缩小 B1 竞态窗口）")
    void cancelOnlySkipsPendingItems() {
        when(genTaskMapper.selectById(TASK_ID)).thenReturn(task(TaskStatus.RUNNING.name()));

        service.cancel(TASK_ID);

        ArgumentCaptor<Wrapper<GenTaskItem>> wrapper = updateWrapperCaptor();
        verify(genTaskItemMapper).update(isNull(), wrapper.capture());
        List<Object> values = wrapperValues(wrapper.getValue());
        assertThat(values).contains("PENDING", "SKIPPED");
        assertThat(values).doesNotContain("RUNNING");
    }

    // ------------------------------------------------------------------
    // 创建：入参二选一
    // ------------------------------------------------------------------

    private static Stream<Arguments> xorGroups() {
        return Stream.of(
                Arguments.of("只有 unitRefs", List.of("operation:a"), null, true),
                Arguments.of("只有 testPointIds", null, List.of(11L), true),
                Arguments.of("两者都不传", null, null, false),
                Arguments.of("两者都传", List.of("operation:a"), List.of(11L), false),
                Arguments.of("两个空列表", List.of(), List.of(), false));
    }

    @ParameterizedTest(name = "{0} → 合法={3}")
    @MethodSource("xorGroups")
    @DisplayName("GenTaskCreateReq 的 unitRefs / testPointIds 二选一校验")
    void exactlyOneGroupIsValidatedByBeanValidation(String name, List<String> unitRefs,
                                                    List<Long> pointIds, boolean expectedValid) {
        GenTaskCreateReq req = new GenTaskCreateReq(
                TaskScene.TESTPOINT_EXTRACT.code(), DOC_ID, "mock", "model-x", unitRefs, pointIds);

        Set<String> properties = validator.validate(req).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        if (expectedValid) {
            assertThat(properties).doesNotContain("exactlyOneGroup");
        } else {
            assertThat(properties).contains("exactlyOneGroup");
        }
    }

    @Test
    @DisplayName("create 两个子项集合都为空 → 40001，且不取号不写库")
    void createRejectsEmptyGroups() {
        BizException ex = assertThrows(BizException.class,
                () -> service.create(TaskScene.TESTPOINT_EXTRACT, DOC_ID, "mock", "m", null, null));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_PARAM_INVALID);
        assertThat(ex.getMessage()).contains("可执行的子项");
        verifyNoInteractions(idGen, genTaskMapper, genTaskItemMapper, testPointMapper);
    }

    // ------------------------------------------------------------------
    // 创建：item 数量与 unit_ref 透传
    // ------------------------------------------------------------------

    /** 让 generateWithRetry 真正执行传入的 action（模拟"取号 + 插入主表"走自动提交）。 */
    private void wireIdGenAndInsert() {
        when(idGen.<GenTask>generateWithRetry(eq(IdGen.NoType.TASK), any()))
                .thenAnswer(invocation -> {
                    Function<String, GenTask> action = invocation.getArgument(1);
                    return action.apply(TASK_NO);
                });
        // MP 的 ASSIGN_ID 由 insert 回填；mock 里手工补上，便于断言 item.taskId
        doAnswer(invocation -> {
            GenTask t = invocation.getArgument(0);
            t.setId(TASK_ID);
            return 1;
        }).when(genTaskMapper).insert(any(GenTask.class));
    }

    @Test
    @DisplayName("create：3 个 unitRefs → 3 条 item，unit_ref 原样透传")
    void createFromUnitRefsKeepsRefs() {
        wireIdGenAndInsert();
        List<String> refs = List.of("operation:createUser", "section:3", "operation:deleteUser");

        Long id = service.create(TaskScene.TESTPOINT_EXTRACT, DOC_ID, "mock", "m", refs, null);

        assertThat(id).isEqualTo(TASK_ID);
        ArgumentCaptor<GenTaskItem> items = ArgumentCaptor.forClass(GenTaskItem.class);
        verify(genTaskItemMapper, times(3)).insert(items.capture());
        assertThat(items.getAllValues())
                .extracting(GenTaskItem::getUnitRef)
                .containsExactlyElementsOf(refs);
        assertThat(items.getAllValues())
                .allSatisfy(item -> {
                    assertThat(item.getTaskId()).isEqualTo(TASK_ID);
                    assertThat(item.getStatus()).isEqualTo("PENDING");
                    assertThat(item.getAttempts()).isZero();
                    assertThat(item.getErrorCode()).isEmpty();
                    assertThat(item.getErrorMsg()).isEmpty();
                    assertThat(item.getNextRunAt()).isNotNull();
                });
        verifyNoInteractions(testPointMapper);
    }

    @Test
    @DisplayName("I2 负向：无 operationId 的长 unitRef（>64 字符）原样透传，不截断不拒绝")
    void longUnitRefWithoutOperationIdIsPreserved() {
        wireIdGenAndInsert();
        // ExtractPlanner.operationRef 的 fallback = "operation:" + METHOD + " " + path
        String longRef = "operation:POST /api/v1/" + "x".repeat(140);
        assertThat(longRef.length()).isGreaterThan(64).isLessThanOrEqualTo(200);

        service.create(TaskScene.TESTPOINT_EXTRACT, DOC_ID, "mock", "m", List.of(longRef), null);

        ArgumentCaptor<GenTaskItem> items = ArgumentCaptor.forClass(GenTaskItem.class);
        verify(genTaskItemMapper).insert(items.capture());
        assertThat(items.getValue().getUnitRef()).isEqualTo(longRef);
    }

    @Test
    @DisplayName("create：2 个 testPointIds → 2 条 item，testPointId 与 unit_ref 正确")
    void createFromTestPointIds() {
        wireIdGenAndInsert();
        when(testPointMapper.selectById(any())).thenReturn(new TestPoint());

        Long id = service.create(TaskScene.CASE_GENERATE, DOC_ID, "mock", "m",
                null, List.of(101L, 102L));

        assertThat(id).isEqualTo(TASK_ID);
        ArgumentCaptor<GenTaskItem> items = ArgumentCaptor.forClass(GenTaskItem.class);
        verify(genTaskItemMapper, times(2)).insert(items.capture());
        assertThat(items.getAllValues())
                .extracting(GenTaskItem::getTestPointId)
                .containsExactly(101L, 102L);
        assertThat(items.getAllValues())
                .extracting(GenTaskItem::getUnitRef)
                .containsExactly("testpoint:101", "testpoint:102");
    }

    @Test
    @DisplayName("I9：create 落库 producedItems=0 且 createdBy 允许为空（历史数据兼容）")
    void createInitializesProducedItems() {
        wireIdGenAndInsert();

        service.create(TaskScene.TESTPOINT_EXTRACT, DOC_ID, "mock", "m",
                List.of("operation:a"), null);

        ArgumentCaptor<GenTask> saved = ArgumentCaptor.forClass(GenTask.class);
        verify(genTaskMapper).insert(saved.capture());
        assertThat(saved.getValue().getProducedItems()).isZero();
        assertThat(saved.getValue().getAcceptedCases()).isZero();
    }

    @Test
    @DisplayName("create：子项插入失败时任务置 FAILED，而不是物理删主表（不丢号）")
    void createMarksTaskFailedWhenItemsFail() {
        wireIdGenAndInsert();
        when(genTaskItemMapper.insert(any(GenTaskItem.class)))
                .thenThrow(new IllegalStateException("模拟子表写失败"));

        BizException ex = assertThrows(BizException.class,
                () -> service.create(TaskScene.TESTPOINT_EXTRACT, DOC_ID, "mock", "m",
                        List.of("operation:a"), null));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_INTERNAL);
        verify(genTaskMapper).markTerminal(eq(TASK_ID), eq(TaskStatus.FAILED.code()),
                eq(ErrorCode.E_INTERNAL.name()), any(String.class), any(LocalDateTime.class));
        // deleteById 有 Serializable / T 两个重载，必须写明具体类型
        verify(genTaskMapper, never()).deleteById(any(Serializable.class));
    }

    // ------------------------------------------------------------------
    // 认领 / 超时回收
    // ------------------------------------------------------------------

    @Test
    @DisplayName("I6：claimBatch 认领到首批 item 后把任务置 RUNNING（带 PENDING 守卫）")
    void claimBatchMarksTaskRunning() {
        List<Long> ids = List.of(11L, 12L);
        when(genTaskItemMapper.claimPending(eq(2), any(LocalDateTime.class))).thenReturn(ids);
        when(genTaskItemMapper.markRunning(eq(ids), eq("worker-1"), any(LocalDateTime.class)))
                .thenReturn(2);
        // 死锁修复后：先按 item 快照读反查去重 taskIds，再显式 IN 更新，
        // 不再在 UPDATE 里内联 gen_task_item 子查询。
        when(genTaskItemMapper.selectTaskIdsByItemIds(ids)).thenReturn(List.of(TASK_ID));

        List<Long> claimed = service.claimBatch(2, "worker-1");

        assertThat(claimed).isEqualTo(ids);
        verify(genTaskItemMapper).selectTaskIdsByItemIds(ids);
        verify(genTaskMapper).markRunningByItemIds(eq(List.of(TASK_ID)), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("claimBatch 反查不到 taskId 时（空列表）不置任务 RUNNING，避免 IN () 语法错")
    void claimBatchSkipsTaskUpdateWhenNoTaskIds() {
        List<Long> ids = List.of(11L);
        when(genTaskItemMapper.claimPending(eq(1), any(LocalDateTime.class))).thenReturn(ids);
        when(genTaskItemMapper.markRunning(eq(ids), eq("worker-1"), any(LocalDateTime.class)))
                .thenReturn(1);
        when(genTaskItemMapper.selectTaskIdsByItemIds(ids)).thenReturn(List.of());

        assertThat(service.claimBatch(1, "worker-1")).isEqualTo(ids);

        verify(genTaskMapper, never()).markRunningByItemIds(any(), any());
    }

    @Test
    @DisplayName("claimBatch 没认领到 item 时不置 RUNNING、不写 started_at")
    void claimBatchEmptyDoesNotTouchTask() {
        when(genTaskItemMapper.claimPending(eq(2), any(LocalDateTime.class))).thenReturn(List.of());

        assertThat(service.claimBatch(2, "worker-1")).isEmpty();

        verify(genTaskMapper, never()).markRunningByItemIds(any(), any());
        verify(genTaskItemMapper, never()).markRunning(any(), any(), any());
    }

    @Test
    @DisplayName("I7：超时回收把 RUNNING item 置 FAILED（错误码/文案非空）并补齐失败计数")
    void recycleExpiredTasksCountsFailedItems() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 8, 4, 0);
        List<Long> expired = List.of(TASK_ID);
        when(genTaskMapper.selectExpiredTaskIds(now)).thenReturn(expired);
        when(genTaskItemMapper.failRunningItemsOfTask(eq(TASK_ID), eq(now),
                any(String.class), any(String.class))).thenReturn(2);

        assertThat(service.recycleExpiredTasks(now)).containsExactly(TASK_ID);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(genTaskItemMapper).failRunningItemsOfTask(eq(TASK_ID), eq(now),
                code.capture(), msg.capture());
        assertThat(code.getValue()).isNotBlank();
        assertThat(msg.getValue()).isNotBlank();
        verify(genTaskMapper).bumpCounters(TASK_ID, 2, 0, 2, 0);
    }

    @Test
    @DisplayName("I7 PENDING 收口：超时任务仍留有 PENDING item 时一并置 FAILED（错误码/文案非空）并合计补计数")
    void recycleExpiredTasksAlsoFailsPendingItems() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 8, 4, 0);
        List<Long> expired = List.of(TASK_ID);
        when(genTaskMapper.selectExpiredTaskIds(now)).thenReturn(expired);
        when(genTaskItemMapper.failRunningItemsOfTask(eq(TASK_ID), eq(now),
                any(String.class), any(String.class))).thenReturn(2);
        when(genTaskItemMapper.failPendingItemsOfTask(eq(TASK_ID), eq(now),
                any(String.class), any(String.class))).thenReturn(1);

        assertThat(service.recycleExpiredTasks(now)).containsExactly(TASK_ID);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(genTaskItemMapper).failPendingItemsOfTask(eq(TASK_ID), eq(now),
                code.capture(), msg.capture());
        // NOT NULL 列：两条路径的错误码/文案都必须非空
        assertThat(code.getValue()).isNotNull().isNotBlank();
        assertThat(msg.getValue()).isNotNull().isNotBlank();
        // RUNNING 2 + PENDING 1 = 3，done/failed 一次补齐；否则 maybeFinalize 仍会因
        // pending > 0 卡住，回收器每 60s 空转。
        verify(genTaskMapper).bumpCounters(TASK_ID, 3, 0, 3, 0);
    }

    @Test
    @DisplayName("I7：没有超时任务时不写库")
    void recycleExpiredTasksIsNoOpWhenNothingExpired() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 8, 4, 0);
        when(genTaskMapper.selectExpiredTaskIds(now)).thenReturn(List.of());

        assertThat(service.recycleExpiredTasks(now)).isEmpty();

        verify(genTaskItemMapper, never()).failRunningItemsOfTask(any(), any(), any(), any());
        verify(genTaskMapper, never()).bumpCounters(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("require：任务不存在抛 40401")
    void requireThrowsNotFound() {
        when(genTaskMapper.selectById(TASK_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.require(TASK_ID));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_NOT_FOUND);
    }
}
