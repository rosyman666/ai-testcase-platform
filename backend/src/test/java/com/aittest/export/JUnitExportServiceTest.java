package com.aittest.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.aittest.config.ExportProperties;
import com.aittest.export.dto.CaseRow;
import com.aittest.export.dto.CaseSnapshot;
import com.aittest.export.service.JUnitExportService;
import com.aittest.export.support.JUnitCompileSupport;
import com.aittest.export.support.JUnitNaming;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T09 方案 A 真编译验收（docs/07 §1.5 / §8 T02 完成判据 ⑨⑩）。
 *
 * <p><b>硬要求</b>：解压 zip 后**真正调用** {@code ToolProvider.getSystemJavaCompiler()}
 * 编译全部 {@code .java}，断言 ERROR 数 = 0；只断言「文件已生成 / 文件名合法」是无对象假绿。
 * 本类同时验证四个「探针有效性」条件，防止编译断言本身退化成 {@code 0=0}：</p>
 * <ol>
 *   <li>编译单元数 {@code >= 用例数 + 3}（用例类 + 注解桩 + runner）；</li>
 *   <li>{@code .class} 产出数 {@code >=} 编译单元数；</li>
 *   <li>中文 {@code @DisplayName} 里的 {@code "} 被正确转义；</li>
 *   <li><b>负例</b>：故意去掉转义的 {@code "}，同一编译链必须报 ERROR。</li>
 * </ol>
 *
 * @author 寇豆码
 */
class JUnitExportServiceTest {

    private static final String PACKAGE_NAME = "com.aittest.generated";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("zip 内全部 .java 真编译：ERROR=0、编译单元数足、有 .class 产出")
    void generatedSourcesCompileWithZeroErrors() throws IOException {
        List<String> titles = List.of(
                "登录 \"失败\" 分支应返回 401",
                "含反斜杠 \\ 与制表符\t的中文标题",
                "普通中文用例标题");
        CaseSnapshot snapshot = snapshot(titles);
        byte[] zip = service().write(snapshot, PACKAGE_NAME);

        Path root = tempDir.resolve("generated");
        unzip(zip, root);

        // 1) zip 内容齐备：3 个注解桩 + N 个用例类 + 1 个 runner + README
        String packagePath = PACKAGE_NAME.replace('.', '/') + "/";
        assertThat(Files.exists(root.resolve("org/junit/jupiter/api/Test.java"))).isTrue();
        assertThat(Files.exists(root.resolve("org/junit/jupiter/api/DisplayName.java"))).isTrue();
        assertThat(Files.exists(root.resolve("org/junit/jupiter/api/Timeout.java"))).isTrue();
        assertThat(Files.exists(root.resolve("README.txt"))).isTrue();
        assertThat(Files.readString(root.resolve("README.txt"), StandardCharsets.UTF_8))
                .contains("javac")
                .contains("纯 JDK");
        for (int seq = 1; seq <= titles.size(); seq++) {
            assertThat(Files.exists(root.resolve(packagePath + JUnitNaming.simpleClassName(seq) + ".java")))
                    .isTrue();
        }
        assertThat(Files.exists(root.resolve(packagePath + JUnitExportService.RUNNER_SIMPLE_NAME + ".java")))
                .isTrue();

        // 2) 编译单元数防 0=0 假绿
        List<Path> units = JUnitCompileSupport.listJavaUnits(root);
        assertThat(units.size()).isGreaterThanOrEqualTo(titles.size() + 3);
        assertThat(units.stream().map(path -> path.getFileName().toString()).distinct().count())
                .isEqualTo(units.size());

        // 3) 真编译
        List<Diagnostic<? extends JavaFileObject>> diagnostics = JUnitCompileSupport.compileAll(root);
        assertThat(JUnitCompileSupport.countErrors(diagnostics))
                .as("编译诊断中的 ERROR 数：%s", diagnostics)
                .isZero();

        // 4) 真的产出了 class，而不是空跑
        assertThat(JUnitCompileSupport.countClassFiles(root)).isGreaterThanOrEqualTo(units.size());

        // 5) classpath 方案 A：零外部 jar
        assertThat(JUnitCompileSupport.resolveClasspath()).isEmpty();
    }

    @Test
    @DisplayName("中文 @DisplayName 转义正确，无重复类名/方法名")
    void chineseDisplayNameIsEscaped() throws IOException {
        List<String> titles = List.of("登录 \"失败\" 分支", "含反斜杠 \\ 的标题");
        byte[] zip = service().write(snapshot(titles), PACKAGE_NAME);
        Path root = tempDir.resolve("escaped");
        unzip(zip, root);

        String first = Files.readString(
                root.resolve(PACKAGE_NAME.replace('.', '/') + "/GeneratedCase_001.java"),
                StandardCharsets.UTF_8);
        assertThat(first).contains("@DisplayName(");
        // JavaPoet 的 $S 把 " 转成 \" —— 若这里出现裸引号，编译必然失败
        assertThat(first).contains("\\\"失败\\\"");
        assertThat(first).contains("@Timeout(30)");
        assertThat(first).contains("case_001");
        assertThat(first).doesNotContain("class 登录");

        String second = Files.readString(
                root.resolve(PACKAGE_NAME.replace('.', '/') + "/GeneratedCase_002.java"),
                StandardCharsets.UTF_8);
        assertThat(second).contains("\\\\");

        // runner 是手写源码，中文标题同样必须转义
        String runner = Files.readString(
                root.resolve(PACKAGE_NAME.replace('.', '/') + "/GeneratedCaseRunner.java"),
                StandardCharsets.UTF_8);
        assertThat(runner).contains("\\\"失败\\\"");

        List<Path> units = JUnitCompileSupport.listJavaUnits(root);
        assertThat(JUnitCompileSupport.countErrors(JUnitCompileSupport.compileAll(root))).isZero();
        assertThat(units).hasSize(titles.size() + 4);
    }

    @Test
    @DisplayName("负例：把 @DisplayName 里的 \\\" 改成裸引号，同一编译链必须报 ERROR")
    void negativeCaseUnescapedQuoteMustFailCompilation() throws IOException {
        List<String> titles = List.of("登录 \"失败\" 分支");
        byte[] zip = service().write(snapshot(titles), PACKAGE_NAME);
        Path root = tempDir.resolve("negative");
        unzip(zip, root);

        // 先证明原产物是绿的（探针基线）
        assertThat(JUnitCompileSupport.countErrors(JUnitCompileSupport.compileAll(root))).isZero();

        Path caseFile = root.resolve(PACKAGE_NAME.replace('.', '/') + "/GeneratedCase_001.java");
        String original = Files.readString(caseFile, StandardCharsets.UTF_8);
        assertThat(original).contains("\\\"");
        String corrupted = original.replace("\\\"", "\"");
        assertThat(corrupted).isNotEqualTo(original);
        Files.writeString(caseFile, corrupted, StandardCharsets.UTF_8);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = JUnitCompileSupport.compileAll(root);
        assertThat(JUnitCompileSupport.countErrors(diagnostics))
                .as("去掉转义后编译器必须报错，否则编译断言没有判别力")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("packageName 非法 → 40001；空快照 → 40003")
    void rejectsInvalidPackageAndEmptySnapshot() {
        assertThat(catchCode(() -> service().write(snapshot(List.of("x")), "123.bad"))).isEqualTo(40001);
        assertThat(catchCode(() -> service().write(snapshot(List.of()), PACKAGE_NAME))).isEqualTo(40003);
        assertThat(JUnitNaming.isValidJavaPackageName("com.aittest.generated")).isTrue();
        assertThat(JUnitNaming.isValidJavaPackageName("com.class.generated")).isFalse();
    }

    /** 造一份导出快照（时间戳固定，避免测试随当前时间漂移）。 */
    private static CaseSnapshot snapshot(List<String> titles) {
        List<CaseRow> rows = new java.util.ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("DRAFT", 0);
        counts.put("ACCEPTED", 0);
        counts.put("EDITED", 0);
        counts.put("REJECTED", 0);
        for (int index = 0; index < titles.size(); index++) {
            rows.add(CaseRow.builder()
                    .seqNo(index + 1)
                    .title(titles.get(index))
                    .precondition("无")
                    .steps("[{\"no\":1,\"action\":\"打开登录页\",\"data\":\"-\"}]")
                    .expected("返回 401")
                    .priority("P1")
                    .caseType("FUNCTIONAL")
                    .status("ACCEPTED")
                    .build());
            counts.merge("ACCEPTED", 1, Integer::sum);
        }
        return CaseSnapshot.builder()
                .taskNo("T20261112000001")
                .rows(rows)
                .statusCounts(counts)
                .exportedAt(LocalDateTime.of(2026, 11, 12, 10, 30, 0))
                .criteria("statuses=ACCEPTED,EDITED; taskId=1")
                .build();
    }

    private static JUnitExportService service() {
        ExportProperties properties = new ExportProperties();
        properties.setJunitTimeoutSeconds(30);
        return new JUnitExportService(properties);
    }

    /** 解压到目标目录（防 zip slip）。 */
    private static void unzip(byte[] zipBytes, Path target) throws IOException {
        Files.createDirectories(target);
        try (ZipInputStream input = new ZipInputStream(
                new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path path = target.resolve(entry.getName()).normalize();
                if (!path.startsWith(target)) {
                    throw new IOException("非法的 zip 条目路径：" + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(path);
                } else {
                    Files.createDirectories(path.getParent());
                    Files.write(path, input.readAllBytes());
                }
                input.closeEntry();
            }
        }
    }

    /** 取 BizException 的业务码，便于断言 40001 / 40003。 */
    private static int catchCode(Runnable action) {
        try {
            action.run();
            return -1;
        } catch (com.aittest.common.BizException ex) {
            return ex.getErrorCode().getCode();
        }
    }
}
