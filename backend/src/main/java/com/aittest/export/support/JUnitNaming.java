package com.aittest.export.support;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JUnit 生成物的命名与转义规则（docs/07 §2.1 / §4.2）。
 *
 * <p><b>中文标题绝不进标识符</b>：类名恒为 {@code GeneratedCase_NNN}，方法名恒为
 * {@code case_NNN}，中文只进 {@code @DisplayName("…")} 的字符串字面量（由 JavaPoet 的
 * {@code $S} 负责转义）。这样从根上排除「类名/方法名含非法字符」与「重复类名」两类事故。</p>
 *
 * @author 寇豆码
 */
public final class JUnitNaming {

    /** Java 标识符段（含合法的 {@code $}）。 */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    /** Java 保留字：包名段命中即判非法，否则生成的 .java 编译不过。 */
    private static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null", "_", "record", "var", "yield", "sealed", "permits");

    /** 显示名兜底文案（标题为空时使用）。 */
    private static final String FALLBACK_TITLE = "未命名用例";

    /** 显示名最大长度，防止极端脏数据把生成文件撑爆。 */
    private static final int MAX_DISPLAY_LENGTH = 500;

    private JUnitNaming() {
        // 纯静态工具类，禁止实例化
    }

    /**
     * 方法名：{@code case_001} 起，按序号递增，零填充到 3 位。
     *
     * @param seq 从 1 开始的序号
     * @return 合法 Java 方法名
     */
    public static String methodName(int seq) {
        return String.format(Locale.ROOT, "case_%03d", seq);
    }

    /**
     * 简单类名：{@code GeneratedCase_001} 起。
     *
     * @param seq 从 1 开始的序号
     * @return 合法 Java 简单类名
     */
    public static String simpleClassName(int seq) {
        return String.format(Locale.ROOT, "GeneratedCase_%03d", seq);
    }

    /**
     * 全限定类名。
     *
     * @param pkg 包名
     * @param seq 从 1 开始的序号
     * @return 全限定类名
     */
    public static String className(String pkg, int seq) {
        return pkg + "." + simpleClassName(seq);
    }

    /**
     * 归一化显示名：把换行/制表符压成空格、去首尾空白、空值兜底、超长截断。
     *
     * <p><b>这里不做 Java 字面量转义</b>：转义交给 JavaPoet 的 {@code $S}，它对
     * {@code "} / {@code \} / {@code \n} / {@code \r} 都正确；在这里提前转义会导致双重转义。</p>
     *
     * @param title 原始用例标题（可为 null）
     * @return 归一化后的显示名
     */
    public static String escapeDisplay(String title) {
        if (title == null) {
            return FALLBACK_TITLE;
        }
        String normalized = title
                .replace("\r\n", " ")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .trim();
        if (normalized.isEmpty()) {
            return FALLBACK_TITLE;
        }
        if (normalized.length() > MAX_DISPLAY_LENGTH) {
            return normalized.substring(0, MAX_DISPLAY_LENGTH);
        }
        return normalized;
    }

    /**
     * 校验 Java 包名：以 {@code .} 分段，每段是合法标识符且不是保留字。
     *
     * @param pkg 待校验包名（可为 null）
     * @return 合法返回 true
     */
    public static boolean isValidJavaPackageName(String pkg) {
        if (pkg == null || pkg.isBlank()) {
            return false;
        }
        String candidate = pkg.trim();
        if (candidate.startsWith(".") || candidate.endsWith(".") || candidate.contains("..")) {
            return false;
        }
        for (String segment : candidate.split("\\.", -1)) {
            if (!SEGMENT.matcher(segment).matches() || RESERVED.contains(segment)) {
                return false;
            }
        }
        return true;
    }
}
