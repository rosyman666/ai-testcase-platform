package com.aittest.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.IdGen;
import com.aittest.common.enums.TaskScene;
import com.aittest.common.enums.TaskStatus;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.domain.GenTaskItem;
import com.aittest.generation.mapper.GenTaskItemMapper;
import com.aittest.generation.mapper.GenTaskMapper;
import com.aittest.generation.support.MybatisPlusLambdaCache;
import com.aittest.testpoint.mapper.TestPointMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 建任务 ↔ dispatcher 认领的<b>死锁有界重试</b>契约锁（T06 验收暴露的真实缺陷）。
 *
 * <p>现场：{@code POST /api/testpoints/extract}（60 个 operation）返回
 * {@code 50000 任务创建失败}，日志
 * {@code Deadlock found when trying to get lock; try restarting transaction}，
 * 失败语句是 {@code INSERT INTO gen_task_item}。根因是建任务的 60 条 INSERT 与
 * {@code claimBatch} 的 {@code FOR UPDATE SKIP LOCKED} + 子查询 UPDATE 锁序交叉。</p>
 *
 * <p>本类锁三条：① 死锁后<b>重试能成功</b>，不把一次瞬态冲突暴露成 500；
 * ② 重试<b>有界</b>（{@value #MAX_ATTEMPTS} 次），耗尽后仍按既有语义置任务 FAILED、
 * 抛 {@code E_INTERNAL}；③ 重试之间有退避，不是空转硬冲。</p>
 *
 * <p><b>纯单测</b>：{@code itemTxTemplate} 用的是 mock 的
 * {@link PlatformTransactionManager}——{@code getTransaction} 返回 null，
 * {@code TransactionTemplate} 照常执行 action、{@code commit(null)} 为空操作，
 * 因此插入循环与异常传播与真实事务一致，但不连库。</p>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class TaskCreateDeadlockRetryTest {

    /** 与生产常量对齐：重试上限（含首次）。 */
    private static final int MAX_ATTEMPTS = 3;

    private static final Long TASK_ID = 7050L;

    private static final Long DOC_ID = 9001L;

    private static final String TASK_NO = "T20261108-000050";

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

    @Mock
    private PlatformTransactionManager txManager;

    @InjectMocks
    private GenTaskService service;

    @BeforeAll
    static void installLambdaCache() {
        // create() 本身不拼 lambda wrapper，但 markTerminal/insertTask 走的 mapper 与
        // 既有测试共享同一套 MP 元数据，装着更稳（幂等）。
        MybatisPlusLambdaCache.install();
    }

    /**
     * 让 {@code generateWithRetry} 真正执行取号 action，并回填主表 id
     * （MP 的 ASSIGN_ID 由真实 insert 回填，mock 里手工补）。
     */
    private void wireIdGenAndInsert() {
        when(idGen.<GenTask>generateWithRetry(eq(IdGen.NoType.TASK), any()))
                .thenAnswer(invocation -> {
                    Function<String, GenTask> action = invocation.getArgument(1);
                    return action.apply(TASK_NO);
                });
        doAnswer(invocation -> {
            GenTask t = invocation.getArgument(0);
            t.setId(TASK_ID);
            return 1;
        }).when(genTaskMapper).insert(any(GenTask.class));
    }

    private Long createOneItemTask() {
        return service.create(TaskScene.TESTPOINT_EXTRACT, DOC_ID, "mock", "m",
                List.of("operation:createUser"), null);
    }

    @Test
    @DisplayName("死锁后重试成功：第 1 次 insert 抛死锁、第 2 次成功 → create 正常返回，任务不置 FAILED")
    void retrySucceedsAfterOneDeadlock() {
        wireIdGenAndInsert();
        // 单 item 任务：每轮只 1 次 insert，便于用 insert 调用次数精确数"尝试了几轮"
        when(genTaskItemMapper.insert(any(GenTaskItem.class)))
                .thenThrow(new DeadlockLoserDataAccessException("Deadlock found", null))
                .thenReturn(1);

        long startNanos = System.nanoTime();
        Long id = createOneItemTask();
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertThat(id).isEqualTo(TASK_ID);
        // 第 1 轮失败 + 第 2 轮成功 = 恰好 2 次
        verify(genTaskItemMapper, times(2)).insert(any(GenTaskItem.class));
        // 瞬态死锁被重试吸收，不应走到"置 FAILED"
        verify(genTaskMapper, never()).markTerminal(any(), any(), any(), any(), any());
        // 重试之间有退避（宽松阈值：50ms 起步，不断言精确值避免脆弱）
        assertThat(elapsedMs).isGreaterThanOrEqualTo(45L);
    }

    @Test
    @DisplayName("重试耗尽：连续 3 次死锁 → 抛 E_INTERNAL、任务置 FAILED、insert 恰好 3 次")
    void retryExhaustedMarksTaskFailed() {
        wireIdGenAndInsert();
        // 单个 thenThrow 表示"每次调用都抛"，与连续三次抛等价
        when(genTaskItemMapper.insert(any(GenTaskItem.class)))
                .thenThrow(new PessimisticLockingFailureException("Deadlock found"));

        BizException ex = assertThrows(BizException.class, this::createOneItemTask);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_INTERNAL);
        assertThat(ex.getMessage()).contains("任务创建失败");
        // 有界：恰好 MAX_ATTEMPTS 次，不多不少
        verify(genTaskItemMapper, times(MAX_ATTEMPTS)).insert(any(GenTaskItem.class));
        // 耗尽后落到既有 catch：置 FAILED（error_code 非空、E_INTERNAL）
        verify(genTaskMapper).markTerminal(eq(TASK_ID), eq(TaskStatus.FAILED.code()),
                eq(ErrorCode.E_INTERNAL.name()), any(String.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("非死锁异常不触发重试：普通 insert 失败一次即置 FAILED（重试只针对锁冲突）")
    void nonLockFailureIsNotRetried() {
        wireIdGenAndInsert();
        when(genTaskItemMapper.insert(any(GenTaskItem.class)))
                .thenThrow(new IllegalStateException("模拟非锁类写入失败"));

        BizException ex = assertThrows(BizException.class, this::createOneItemTask);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_INTERNAL);
        // 非 PessimisticLockingFailureException → 不重试，只 1 次
        verify(genTaskItemMapper, times(1)).insert(any(GenTaskItem.class));
        verify(genTaskMapper).markTerminal(eq(TASK_ID), eq(TaskStatus.FAILED.code()),
                eq(ErrorCode.E_INTERNAL.name()), any(String.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("多 item 任务：首个 item 撞死锁、整批重试后全部插入成功（事务整体回滚后重建）")
    void multiItemBatchRetriesAsWhole() {
        wireIdGenAndInsert();
        // 3 个 unitRef → 每轮 3 次 insert。第 1 轮的第一次抛死锁，其余放行。
        when(genTaskItemMapper.insert(any(GenTaskItem.class)))
                .thenThrow(new DeadlockLoserDataAccessException("Deadlock found", null))
                .thenReturn(1);

        Long id = service.create(TaskScene.TESTPOINT_EXTRACT, DOC_ID, "mock", "m",
                List.of("operation:a", "operation:b", "operation:c"), null);

        assertThat(id).isEqualTo(TASK_ID);
        // 第 1 轮：第 1 条抛死锁（后续未执行）；第 2 轮：3 条全部插入 → 共 1 + 3 = 4 次
        verify(genTaskItemMapper, times(4)).insert(any(GenTaskItem.class));
        verify(genTaskMapper, never()).markTerminal(any(), any(), any(), any(), any());
    }
}
