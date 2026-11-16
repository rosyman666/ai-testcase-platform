package com.aittest.execution.service;

import com.aittest.common.enums.FailureCategoryCode;
import com.aittest.execution.parser.FailureTypes;
import java.util.List;
import java.util.Locale;

/**
 * 失败归因规则（<b>纯函数，不碰库、不依赖 Spring</b>）。
 *
 * <p>输入一条失败结果的三样事实，输出一个 {@link FailureCategoryCode}。没有副作用，
 * 因此可以被单测穷举，也可以被人工拿去对库排查"这条为什么被判成 ENV"。</p>
 *
 * <h2>两阶段：异常类优先于文案</h2>
 *
 * <p><b>阶段一</b>按异常类判定：先用 {@code failureType}（{@code <failure type="...">} 或
 * {@code failure_type} 列），没有再读 {@code stackTrace} 首行（解析时已把异常类前置进去）。
 * 命中即返回。<b>阶段二</b>才看文案关键词——只有阶段一完全没命中时才允许进入。</p>
 *
 * <p><b>为什么必须分成两个阶段，而不是"把所有信号混在一起打分"</b>：一条
 * {@code AssertionError} 的 message 里完全可能写着 "connection timed out"（被测的正是
 * 超时相关的接口），混合匹配会把它判成 TIMEOUT 或 ENV，于是归因把人带去看环境和超时配置，
 * 而真实原因是断言写错或功能缺陷——方向错了比不归因更贵，因为它看起来很有结论。</p>
 *
 * <p><b>两个必须写在这里的反例</b>（它们就是两阶段存在的理由）：</p>
 * <ul>
 *   <li>{@code AssertionError} 且 message 含 "timed out" → <b>ASSERTION</b>，不是 TIMEOUT。
 *       {@code AssertionError} 属于阶段一第 1 组，命中即返回，不会进入关键词阶段；</li>
 *   <li>{@code java.net.SocketTimeoutException} → <b>ENV</b>，不是 TIMEOUT。它在阶段一第 3 组
 *       （环境不可达的一族），而不是 {@code java.util.concurrent.TimeoutException}
 *       （用例自身超时）。名字里都有 Timeout，语义完全不同：前者要去看网络与下游，
 *       后者要去看用例超时阈值。按 "Timeout" 子串匹配会把这两类永久混在一起。</li>
 * </ul>
 *
 * <p><b>匹配口径</b>：类名匹配<b>大小写敏感</b>（Java 类名就是大小写敏感的，
 * {@code assertionerror} 这种输入只可能来自脏数据），允许"全名相等"或"以
 * {@code .简单类名} 结尾"两种形态——前者匹配 {@code org.springframework.dao.DuplicateKeyException}
 * 这种显式写法，后者让 {@code AssertionError} 能覆盖 {@code java.lang.AssertionError}
 * 与 {@code junit.framework.AssertionFailedError} 之外的各种包名变体。
 * 关键词匹配则<b>不区分大小写</b>（日志里 {@code TIMEOUT / Timeout / timeout} 都出现过）。</p>
 *
 * @author 寇豆码
 */
public final class FailureTriageRules {

    /** 关键词扫描的字符上限：只看开头，避免把 1MB 堆栈全量转小写。 */
    private static final int KEYWORD_WINDOW = 2000;

    /**
     * 阶段一的规则表（<b>列表顺序即优先级，第一个命中即返回</b>）。
     *
     * <p>顺序不是随意的：断言失败必须排在超时/环境之前，因为断言类异常的 message 里
     * 经常提到下游的超时与连接问题（"期望返回 200，实际连接超时"）。</p>
     */
    private static final List<TypeRule> TYPE_RULES = List.of(
            new TypeRule(FailureCategoryCode.ASSERTION, List.of(
                    "AssertionError",
                    "AssertionFailedError",
                    "org.opentest4j.AssertionFailedError",
                    "org.junit.ComparisonFailure",
                    "org.opentest4j.MultipleFailuresError")),
            new TypeRule(FailureCategoryCode.TIMEOUT, List.of(
                    "java.util.concurrent.TimeoutException",
                    "org.apache.http.conn.ConnectTimeoutException",
                    "io.netty.handler.timeout.ReadTimeoutException")),
            new TypeRule(FailureCategoryCode.ENV, List.of(
                    "java.net.UnknownHostException",
                    "ConnectException",
                    "SocketTimeoutException",
                    "NoRouteToHostException",
                    "SocketException",
                    "javax.net.ssl.SSLException",
                    "org.springframework.web.client.ResourceAccessException")),
            new TypeRule(FailureCategoryCode.DEPENDENCY, List.of(
                    "ClassNotFoundException",
                    "NoClassDefFoundError",
                    "NoSuchMethodError",
                    "NoSuchFieldError",
                    "NoSuchBeanDefinitionException",
                    "UnsatisfiedDependencyException",
                    "BeanCreationException")),
            new TypeRule(FailureCategoryCode.DATA, List.of(
                    "SQLIntegrityConstraintViolationException",
                    "DuplicateKeyException",
                    "DataIntegrityViolationException",
                    "NumberFormatException",
                    "DateTimeParseException",
                    "JsonParseException")));

