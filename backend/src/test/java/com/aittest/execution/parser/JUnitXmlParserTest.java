package com.aittest.execution.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.ExecutionSourceType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JUnit XML 解析器的契约测试。
 *
 * <p><b>本类要挡住的具体事故</b>（每一条都不会抛异常，只会让页面上的数字悄悄变对/变错）：</p>
 * <ul>
 *   <li><b>只读第一个 suite</b>：JUnit 真实产物是 {@code <testsuites>} 下嵌多个
 *       {@code <testsuite>}，只看第一个会让后几个 suite 的结果静默消失
 *       （总数从 8 变 4，通过率反而上升）；</li>
 *   <li><b>{@code <error>} 不算失败</b>：异常/超时的用例会被算成"通过"，通过率虚高，
 *       且没有任何痕迹；</li>
 *   <li><b>time 单位搞错</b>：JUnit 的 {@code time} 是<b>秒</b>（double），当成毫秒存会让
 *       单条耗时缩小 1000 倍，指标看板全废；</li>
 *   <li><b>failure 的 message 与堆栈串位</b>：message 是断言文案（页面主展示），
 *       元素文本才是堆栈。串位后页面上"失败原因"变成一坨 {@code at ...}；</li>
 *   <li><b>属性缺失时 NPE</b>：{@code classname} 缺失在手工造的结果文件里很常见。</li>
 * </ul>
 *
 * @author 寇豆码
 */
class JUnitXmlParserTest {

    /** 待测解析器（无依赖，直接 new）。 */
    private final JUnitXmlParser parser = new JUnitXmlParser();

