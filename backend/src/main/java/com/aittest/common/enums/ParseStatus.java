package com.aittest.common.enums;

/**
 * 文档解析状态（{@code doc_source.parse_status}）。
 *
 * @author 寇豆码
 */
public enum ParseStatus implements CodedEnum {

    /** 已上传，等待解析。 */
    PENDING("PENDING", "待解析", 10),

    /** 解析成功。 */
    PARSED("PARSED", "已解析", 20),

    /** 解析失败（{@code parse_error} 给可读原因）。 */
    FAILED("FAILED", "解析失败", 30);

    private final String code;

    private final String label;

    private final int sortNo;

    ParseStatus(String code, String label, int sortNo) {
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
}
