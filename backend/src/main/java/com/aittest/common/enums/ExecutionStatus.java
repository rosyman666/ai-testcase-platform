package com.aittest.common.enums;

/**
 * 执行结果状态（{@code execution_result.status}）。
 *
 * @author 寇豆码
 */
public enum ExecutionStatus implements CodedEnum {

    /** 通过。 */
    PASSED("PASSED", "通过", 10),

    /** 失败。 */
    FAILED("FAILED", "失败", 20),

    /** 跳过。 */
    SKIPPED("SKIPPED", "跳过", 30);

    private final String code;

    private final String label;

    private final int sortNo;

    ExecutionStatus(String code, String label, int sortNo) {
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
