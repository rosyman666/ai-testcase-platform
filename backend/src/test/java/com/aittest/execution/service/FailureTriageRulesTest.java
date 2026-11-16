package com.aittest.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aittest.common.enums.FailureCategoryCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 归因规则的取值表测试（纯函数，不需要 Mockito、不连库）。
 *
 * <p><b>本类要挡住的具体事故</b>（每一条都不会抛异常，只会安静地把人带向错误的排查方向）：</p>
 * <ul>
 *   <li><b>按文案关键词优先</b>：一条 {@code AssertionError} 的 message 里写着
 *       "connection timed out"（被测的就是超时相关的接口），混合匹配会判成 ENV/TIMEOUT，
 *       于是有人去查网络与超时配置，而真实原因是断言写错——<b>方向错了比不归因更贵</b>，
 *       因为它看起来很有结论；</li>
 *   <li><b>按 "Timeout" 子串判断</b>：{@code java.util.concurrent.TimeoutException}（用例自身超时，
 *       调阈值）与 {@code java.net.SocketTimeoutException}（网络读超时，查环境）会被永久混成一类；</li>
 *   <li><b>空输入 NPE / 返回 null</b>：{@code UNKNOWN} 是显式结论，返回 null 会让上层
 *       {@code category.code()} 直接炸在写库路径上。</li>
 * </ul>
 *
 * @author 寇豆码
 */
class FailureTriageRulesTest {

    @Test
    @DisplayName("断言的 AssertionError 判 ASSERTION（阶段一第 1 组）")
    void classifiesAssertion() {
        assertThat(FailureTriageRules.decide("java.lang.AssertionError",
                "期望状态码 200，实际 400", "java.lang.AssertionError: 期望 200 实际 400"))
                .isEqualTo(FailureCategoryCode.ASSERTION);
    }

    @Test
    @DisplayName("用例自身超时的 TimeoutException 判 TIMEOUT")
    void classifiesTimeout() {
        assertThat(FailureTriageRules.decide("java.util.concurrent.TimeoutException",
                "用例执行超过 30 秒", "java.util.concurrent.TimeoutException: 超过 30 秒"))
                .isEqualTo(FailureCategoryCode.TIMEOUT);
    }

    @Test
    @DisplayName("连接被拒的 ConnectException 判 ENV（这类失败重跑一次往往就过了，不是功能缺陷）")
    void classifiesEnv() {
        assertThat(FailureTriageRules.decide("java.net.ConnectException",
                "Connection refused", "java.net.ConnectException: Connection refused: connect"))
                .isEqualTo(FailureCategoryCode.ENV);
    }

    @Test
    @DisplayName("类缺失的 ClassNotFoundException 判 DEPENDENCY（不是环境不通，是包没打进去）")
    void classifiesDependency() {
        assertThat(FailureTriageRules.decide("java.lang.ClassNotFoundException",
                "com.foo.Bar", "java.lang.ClassNotFoundException: com.foo.Bar"))
                .isEqualTo(FailureCategoryCode.DEPENDENCY);
    }

    @Test
    @DisplayName("唯一键冲突的 DuplicateKeyException 判 DATA（造数问题，不是被测系统缺陷）")
    void classifiesData() {
        assertThat(FailureTriageRules.decide(
                "org.springframework.dao.DuplicateKeyException",
                "Duplicate entry 'x' for key 'uk_user_name'",
                "org.springframework.dao.DuplicateKeyException: Duplicate entry 'x'"))
                .isEqualTo(FailureCategoryCode.DATA);
    }

    @Test
    @DisplayName("三条信号都没有时返回 UNKNOWN（显式结论，绝不返回 null 让上层 NPE）")
    void fallsBackToUnknown() {
        assertThat(FailureTriageRules.decide(null, "", "")).isEqualTo(FailureCategoryCode.UNKNOWN);
        assertThat(FailureTriageRules.decide(null, null, null)).isEqualTo(FailureCategoryCode.UNKNOWN);
        assertThat(FailureTriageRules.decide(null, "something went wrong", "nothing useful here"))
                .isEqualTo(FailureCategoryCode.UNKNOWN);
    }

    @Test
    @DisplayName("反例①：AssertionError 的 message 里写了 timed out，仍判 ASSERTION（否则会被带去查环境与超时配置）")
    void assertionBeatsTimeoutKeyword() {
        assertThat(FailureTriageRules.decide("java.lang.AssertionError",
                "调用下单接口 timed out，期望返回 200", "java.lang.AssertionError: 调用下单接口 timed out"))
                .isEqualTo(FailureCategoryCode.ASSERTION);
    }

    @Test
    @DisplayName("反例②：SocketTimeoutException 判 ENV 而不是 TIMEOUT（名字像超时，语义是网络不可达）")
    void socketTimeoutIsEnvNotTimeout() {
        assertThat(FailureTriageRules.decide("java.net.SocketTimeoutException",
                "Read timed out", "java.net.SocketTimeoutException: Read timed out"))
                .isEqualTo(FailureCategoryCode.ENV);
    }

    @Test
    @DisplayName("异常类与文案指向不同分类时以异常类为准（ENV 异常 + 『断言失败』文案仍判 ENV）")
    void exceptionTypeWinsOverMessage() {
        assertThat(FailureTriageRules.decide("java.net.ConnectException",
                "断言失败：期望 200 实际 500", "java.net.ConnectException: Connection refused"))
                .isEqualTo(FailureCategoryCode.ENV);
    }

    @Test
    @DisplayName("failureType 缺失时从 stackTrace 首行取异常类（JUnit5 自带 writer 常不写 type 属性）")
    void infersTypeFromStackTraceFirstLine() {
        assertThat(FailureTriageRules.decide(null, "用例超时",
                "java.util.concurrent.TimeoutException: 超过 30 秒\n\tat x.y.z(Z.java:1)"))
                .isEqualTo(FailureCategoryCode.TIMEOUT);
    }

    @Test
    @DisplayName("阶段二：只有中文文案『超时』也能判 TIMEOUT（没有异常类的纯手工 CSV）")
    void keywordOnlyMessage() {
        assertThat(FailureTriageRules.decide(null, "接口调用超时", ""))
                .isEqualTo(FailureCategoryCode.TIMEOUT);
        assertThat(FailureTriageRules.decide(null, "数据重复：订单号已存在", ""))
                .isEqualTo(FailureCategoryCode.DATA);
    }

    @Test
    @DisplayName("关键词匹配不区分大小写（日志里 TIMEOUT / Timeout / timeout 都出现过）")
    void keywordIsCaseInsensitive() {
        assertThat(FailureTriageRules.decide(null, "CONNECTION REFUSED by peer", ""))
                .isEqualTo(FailureCategoryCode.ENV);
    }

    @Test
    @DisplayName("首行是 at 堆栈、正文无类名时不误判（宁可 UNKNOWN，也不要凭 at 行猜一个分类）")
    void doesNotGuessFromStackFrames() {
        assertThat(FailureTriageRules.decide(null, "oops",
                "\tat java.base/java.lang.Thread.run(Thread.java:840)"))
                .isEqualTo(FailureCategoryCode.UNKNOWN);
    }
}
