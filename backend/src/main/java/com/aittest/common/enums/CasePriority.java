package com.aittest.common.enums;

/**
 * 用例优先级（{@code test_case.priority}）：P0 最高，P3 最低。
 *
 * @author 寇豆码
 */
public enum CasePriority implements CodedEnum {

    /** 冒烟/核心主流程，必须通过。 */
    P0("P0", "P0 最高", 10),

    /** 主要功能。 */
    P1("P1", "P1 高", 20),

    /** 一般功能。 */
    P2("P2", "P2 中", 30),

    /** 边缘场景。 */
    P3("P3", "P3 低", 40);

    private final String code;

    private final String label;

    private final int sortNo;

    CasePriority(String code, String label, int sortNo) {
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
