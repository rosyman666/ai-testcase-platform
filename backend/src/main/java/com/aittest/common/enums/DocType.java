package com.aittest.common.enums;

/**
 * 需求文档类型（{@code doc_source.doc_type}）。
 *
 * <p>与解析器扩展名白名单一一对应：同一份需求用不同格式上传，切分方式不同
 * （Markdown 按标题、文本按空行、OpenAPI 按 operation），但下游测试点抽取逻辑一致。</p>
 *
 * @author 寇豆码
 */
public enum DocType implements CodedEnum {

    /** Markdown：按标题层级切章节。 */
    MD("MD", "Markdown 文档", 10, "md"),

    /** 纯文本：按空行/长度切块。 */
    TXT("TXT", "纯文本", 20, "txt"),

    /** Word：按标题样式切章节。 */
    DOCX("DOCX", "Word 文档", 30, "docx"),

    /** OpenAPI/Swagger JSON：按 operation 切分。 */
    OPENAPI_JSON("OPENAPI_JSON", "OpenAPI (JSON)", 40, "json");

    private final String code;

    private final String label;

    private final int sortNo;

    /** 文件扩展名（小写，不含点）。 */
    private final String extension;

    DocType(String code, String label, int sortNo, String extension) {
        this.code = code;
        this.label = label;
        this.sortNo = sortNo;
        this.extension = extension;
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
     * 扩展名（不含点，小写）。
     *
     * @return 扩展名
     */
    public String extension() {
        return extension;
    }

    /**
     * 按扩展名反查文档类型（上传白名单校验入口）。
     *
     * @param extension 扩展名（不含点，大小写不敏感）
     * @return 命中类型；不支持时返回 null，由调用方抛 42002
     */
    public static DocType ofExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return null;
        }
        String normalized = extension.toLowerCase().trim();
        for (DocType type : values()) {
            if (type.extension.equals(normalized)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 按编码反查，未知编码归 null（不要抛异常：历史数据可能带旧编码）。
     *
     * @param code 编码
     * @return 命中类型或 null
     */
    public static DocType ofCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (DocType type : values()) {
            if (type.code.equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        return null;
    }
}
