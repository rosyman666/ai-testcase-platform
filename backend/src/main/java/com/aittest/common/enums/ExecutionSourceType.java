package com.aittest.common.enums;

import java.util.Locale;

/**
 * 执行结果来源格式（{@code execution_run.source_type}）。
 *
 * <p>两种格式的能力边界不同，所以必须记在批次上而不是靠"猜文件内容"：</p>
 * <ul>
 *   <li>{@link #JUNIT_XML}：JUnit 原生输出，天然带 {@code classname}，能映射到用例；</li>
 *   <li>{@link #CSV}：人工/脚本整理的结果，{@code class_name} 列可以留空，
 *       此时整批都映射不上（{@code mapped_rate = 0}）——这是<b>允许</b>的，
 *       映射率就是用来暴露这件事的，不是用来拦导入的。</li>
 * </ul>
 *
 * <p><b>为什么不直接用文件扩展名当枚举值</b>：扩展名是"用户传了什么"，source_type 是
 * "我们按哪种解析器处理"，两者在大小写（{@code .XML}）、别名（{@code .junit}）上会分叉。
 * 枚举值只留解析器口径，扩展名 → 枚举的换算集中在 {@link #ofExtension(String)}，
 * 加一种格式时只有一个地方要改。</p>
 *
 * @author 寇豆码
 */
public enum ExecutionSourceType implements CodedEnum {

    /** JUnit XML（{@code <testsuites>} 根，流式解析）。 */
    JUNIT_XML("JUNIT_XML", "JUnit XML", 10),

    /** CSV（首行表头，RFC4180 引号规则）。 */
    CSV("CSV", "CSV", 20);

    private final String code;

    private final String label;

    private final int sortNo;

    ExecutionSourceType(String code, String label, int sortNo) {
        this.code = code;
        this.label = label;
        this.sortNo = sortNo;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public int sortNo() {
        return sortNo;
    }

    /**
     * 文件扩展名 → 来源格式。
     *
     * <p>大小写不敏感、去首尾空白，且容忍前导点（{@code ".xml"} 与 {@code "xml"} 等价）——
     * 浏览器/工具给出的扩展名格式并不统一，这里做宽一点，避免"同一个文件换台机器就导不进"。</p>
     *
     * @param extension 扩展名（可带前导点，可为 null）
     * @return 对应格式；<b>无法识别返回 {@code null}</b>（由调用方抛 44002 并带上真实文件名）
     */
    public static ExecutionSourceType ofExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return null;
        }
        String normalized = extension.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return switch (normalized) {
            case "xml" -> JUNIT_XML;
            case "csv" -> CSV;
            default -> null;
        };
    }

    /**
     * 编码 → 来源格式（用于查询条件的白名单校验）。
     *
     * @param code 编码（大小写不敏感）
     * @return 对应格式；无法识别返回 {@code null}
     */
    public static ExecutionSourceType ofCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (ExecutionSourceType item : values()) {
            if (item.code.equals(normalized)) {
                return item;
            }
        }
        return null;
    }
}
