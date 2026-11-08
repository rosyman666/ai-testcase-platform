package com.aittest.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.TaskStatus;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 任务状态机的契约锁（T06）。
 *
 * <p>本类要挡住的事故：合法流转被误删一条分支、或非法流转从"抛 40901"被改成"静默吞掉"。
 * 两者都不会报错，只会让任务卡在中间态 / 被重复收尾，且日志里看不出异常。
 * 错误码必须精确断言到 {@code 40901}，不能只断言"抛了 BizException"——
 * 历史上曾有人用 {@code E_CONFLICT(40902)} 代替，前端按码值分支会走错提示。</p>
 *
 * @author 寇豆码
 */
class TaskStateMachineTest {

    /** 合法流转表：与类注释里的状态图一一对应。 */
    private static Stream<Arguments> legalTransitions() {
        return Stream.of(
                Arguments.of(TaskStatus.PENDING, TaskStatus.RUNNING),
                Arguments.of(TaskStatus.PENDING, TaskStatus.CANCELED),
                Arguments.of(TaskStatus.RUNNING, TaskStatus.SUCCESS),
                Arguments.of(TaskStatus.RUNNING, TaskStatus.PARTIAL),
                Arguments.of(TaskStatus.RUNNING, TaskStatus.FAILED),
                Arguments.of(TaskStatus.RUNNING, TaskStatus.CANCELED));
    }

    /** 非法流转表：含终态互跳、终态回跑、跨级跳转。 */
    private static Stream<Arguments> illegalTransitions() {
        return Stream.of(
                Arguments.of(TaskStatus.PENDING, TaskStatus.SUCCESS),
                Arguments.of(TaskStatus.PENDING, TaskStatus.PARTIAL),
                Arguments.of(TaskStatus.PENDING, TaskStatus.FAILED),
                Arguments.of(TaskStatus.RUNNING, TaskStatus.PENDING),
                Arguments.of(TaskStatus.SUCCESS, TaskStatus.RUNNING),
                Arguments.of(TaskStatus.SUCCESS, TaskStatus.FAILED),
                Arguments.of(TaskStatus.PARTIAL, TaskStatus.SUCCESS),
                Arguments.of(TaskStatus.PARTIAL, TaskStatus.FAILED),
                Arguments.of(TaskStatus.FAILED, TaskStatus.SUCCESS),
                Arguments.of(TaskStatus.FAILED, TaskStatus.RUNNING),
                Arguments.of(TaskStatus.CANCELED, TaskStatus.PENDING),
                Arguments.of(TaskStatus.CANCELED, TaskStatus.RUNNING));
    }

    @ParameterizedTest(name = "{0} → {1} 合法")
    @MethodSource("legalTransitions")
    @DisplayName("合法流转不抛异常")
    void legalTransitionDoesNotThrow(TaskStatus from, TaskStatus to) {
        assertThatCode(() -> TaskStateMachine.assertTransition(from, to)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} → {1} 非法 → 40901")
    @MethodSource("illegalTransitions")
    @DisplayName("非法流转抛 40901，且不是 40902")
    void illegalTransitionThrows40901(TaskStatus from, TaskStatus to) {
        BizException ex = assertThrows(BizException.class,
                () -> TaskStateMachine.assertTransition(from, to));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_STATE_ILLEGAL);
        assertThat(ex.getErrorCode().getCode()).isEqualTo(40901);
        // 40902 是唯一键冲突（E_DUPLICATE），状态机拒绝不能借用这个码
        assertThat(ex.getErrorCode()).isNotEqualTo(ErrorCode.E_DUPLICATE);
        assertThat(ex.getErrorCode().getCode()).isNotEqualTo(40902);
        assertThat(ex.getMessage()).isNotBlank();
    }

    @ParameterizedTest(name = "{0} → {0} 幂等")
    @EnumSource(TaskStatus.class)
    @DisplayName("同状态流转幂等，包括终态")
    void sameStateIsIdempotent(TaskStatus status) {
        assertThatCode(() -> TaskStateMachine.assertTransition(status, status))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("terminalOf：全部成功 → SUCCESS")
    void terminalOfAllSuccess() {
        assertThat(TaskStateMachine.terminalOf(3, 3)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(TaskStateMachine.terminalOf(1, 1)).isEqualTo(TaskStatus.SUCCESS);
        // success > total 是脏数据，但不能因此返回 PARTIAL；按"全部成功"收敛
        assertThat(TaskStateMachine.terminalOf(3, 4)).isEqualTo(TaskStatus.SUCCESS);
    }

    @Test
    @DisplayName("terminalOf：部分成功 → PARTIAL（1..n-1）")
    void terminalOfPartial() {
        assertThat(TaskStateMachine.terminalOf(3, 1)).isEqualTo(TaskStatus.PARTIAL);
        assertThat(TaskStateMachine.terminalOf(3, 2)).isEqualTo(TaskStatus.PARTIAL);
        assertThat(TaskStateMachine.terminalOf(100, 99)).isEqualTo(TaskStatus.PARTIAL);
    }

    @Test
    @DisplayName("terminalOf：零成功 → FAILED")
    void terminalOfZeroSuccess() {
        assertThat(TaskStateMachine.terminalOf(3, 0)).isEqualTo(TaskStatus.FAILED);
        assertThat(TaskStateMachine.terminalOf(1, 0)).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    @DisplayName("terminalOf：total=0 / 负数 → FAILED（边界）")
    void terminalOfEmptyTask() {
        assertThat(TaskStateMachine.terminalOf(0, 0)).isEqualTo(TaskStatus.FAILED);
        assertThat(TaskStateMachine.terminalOf(0, 5)).isEqualTo(TaskStatus.FAILED);
        assertThat(TaskStateMachine.terminalOf(-1, 0)).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    @DisplayName("terminalOf 的结果一律是终态")
    void terminalOfAlwaysTerminal() {
        for (int total = 0; total <= 4; total++) {
            for (int success = 0; success <= 4; success++) {
                assertThat(TaskStateMachine.terminalOf(total, success).isTerminal()).isTrue();
            }
        }
    }
}
