package com.aittest.export.service;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.config.ExportProperties;
import com.aittest.export.dto.CaseRow;
import com.aittest.export.dto.CaseSnapshot;
import com.aittest.export.support.JUnitNaming;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.lang.model.element.Modifier;
import org.springframework.stereotype.Component;

/**
 * JUnit 骨架 zip 生成（docs/07 §1.5 方案 A / §4.2）。
 *
 * <p><b>方案 A 的注解桩变体</b>：JUnit 5 只在 test scope，fat-jar 运行时不含
 * {@code junit-jupiter-api}。因此 zip 内自供 {@code org/junit/jupiter/api/} 下的
 * {@code Test} / {@code DisplayName} / {@code Timeout} 三个纯 JDK 注解定义，
 * 生成物仍写 {@code import org.junit.jupiter.api.Test;} 与中文 {@code @DisplayName}，
 * <b>编译期零外部 jar</b>，同时保住 §6 T09 ③「中文 @DisplayName 正确转义」的验收对象。</p>
 *
 * <p><b>为什么有第三个注解桩 {@code Timeout}</b>：§1.5 要求生成物带
 * {@code @Timeout(export.junitTimeoutSeconds)}；若只供两个桩，该注解无法解析，
 * 验收期的真编译必然报 ERROR。桩与用例类一起作为 compilation units 编译。</p>
 *
 * <p><b>用例类由 JavaPoet 生成</b>：{@code $S} 对 {@code "} / {@code \} / 换行 正确转义，
 * 中文标题不进标识符（{@code case_NNN} / {@code GeneratedCase_NNN}），从根上排除
 * 「非法字符」与「重复类名/方法名」。</p>
 *
 * @author 寇豆码
 */
@Component
public class JUnitExportService {

    /** 注解桩的包名与 zip 内路径。 */
    private static final String STUB_PACKAGE = "org.junit.jupiter.api";
    private static final String STUB_DIR = "org/junit/jupiter/api/";

    /** 极简 runner 的简单类名。 */
    public static final String RUNNER_SIMPLE_NAME = "GeneratedCaseRunner";

    private final ExportProperties exportProperties;

    public JUnitExportService(ExportProperties exportProperties) {
        this.exportProperties = exportProperties;
    }