    /**
     * 两个 suite 共 8 条：4 通过、3 失败（1 断言 + 1 error + 1 连接失败）、1 跳过。
     */
    private static final String TWO_SUITES_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuites name="demo" tests="8" failures="3" skipped="1">
                <testsuite name="suite-a" tests="4">
                    <testcase classname="com.aittest.generated.GeneratedCase_001" name="case_001" time="0.123"/>
                    <testcase classname="com.aittest.generated.GeneratedCase_002" name="case_002" time="0.456"/>
                    <testcase classname="com.aittest.generated.GeneratedCase_003" name="case_003" time="0.789">
                        <failure message="断言失败：期望 200 实际 400">java.lang.AssertionError: 期望 200 实际 400
                at GeneratedCase_003.case_003(GeneratedCase_003.java:24)</failure>
                    </testcase>
                    <testcase classname="com.aittest.generated.GeneratedCase_004" name="case_004" time="3.0">
                        <error message="执行超时">java.util.concurrent.TimeoutException: 超过 30 秒</error>
                    </testcase>
                </testsuite>
                <testsuite name="suite-b" tests="4">
                    <testcase classname="com.aittest.generated.GeneratedCase_005" name="case_005" time="0.010"/>
                    <testcase classname="com.aittest.generated.GeneratedCase_006" name="case_006" time="0.020"/>
                    <testcase classname="com.aittest.generated.GeneratedCase_007" name="case_007" time="0.030">
                        <failure message="连接失败">java.net.ConnectException: Connection refused</failure>
                    </testcase>
                    <testcase classname="com.aittest.generated.GeneratedCase_008" name="case_008" time="0.000">
                        <skipped message="环境未就绪"/>
                    </testcase>
                </testsuite>
            </testsuites>
            """;

    @Test
    @DisplayName("来源格式声明为 JUNIT_XML：导入侧靠它选解析器，写错会走到 CSV 分支")
    void declaresSourceType() {
        assertThat(parser.sourceType()).isEqualTo(ExecutionSourceType.JUNIT_XML);
    }

    @Test
    @DisplayName("两个 suite 的 8 条全被统计，且 4 通过 / 3 失败 / 1 跳过（只看第一个 suite 会漏掉后 4 条）")
    void countsAllSuites() throws IOException {
        List<ParsedResult> results = parse(TWO_SUITES_XML);

        assertThat(results).hasSize(8);
        assertThat(results).filteredOn(r -> "PASSED".equals(r.status())).hasSize(4);
        assertThat(results).filteredOn(r -> "FAILED".equals(r.status())).hasSize(3);
        assertThat(results).filteredOn(r -> "SKIPPED".equals(r.status())).hasSize(1);
        // 顺序必须与文件一致：映射靠 classname，但排查时人按文件顺序核对
        assertThat(results.get(0).className()).isEqualTo("com.aittest.generated.GeneratedCase_001");
        assertThat(results.get(7).caseName()).isEqualTo("case_008");
    }

    @Test
    @DisplayName("failure 的 message 进 failureMessage、元素文本进 stackTrace（串位会让页面『失败原因』变成堆栈）")
    void splitsFailureMessageAndStackTrace() throws IOException {
        ParsedResult failed = parse(TWO_SUITES_XML).stream()
                .filter(r -> "GeneratedCase_003".equals(lastSegment(r.className())))
                .findFirst()
                .orElseThrow();

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureMessage()).isEqualTo("断言失败：期望 200 实际 400");
        assertThat(failed.stackTrace())
                .startsWith("java.lang.AssertionError")
                .contains("GeneratedCase_003.java:24");
        // message 里绝不该出现堆栈行，反之亦然
        assertThat(failed.failureMessage()).doesNotContain("at GeneratedCase_003");
    }

    @Test
    @DisplayName("<error> 也算 FAILED（当成通过会让异常用例凭空变成绿灯，通过率虚高）")
    void treatsErrorAsFailed() throws IOException {
        ParsedResult error = parse(TWO_SUITES_XML).stream()
                .filter(r -> "GeneratedCase_004".equals(lastSegment(r.className())))
                .findFirst()
                .orElseThrow();

        assertThat(error.status()).isEqualTo("FAILED");
        assertThat(error.failureMessage()).isEqualTo("执行超时");
        assertThat(error.stackTrace()).contains("TimeoutException");
    }

    @Test
    @DisplayName("time 是秒（double），0.123 秒要变成 123 毫秒而不是 0.123 或 123000")
    void convertsSecondsToMillis() throws IOException {
        List<ParsedResult> results = parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuites>
                    <testsuite name="s">
                        <testcase classname="com.aittest.generated.GeneratedCase_001" name="case_001" time="0.123"/>
                        <testcase classname="com.aittest.generated.GeneratedCase_002" name="case_002" time="3.0"/>
                    </testsuite>
                </testsuites>
                """);

        assertThat(results.get(0).durationMs()).isEqualTo(123L);
        assertThat(results.get(1).durationMs()).isEqualTo(3000L);
    }

    @Test
    @DisplayName("属性缺失或 time 非法一律降级为空串/0，绝不 NPE（手工造的结果文件常常缺 classname）")
    void toleratesMissingAttributes() throws IOException {
        List<ParsedResult> results = parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="single-suite-root">
                    <testcase name="case_001" time="not-a-number"/>
                </testsuite>
                """);

        assertThat(results).hasSize(1);
        ParsedResult only = results.get(0);
        assertThat(only.className()).isEmpty();
        assertThat(only.caseName()).isEqualTo("case_001");
        assertThat(only.status()).isEqualTo("PASSED");
        assertThat(only.durationMs()).isZero();
        assertThat(only.failureMessage()).isEmpty();
        assertThat(only.stackTrace()).isEmpty();
    }

    @Test
    @DisplayName("UTF-8 BOM 不影响解析（Windows 导出的文件常带 BOM，否则会报 Content is not allowed in prolog）")
    void skipsUtf8Bom() throws IOException {
        byte[] body = TWO_SUITES_XML.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        List<ParsedResult> results;
        try (InputStream in = new ByteArrayInputStream(withBom)) {
            results = parser.parse(in);
        }
        assertThat(results).hasSize(8);
    }

    @Test
    @DisplayName("XML 格式错误抛 44001（而不是裸异常落到 50000『系统内部错误』）")
    void rejectsMalformedXml() {
        assertThatThrownBy(() -> parse("<testsuites><testcase classname=\"x\"></testsuites>"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_RESULT_PARSE_FAILED))
                .hasMessageContaining("JUnit XML 解析失败");
    }

    @Test
    @DisplayName("failure 的 type 属性进 failureType，并前置到堆栈首行（归因靠它，正文关键词不可靠）")
    void readsFailureTypeAttribute() throws IOException {
        List<ParsedResult> results = parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuites>
                    <testsuite name="s">
                        <testcase classname="com.aittest.generated.GeneratedCase_001" name="case_001" time="0.1">
                            <failure type="java.lang.AssertionError" message="断言失败">at GeneratedCase_001.case_001(GeneratedCase_001.java:9)</failure>
                        </testcase>
                        <testcase classname="com.aittest.generated.GeneratedCase_002" name="case_002" time="0.2">
                            <error type="java.net.ConnectException" message="连接失败"/>
                        </testcase>
                    </testsuite>
                </testsuites>
                """);

        assertThat(results.get(0).failureType()).isEqualTo("java.lang.AssertionError");
        // 正文以 at 开头（JUnit5 writer 的常见形态），类名必须由 type 补到首行
        assertThat(results.get(0).stackTrace())
                .startsWith("java.lang.AssertionError\n")
                .contains("GeneratedCase_001.java:9");
        // 自闭合 <error .../>：只有 type 没有正文，堆栈至少要有异常类，否则"失败原因"是空展开
        assertThat(results.get(1).failureType()).isEqualTo("java.net.ConnectException");
        assertThat(results.get(1).stackTrace()).isEqualTo("java.net.ConnectException");
    }

    @Test
    @DisplayName("type 属性缺失时从正文首行推断异常类（Surefire 常带类名首行但没 type）")
    void infersFailureTypeFromFirstLine() throws IOException {
        List<ParsedResult> results = parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuites>
                    <testsuite name="s">
                        <testcase classname="com.aittest.generated.GeneratedCase_001" name="case_001" time="0.1">
                            <failure message="超时">java.util.concurrent.TimeoutException: 超过 30 秒
                    at GeneratedCase_001.case_001(GeneratedCase_001.java:9)</failure>
                        </testcase>
                    </testsuite>
                </testsuites>
                """);

        assertThat(results.get(0).failureType()).isEqualTo("java.util.concurrent.TimeoutException");
        // 已经以类名开头，不重复前置（重复导入会让堆栈越贴越长）
        assertThat(results.get(0).stackTrace()).startsWith("java.util.concurrent.TimeoutException: 超过 30 秒");
        assertThat(results.get(0).stackTrace()).doesNotContain("TimeoutException\njava.util.concurrent");
    }

    @Test
    @DisplayName("type 与类名首行都没有时 failureType 为 null 且不报错（这是正常情况，不是缺陷）")
    void toleratesMissingFailureType() throws IOException {
        List<ParsedResult> results = parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuites>
                    <testsuite name="s">
                        <testcase classname="com.aittest.generated.GeneratedCase_001" name="case_001" time="0.1">
                            <failure message="说不清哪里错了">something bad happened</failure>
                        </testcase>
                    </testsuite>
                </testsuites>
                """);

        ParsedResult only = results.get(0);
        assertThat(only.failureType()).isNull();
        assertThat(only.status()).isEqualTo("FAILED");
        assertThat(only.stackTrace()).isEqualTo("something bad happened");
        assertThat(only.failureMessage()).isEqualTo("说不清哪里错了");
    }

    /**
     * 解析内联 XML。
     *
     * @param xml XML 文本
     * @return 解析结果
     * @throws IOException 读流失败
     */
    private List<ParsedResult> parse(String xml) throws IOException {
        try (InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            return parser.parse(in);
        }
    }

    /**
     * 取类名最后一段（断言里只看简单类名，避免整行被长串占满）。
     *
     * @param className 全限定类名
     * @return 简单类名
     */
    private static String lastSegment(String className) {
        int index = className == null ? -1 : className.lastIndexOf('.');
        return index < 0 ? className : className.substring(index + 1);
    }
}
