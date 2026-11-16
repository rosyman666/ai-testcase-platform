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
 * CSV 解析器的契约测试。
 *
 * <p><b>本类要挡住的具体事故</b>：</p>
 * <ul>
 *   <li><b>按行 {@code split(",")}</b>：失败消息里本来就带逗号（"期望 200，实际 400"），
 *       按逗号切会让后面的列整体右移，status 列读到的是消息碎片，于是"导入成功但通过率不对"；</li>
 *   <li><b>引号内的换行被当行尾</b>：一条堆栈被切成两条记录，第一条 status 变乱码；</li>
 *   <li><b>UTF-8 BOM 不剥</b>：第一列列名变成 {@code "\uFEFFclass_name"}，匹配失败后
 *       整表 class_name 全空 → 映射率 0，而文件看起来完全正常；</li>
 *   <li><b>非法 status 静默兜底</b>：把 {@code ERROR} 猜成 {@code PASSED} 会让通过率凭空变好，
 *       且没有任何痕迹——所以必须报错，并且消息里要有行号。</li>
 * </ul>
 *
 * @author 寇豆码
 */
class CsvResultParserTest {

    /** 待测解析器（无依赖，直接 new）。 */
    private final CsvResultParser parser = new CsvResultParser();

    /** 表头 + 5 行（与 docs/samples/execution-result.csv 同形态）。 */
    private static final String SAMPLE_CSV = """
            class_name,case_name,status,duration_ms,failure_message,stack_trace
            com.aittest.generated.GeneratedCase_001,case_001,PASSED,120,,
            com.aittest.generated.GeneratedCase_002,case_002,PASSED,95,,
            com.aittest.generated.GeneratedCase_003,case_003,FAILED,340,"断言失败：期望 200，实际 400",java.lang.AssertionError at GeneratedCase_003.java:24
            com.aittest.generated.GeneratedCase_004,case_004,FAILED,5000,"连接失败，服务未启动",java.net.ConnectException: Connection refused
            com.aittest.generated.GeneratedCase_005,case_005,SKIPPED,0,,
            """;

    @Test
    @DisplayName("来源格式声明为 CSV：导入侧靠它选解析器，写错会走到 XML 分支")
    void declaresSourceType() {
        assertThat(parser.sourceType()).isEqualTo(ExecutionSourceType.CSV);
    }

    @Test
    @DisplayName("表头 + 5 行解析成 5 条，计数 2 通过 / 2 失败 / 1 跳过")
    void parsesSampleFile() throws IOException {
        List<ParsedResult> results = parse(SAMPLE_CSV);

        assertThat(results).hasSize(5);
        assertThat(results).filteredOn(r -> "PASSED".equals(r.status())).hasSize(2);
        assertThat(results).filteredOn(r -> "FAILED".equals(r.status())).hasSize(2);
        assertThat(results).filteredOn(r -> "SKIPPED".equals(r.status())).hasSize(1);
        assertThat(results.get(0).className()).isEqualTo("com.aittest.generated.GeneratedCase_001");
        assertThat(results.get(0).caseName()).isEqualTo("case_001");
        assertThat(results.get(0).durationMs()).isEqualTo(120L);
        // 尾部空列不报错，按空串处理
        assertThat(results.get(0).failureMessage()).isEmpty();
        assertThat(results.get(0).stackTrace()).isEmpty();
    }