    /**
     * 阶段二的规则表（<b>顺序即优先级</b>）。
     *
     * <p>关键词偏保守：宁可落到 {@code UNKNOWN}，也不要凭一个泛词（"error"、"failed"）
     * 给出一个看起来有结论的错误分类。{@code UNKNOWN} 在页面上是可以被追问的，
     * 而错分类不会。</p>
     */
    private static final List<KeywordRule> KEYWORD_RULES = List.of(
            new KeywordRule(FailureCategoryCode.TIMEOUT, List.of("timed out", "timeout", "超时")),
            new KeywordRule(FailureCategoryCode.ENV, List.of(
                    "connection refused", "connection reset", "unknown host",
                    "no route to host", "unable to resolve", "ssl", "dns")),
            new KeywordRule(FailureCategoryCode.DEPENDENCY, List.of(
                    "class not found", "could not resolve", "no qualifying bean", "no such method")),
            new KeywordRule(FailureCategoryCode.DATA, List.of(
                    "duplicate entry", "integrity constraint", "constraint violation",
                    "not a valid", "数据不存在", "数据重复")));

    /** 工具类，禁止实例化。 */
    private FailureTriageRules() {
    }

    /**
     * 判定一条失败结果的归因分类。
     *
     * <p>调用方通常只传 {@code null} 作为 {@code failureType}：解析阶段已把异常类
     * 前置进堆栈首行，规则可以自己从 {@code stackTrace} 里取出来。显式传入的
     * {@code failureType} 优先级更高（用于单元测试与将来的 LLM 二次判定）。</p>
     *
     * @param failureType    异常全限定名（可空）
     * @param failureMessage 失败消息（可空）
     * @param stackTrace     堆栈（可空；首行通常是 {@code java.lang.XxxException: msg}）
     * @return 分类；未命中返回 {@link FailureCategoryCode#UNKNOWN}（<b>绝不为 null</b>）
     */
    public static FailureCategoryCode decide(String failureType, String failureMessage,
                                         String stackTrace) {
        String exceptionType = FailureTypes.resolve(failureType, stackTrace);
        if (exceptionType != null) {
            FailureCategoryCode byType = matchType(exceptionType);
            if (byType != null) {
                return byType;
            }
        }
        return matchKeyword(failureMessage, stackTrace);
    }

    /**
     * 阶段一：按异常类名匹配。
     *
     * @param exceptionType 异常全限定名（非空）
     * @return 分类；没有规则命中返回 null（由调用方决定是否进入阶段二）
     */
    private static FailureCategoryCode matchType(String exceptionType) {
        for (TypeRule rule : TYPE_RULES) {
            for (String pattern : rule.patterns()) {
                if (exceptionType.equals(pattern) || exceptionType.endsWith("." + pattern)) {
                    return rule.category();
                }
            }
        }
        return null;
    }

    /**
     * 阶段二：按文案关键词匹配（仅在阶段一未命中时调用）。
     *
     * @param failureMessage 失败消息（可空）
     * @param stackTrace     堆栈（可空）
     * @return 分类；都没命中返回 {@link FailureCategoryCode#UNKNOWN}
     */
    private static FailureCategoryCode matchKeyword(String failureMessage, String stackTrace) {
        String haystack = buildHaystack(failureMessage, stackTrace);
        for (KeywordRule rule : KEYWORD_RULES) {
            for (String keyword : rule.keywords()) {
                if (haystack.contains(keyword)) {
                    return rule.category();
                }
            }
        }
        return FailureCategoryCode.UNKNOWN;
    }

    /**
     * 拼出用于关键词匹配的文本（小写，前 {@link #KEYWORD_WINDOW} 字）。
     *
     * <p>只取开头：真正说明问题的那几行一定在最前面，而 {@code Caused by} 之后的
     * 深层堆栈里常出现与本次失败无关的第三方文案。</p>
     *
     * @param failureMessage 失败消息（可空）
     * @param stackTrace     堆栈（可空）
     * @return 小写文本（非 null）
     */
    private static String buildHaystack(String failureMessage, String stackTrace) {
        String merged = (failureMessage == null ? "" : failureMessage)
                + "\n"
                + (stackTrace == null ? "" : stackTrace);
        String window = merged.length() > KEYWORD_WINDOW
                ? merged.substring(0, KEYWORD_WINDOW)
                : merged;
        return window.toLowerCase(Locale.ROOT);
    }

    /**
     * 一条"异常类 → 分类"规则。
     *
     * @param category 分类
     * @param patterns 匹配的异常类名（全名或简单类名）
     */
    private record TypeRule(FailureCategoryCode category, List<String> patterns) {
    }

    /**
     * 一条"关键词 → 分类"规则。
     *
     * @param category 分类
     * @param keywords 关键词（小写；中文原样）
     */
    private record KeywordRule(FailureCategoryCode category, List<String> keywords) {
    }
}