    /**
     * 生成 zip 字节流。
     *
     * @param snapshot 导出快照（rows 非空）
     * @param pkg 生成用例的 Java 包名（调用方已校验/兜底）
     * @return zip 字节
     * @throws BizException 空快照 40003 / 非法包名 40001 / 生成失败 50000
     */
    public byte[] write(CaseSnapshot snapshot, String pkg) {
        if (snapshot == null || snapshot.getRows() == null || snapshot.getRows().isEmpty()) {
            throw new BizException(ErrorCode.E_EMPTY_RESULT, "没有符合条件的用例");
        }
        if (!JUnitNaming.isValidJavaPackageName(pkg)) {
            throw new BizException(ErrorCode.E_PARAM_INVALID,
                    "packageName 不是合法的 Java 包名：" + pkg);
        }
        int timeoutSeconds = Math.max(1, exportProperties.getJunitTimeoutSeconds());
        List<CaseRow> rows = snapshot.getRows();
        String packagePath = pkg.replace('.', '/') + "/";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            putEntry(zip, STUB_DIR + "Test.java", testStubSource());
            putEntry(zip, STUB_DIR + "DisplayName.java", displayNameStubSource());
            putEntry(zip, STUB_DIR + "Timeout.java", timeoutStubSource());

            for (int index = 0; index < rows.size(); index++) {
                int seq = index + 1;
                putEntry(zip, packagePath + JUnitNaming.simpleClassName(seq) + ".java",
                        caseSource(pkg, seq, rows.get(index), timeoutSeconds));
            }
            putEntry(zip, packagePath + RUNNER_SIMPLE_NAME + ".java", runnerSource(pkg, rows));
            putEntry(zip, "README.txt", readmeSource(pkg, timeoutSeconds, rows.size()));
        } catch (IOException ex) {
            throw new BizException(ErrorCode.E_INTERNAL, "生成 JUnit zip 失败");
        }
        return out.toByteArray();
    }

    /**
     * 用 JavaPoet 生成单个用例类源码。
     *
     * @param pkg 包名
     * @param seq 从 1 开始的序号
     * @param row 用例行
     * @param timeoutSeconds @Timeout 秒数
     * @return .java 源码
     */
    private static String caseSource(String pkg, int seq, CaseRow row, int timeoutSeconds) {
        String methodName = JUnitNaming.methodName(seq);
        String displayName = JUnitNaming.escapeDisplay(row.getTitle());

        MethodSpec method = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addAnnotation(ClassName.get(STUB_PACKAGE, "Test"))
                .addAnnotation(AnnotationSpec.builder(ClassName.get(STUB_PACKAGE, "DisplayName"))
                        .addMember("value", "$S", displayName)
                        .build())
                .addAnnotation(AnnotationSpec.builder(ClassName.get(STUB_PACKAGE, "Timeout"))
                        .addMember("value", "$L", timeoutSeconds)
                        .build())
                .addStatement("$T.out.println($S)", System.class, methodName)
                .build();

        TypeSpec type = TypeSpec.classBuilder(JUnitNaming.simpleClassName(seq))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addJavadoc("由 T09 导出生成，请勿手工修改。\n")
                .addMethod(method)
                .build();

        return JavaFile.builder(pkg, type)
                .indent("    ")
                .build()
                .toString();
    }

    /**
     * 生成极简 runner：纯 JDK {@code main()}，反射调用 {@code case_NNN} 并打印中文显示名。
     *
     * <p>注解桩是 {@code @Retention(SOURCE)}，运行时读不到注解，因此显示名以
     * {@code DISPLAY_NAMES} 常量数组随 runner 一起生成（同样用 {@link #javaString(String)} 转义）。</p>
     *
     * @param pkg 包名
     * @param rows 用例行
     * @return runner 源码
     */
    private static String runnerSource(String pkg, List<CaseRow> rows) {
        StringBuilder source = new StringBuilder();
        source.append("package ").append(pkg).append(";\n\n");
        source.append("import java.lang.reflect.Method;\n\n");
        source.append("/** 纯 JDK runner：反射执行全部用例方法，不依赖 JUnit 引擎。 */\n");
        source.append("public final class ").append(RUNNER_SIMPLE_NAME).append(" {\n\n");

        source.append("    private static final Class<?>[] CASE_CLASSES = {\n");
        for (int index = 0; index < rows.size(); index++) {
            source.append("        ").append(JUnitNaming.simpleClassName(index + 1)).append(".class,\n");
        }
        source.append("    };\n\n");

        source.append("    private static final String[] METHOD_NAMES = {\n");
        for (int index = 0; index < rows.size(); index++) {
            source.append("        ").append(javaString(JUnitNaming.methodName(index + 1))).append(",\n");
        }
        source.append("    };\n\n");

        source.append("    private static final String[] DISPLAY_NAMES = {\n");
        for (CaseRow row : rows) {
            source.append("        ").append(javaString(JUnitNaming.escapeDisplay(row.getTitle())))
                    .append(",\n");
        }
        source.append("    };\n\n");

        source.append("    public static void main(String[] args) throws Exception {\n");
        source.append("        int passed = 0;\n");
        source.append("        for (int i = 0; i < CASE_CLASSES.length; i++) {\n");
        source.append("            Object instance = CASE_CLASSES[i].getDeclaredConstructor().newInstance();\n");
        source.append("            Method method = CASE_CLASSES[i].getDeclaredMethod(METHOD_NAMES[i]);\n");
        source.append("            method.setAccessible(true);\n");
        source.append("            method.invoke(instance);\n");
        source.append("            passed++;\n");
        source.append("            System.out.println(\"[PASS] \" + METHOD_NAMES[i] + \" - \" + DISPLAY_NAMES[i]);\n");
        source.append("        }\n");
        source.append("        System.out.println(\"共执行 \" + passed + \" 条用例（纯 JDK runner，未启动 JUnit 引擎）\");\n");
        source.append("    }\n\n");
        source.append("    private ").append(RUNNER_SIMPLE_NAME).append("() {\n");
        source.append("    }\n");
        source.append("}\n");
        return source.toString();
    }

    /** {@code @Test} 注解桩源码。 */
    private static String testStubSource() {
        return annotationStubHeader()
                + "public @interface Test {\n"
                + "}\n";
    }

    /** {@code @DisplayName} 注解桩源码。 */
    private static String displayNameStubSource() {
        return annotationStubHeader()
                + "public @interface DisplayName {\n"
                + "    String value();\n"
                + "}\n";
    }

    /** {@code @Timeout} 注解桩源码。 */
    private static String timeoutStubSource() {
        return annotationStubHeader()
                + "public @interface Timeout {\n"
                + "    long value();\n"
                + "}\n";
    }

    /** 三个注解桩共用的 package / import / 元注解头。 */
    private static String annotationStubHeader() {
        return "package " + STUB_PACKAGE + ";\n\n"
                + "import java.lang.annotation.ElementType;\n"
                + "import java.lang.annotation.Retention;\n"
                + "import java.lang.annotation.RetentionPolicy;\n"
                + "import java.lang.annotation.Target;\n\n"
                + "/** 编译期注解桩（方案 A）：不是 JUnit 引擎，仅用于让生成的用例类可编译。 */\n"
                + "@Retention(RetentionPolicy.SOURCE)\n"
                + "@Target({ElementType.METHOD, ElementType.TYPE})\n";
    }

    /** README：编译/运行命令与「切换到真实 JUnit 引擎」的说明。 */
    private static String readmeSource(String pkg, int timeoutSeconds, int caseCount) {
        StringBuilder text = new StringBuilder();
        text.append("T09 用例导出 · JUnit 骨架（方案 A：纯 JDK + 注解桩 + 极简 runner）\n");
        text.append("================================================================\n\n");
        text.append("用例数：").append(caseCount).append("\n");
        text.append("包名：").append(pkg).append("\n");
        text.append("默认超时：").append(timeoutSeconds).append(" 秒（@Timeout）\n\n");
        text.append("【编译】只需 JDK 17，无需任何外部 jar：\n");
        text.append("  javac -encoding UTF-8 -d out $(find . -name '*.java')\n");
        text.append("  （Windows 可用 for /r %f in (*.java) do @echo %f 自行枚举）\n\n");
        text.append("【运行】纯 JDK runner，不启动 JUnit 引擎：\n");
        text.append("  java -cp out ").append(pkg).append('.').append(RUNNER_SIMPLE_NAME).append("\n\n");
        text.append("【关于注解桩】\n");
        text.append("  org/junit/jupiter/api/ 下的 Test / DisplayName / Timeout 是随 zip 自供的\n");
        text.append("  纯 JDK 注解定义（@Retention(SOURCE)），只用于编译期解析，不是 JUnit 引擎。\n");
        text.append("  若要用真实 JUnit 5 引擎运行：删除 org/junit/jupiter/api/*.java，\n");
        text.append("  改用 junit-jupiter-api + junit-platform-console-standalone 的 jar，\n");
        text.append("  并按需把 @Timeout 的语义对齐 org.junit.jupiter.api.Timeout。\n\n");
        text.append("【验收】编译通过是硬要求；运行真实 JUnit 引擎是可选。\n");
        return text.toString();
    }

    /**
     * 把任意文本转成合法的 Java 字符串字面量（含双引号）。
     *
     * <p>JavaPoet 负责用例类的 {@code @DisplayName}；runner 是手写源码，
     * 中文标题里的 {@code "} / {@code \} / 换行必须在这里显式转义，否则编译期报 ERROR。</p>
     *
     * @param value 原始文本（可为 null）
     * @return 形如 {@code "..."} 的 Java 字面量
     */
    private static String javaString(String value) {
        String text = value == null ? "" : value;
        StringBuilder literal = new StringBuilder(text.length() + 2);
        literal.append('"');
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            switch (ch) {
                case '"' -> literal.append("\\\"");
                case '\\' -> literal.append("\\\\");
                case '\n' -> literal.append("\\n");
                case '\r' -> literal.append("\\r");
                case '\t' -> literal.append("\\t");
                default -> literal.append(ch);
            }
        }
        return literal.append('"').toString();
    }

    /**
     * 向 zip 写入一个 UTF-8 文本条目。
     *
     * @param zip 目标流
     * @param name 条目名
     * @param content 文本内容
     * @throws IOException 写入失败
     */
    private static void putEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
