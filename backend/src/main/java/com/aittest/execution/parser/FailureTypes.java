package com.aittest.execution.parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 异常类名（FQCN）的提取与固化：把 {@code <failure type="...">} 变成可归因的事实。
 *
 * <p><b>为什么必须有这一步</b>：归因的第一阶段是"按异常类判定"，而
 * {@code <failure type="java.lang.AssertionError">} 里的 {@code type} 属性才是可靠的
 * 异常全限定名。元素正文在不同框架下差异极大——Surefire 常带类名首行，
 * JUnit5 自带的 writer 有时直接从 {@code at ...} 开始。只看正文的话，真实报告上
 * 大量失败会落到 {@code UNKNOWN}，表面"六类都能产出"，实际不可用。</p>
 *
 * <p><b>为什么不新增一列存 type，而是把它前置进 stack_trace</b>：</p>
 * <ul>
 *   <li>不新增迁移（本批的前提约束）；</li>
 *   <li>用户在详情页打开堆栈弹窗时，第一眼最想看的就是异常类名。真实 JUnit 输出里
 *       这一行本来就在（{@code java.lang.AssertionError: ...}），只是有些 writer 会省掉，
 *       前置等于把两种产物归一成同一种形态；</li>
 *   <li>归因规则可以只读 {@code stack_trace} 首行，不必依赖"解析中间态"是否被正确传递——
 *       规则因此可以被单独测试、也可以被人工对库排查。</li>
 * </ul>
 *
 * <p><b>幂等</b>：{@link #withHeader} 在堆栈已经以该类名开头时不重复前置。
 * 这条不是洁癖——重复导入同一份文件时，若每次都在首行前面再贴一遍，
 * 堆栈会随导入次数越长越高（5000 条明细 × 重复行），且用户看不出是哪来的。</p>
 *
 * @author 寇豆码
 */
public final class FailureTypes {

    /**
     * 从正文首行提取 FQCN。
     *
     * <p>只认"以 {@code Exception/Error/Throwable} 结尾、且带包名点号"的形态，
     * 且必须出现在行首。刻意不做更复杂的推断（不加 {@code Caused by:}、不看第二行）：
     * 过度设计会在真实文件上产生误判，而误判的代价是归因方向直接跑偏。</p>
     */
    private static final Pattern FIRST_LINE_TYPE = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$.]*(?:Exception|Error|Throwable))(?::|\\s|$)");

    /** 工具类，禁止实例化。 */
    private FailureTypes() {
    }

    /**
     * 确定这条失败结果的异常类名。
     *
     * <p>优先用解析器读到的 {@code type} 属性；属性缺失时从堆栈首行推断；都没有返回 null。</p>
     *
     * @param declaredType 解析器读到的 {@code type} 属性（可空/空串）
     * @param stackTrace   堆栈原文（可空）
     * @return 异常 FQCN；无法确定时返回 {@code null}（<b>这是正常情况</b>，不是错误）
     */
    public static String resolve(String declaredType, String stackTrace) {
        String declared = trimToNull(declaredType);
        if (declared != null) {
            // 少数产物把 ", msg" 或 ": msg" 也塞进 type 属性：只取类名部分
            int cut = indexOfAny(declared, ':', ',', ' ');
            return cut > 0 ? declared.substring(0, cut) : declared;
        }
        return firstLineType(stackTrace);
    }

    /**
     * 把异常类名固化到堆栈首行（{@code stack_trace} 的写入口）。
     *
     * @param failureType 异常 FQCN（可空）
     * @param stackTrace  堆栈原文（可空）
     * @return 以类名开头的堆栈；两者都空时返回空串（<b>不为 null</b>，交由调用方决定存 NULL）
     */
    public static String withHeader(String failureType, String stackTrace) {
        String type = trimToNull(failureType);
        String text = stackTrace == null ? "" : stackTrace.trim();
        if (type == null) {
            return text;
        }
        if (text.isEmpty()) {
            // 只有 type 没有正文（<failure type="..." message="..."/> 自闭合）：至少让页面
            // 能看到异常类，否则"失败原因"是一次空展开
            return type;
        }
        return text.startsWith(type) ? text : type + "\n" + text;
    }

    /**
     * 从文本首行提取异常类名。
     *
     * @param text 文本（可空）
     * @return FQCN；首行不是"类名 + 冒号/空白"形态时返回 null
     */
    public static String firstLineType(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        int end = firstLineEnd(trimmed);
        Matcher matcher = FIRST_LINE_TYPE.matcher(trimmed.substring(0, end));
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 首行结束位置（{@code \n} 或 {@code \r}）。
     *
     * @param text 已 trim 的文本
     * @return 首行结束下标（无换行时等于长度）
     */
    private static int firstLineEnd(String text) {
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == '\n' || ch == '\r') {
                return index;
            }
        }
        return text.length();
    }

    /**
     * 找第一个分隔字符的位置。
     *
     * @param text  文本
     * @param chars 分隔字符
     * @return 下标；都不存在返回 -1
     */
    private static int indexOfAny(String text, char... chars) {
        int found = -1;
        for (char ch : chars) {
            int index = text.indexOf(ch);
            if (index >= 0 && (found < 0 || index < found)) {
                found = index;
            }
        }
        return found;
    }

    /**
     * trim 并把空白串归一成 null（属性缺失在解析器里是空串，语义上应视为"没有"）。
     *
     * @param value 原值
     * @return trim 后的非空串或 null
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
