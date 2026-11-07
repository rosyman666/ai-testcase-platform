package com.aittest.common.enums;

/**
 * 测试点风险等级（{@code test_point.risk_level}）。
 *
 * @author 寇豆码
 */
public enum RiskLevel implements CodedEnum {

    /** 高风险：核心链路、资金、权限。 */
    HIGH("HIGH", "高", 10),

    /** 中风险。 */
    MEDIUM("MEDIUM", "中", 20),

    /** 低风险。 */
    LOW("LOW", "低", 30);

    private final String code;

    private final String label;

    private final int sortNo;

    RiskLevel(String code, String label, int sortNo) {
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