    @Test
    @DisplayName("双引号内的逗号不被当作分隔符（否则列整体右移，status 读到消息碎片）")
    void keepsCommaInsideQuotes() throws IOException {
        List<ParsedResult> results = parse(SAMPLE_CSV);
        ParsedResult failed = results.get(2);

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureMessage()).isEqualTo("断言失败：期望 200，实际 400");
        assertThat(failed.stackTrace()).isEqualTo("java.lang.AssertionError at GeneratedCase_003.java:24");
        assertThat(failed.durationMs()).isEqualTo(340L);
    }

    @Test
    @DisplayName("双引号内的换行不断记录（切成两条会让第一条 status 变成乱码）")
    void keepsNewlineInsideQuotes() throws IOException {
        List<ParsedResult> results = parse("""
                class_name,case_name,status,duration_ms,failure_message,stack_trace
                com.aittest.generated.GeneratedCase_001,case_001,FAILED,10,"第一行消息
                第二行消息","java.lang.AssertionError: 失败
                \tat GeneratedCase_001.case_001(GeneratedCase_001.java:9)"
                com.aittest.generated.GeneratedCase_002,case_002,PASSED,20,,
                """);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).failureMessage()).contains("第一行消息").contains("第二行消息");
        assertThat(results.get(0).stackTrace()).contains("GeneratedCase_001.java:9");
        assertThat(results.get(1).status()).isEqualTo("PASSED");
    }

    @Test
    @DisplayName("UTF-8 BOM 不污染第一列列名（剥不掉时 class_name 全空 → 映射率静默变 0）")
    void ignoresUtf8Bom() throws IOException {
        String csv = "class_name,case_name,status,duration_ms\n"
                + "com.aittest.generated.GeneratedCase_001,case_001,PASSED,120\n";
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        List<ParsedResult> results;
        try (InputStream in = new ByteArrayInputStream(withBom)) {
            results = parser.parse(in);
        }
        assertThat(results).hasSize(1);
        assertThat(results.get(0).className()).isEqualTo("com.aittest.generated.GeneratedCase_001");
    }

    @Test
    @DisplayName("status 非法抛 44001 且消息带行号（猜一个『差不多』的状态会让通过率凭空变好）")
    void rejectsIllegalStatusWithLineNumber() {
        assertThatThrownBy(() -> parse("""
                class_name,case_name,status,duration_ms
                com.aittest.generated.GeneratedCase_001,case_001,PASSED,10
                com.aittest.generated.GeneratedCase_002,case_002,ERROR,20
                """))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_RESULT_PARSE_FAILED))
                .hasMessageContaining("第 3 行")
                .hasMessageContaining("ERROR")
                .hasMessageContaining("PASSED/FAILED/SKIPPED");
    }

    @Test
    @DisplayName("duration_ms 非整数也报错带行号（猜 0 会让批次总耗时静默失真）")
    void rejectsIllegalDuration() {
        assertThatThrownBy(() -> parse("""
                class_name,case_name,status,duration_ms
                com.aittest.generated.GeneratedCase_001,case_001,PASSED,1.5s
                """))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("第 2 行")
                .hasMessageContaining("duration_ms");
    }

    @Test
    @DisplayName("表头缺 status 列直接报错（静默返回 0 条会被读成『这批没有结果』）")
    void rejectsMissingStatusColumn() {
        assertThatThrownBy(() -> parse("""
                class_name,case_name,duration_ms
                com.aittest.generated.GeneratedCase_001,case_001,10
                """))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("status");
    }

    @Test
    @DisplayName("列名别名与空行：className/name/result/time_ms 都认，尾部空行跳过")
    void acceptsColumnAliasesAndSkipsBlankLines() throws IOException {
        List<ParsedResult> results = parse("""
                className, name ,result,time_ms
                com.aittest.generated.GeneratedCase_001,case_001,passed,77

                """);

        assertThat(results).hasSize(1);
        ParsedResult only = results.get(0);
        assertThat(only.className()).isEqualTo("com.aittest.generated.GeneratedCase_001");
        assertThat(only.caseName()).isEqualTo("case_001");
        // 大小写不敏感：真实文件里常见 passed / Passed
        assertThat(only.status()).isEqualTo("PASSED");
        assertThat(only.durationMs()).isEqualTo(77L);
    }

    @Test
    @DisplayName("failure_type 列的值进 failureType（归因靠它判类，缺了就只能靠文案猜）")
    void readsFailureTypeColumn() throws IOException {
        List<ParsedResult> results = parse("""
                class_name,case_name,status,duration_ms,failure_message,stack_trace,failure_type
                com.aittest.generated.GeneratedCase_001,case_001,FAILED,10,读超时,,java.net.SocketTimeoutException
                """);

        ParsedResult only = results.get(0);
        assertThat(only.failureType()).isEqualTo("java.net.SocketTimeoutException");
        // 固化进堆栈首行：只写了 type 没写堆栈时，页面至少能展开看到异常类
        assertThat(only.stackTrace()).isEqualTo("java.net.SocketTimeoutException");
    }

    @Test
    @DisplayName("没有 failure_type 列时从 stack_trace 首行推断异常类（老文件不必改表头）")
    void infersFailureTypeFromStackTrace() throws IOException {
        List<ParsedResult> results = parse("""
                class_name,case_name,status,duration_ms,failure_message,stack_trace
                com.aittest.generated.GeneratedCase_001,case_001,FAILED,10,连接失败,"java.net.ConnectException: Connection refused"
                """);

        assertThat(results.get(0).failureType()).isEqualTo("java.net.ConnectException");
        // 已以类名开头，不重复前置
        assertThat(results.get(0).stackTrace()).isEqualTo("java.net.ConnectException: Connection refused");
    }

    @Test
    @DisplayName("failure_type 列 + 有堆栈时，类名被前置进首行（这是归因唯一的 FQCN 来源，断了会全落 UNKNOWN）")
    void prependsFailureTypeBeforeExistingStackTrace() throws IOException {
        List<ParsedResult> results = parse("""
                class_name,case_name,status,duration_ms,failure_message,stack_trace,failure_type
                com.aittest.generated.GeneratedCase_001,case_001,FAILED,10,连接失败,"Connection refused at Foo.java:9",java.net.ConnectException
                """);

        ParsedResult only = results.get(0);
        assertThat(only.failureType()).isEqualTo("java.net.ConnectException");
        // 首行必须是类名：FailureTriageRules 只读这一行取 FQCN
        assertThat(only.stackTrace())
                .isEqualTo("java.net.ConnectException\nConnection refused at Foo.java:9");
    }

    /**
     * 解析内联 CSV。
     *
     * @param csv CSV 文本
     * @return 解析结果
     * @throws IOException 读流失败
     */
    private List<ParsedResult> parse(String csv) throws IOException {
        try (InputStream in = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))) {
            return parser.parse(in);
        }
    }
}
