package com.aittest.common.enums;

/**
 * 用例/测试点类型（{@code test_case.case_type}、{@code test_point.category}）。
 *
 * <p>测试点与用例共用同一套分类：这样"测试点分类 → 用例类型"的分布对比才有意义
 * （能看出哪类测试点生成出来的用例被驳回最多）。</p>
 *
 * @author 寇豆码
 */
public enum CaseType implements CodedEnum {

    /** 功能正常流程。 */
    FUNCTIONAL("FUNCTIONAL", "功能", 10),

    /** 边界值。 */
    BOUNDARY("BOUNDARY", "边界", 20),

    /** 异常与错误处理。 */
    EXCEPTION("EXCEPTION", "异常", 30),

    /** 安全（越权、注入、敏感信息）。 */
    SECURITY("SECURITY", "安全", 40),

    /** 性能（并发、大数据量）。 */
    PERFORMANCE("PERFORMANCE", "性能", 50),

    /** 兼容性（版本、终端、编码）。 */
    COMPATIBILITY("COMPATIBILITY", "兼容性", 60);

    private final String code;

    private final String label;

    private final int sortNo;

    CaseType(String code, String label, int sortNo) {
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
