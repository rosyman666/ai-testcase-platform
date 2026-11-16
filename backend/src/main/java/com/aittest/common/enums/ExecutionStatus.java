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

    /**
     * 跳过。
     *
     * <p>文案是「已跳过」而不是「跳过」：前端 {@code constants/status.ts} 的
     * {@code EXECUTION_STATUS} 与 item 明细里的 {@code SKIPPED} 都写「已跳过」，
     * 而执行结果详情页的状态分布直接渲染后端给的 {@code label}。两处不一致时，
     * 同一个 SKIPPED 会在饼图与列表徽标里显示成两个不同的词。</p>
     */
    SKIPPED("SKIPPED", "已跳过", 30);

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
