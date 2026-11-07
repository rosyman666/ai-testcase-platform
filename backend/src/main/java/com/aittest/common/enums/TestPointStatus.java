package com.aittest.common.enums;

/**
 * 测试点状态（{@code test_point.status}）。
 *
 * @author 寇豆码
 */
public enum TestPointStatus implements CodedEnum {

    /** 可用（默认）。 */
    ACTIVE("ACTIVE", "启用", 10),

    /** 人工停用（不再参与生成，但保留以便追溯）。 */
    DISABLED("DISABLED", "停用", 20),

    /** 被新一轮抽取取代（同 doc 内内容相同的旧点）。 */
    SUPERSEDED("SUPERSEDED", "已取代", 30);

    private final String code;

    private final String label;

    private final int sortNo;

    TestPointStatus(String code, String label, int sortNo) {
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
