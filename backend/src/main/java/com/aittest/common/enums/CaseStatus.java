package com.aittest.common.enums;

/**
 * 用例评审状态（{@code test_case.status}）。
 *
 * <p><b>指标口径的关键</b>：采纳率分母只认 ACCEPTED + EDITED + REJECTED（"已评审数"），
 * <b>不含 DRAFT</b>。把 DRAFT 算进分母会让"还没人评审"拉低采纳率，掩盖真实质量。</p>
 *
 * @author 寇豆码
 */
public enum CaseStatus implements CodedEnum {

    /** 模型产出，尚未评审。 */
    DRAFT("DRAFT", "待评审", 10),

    /** 采纳（原文通过）。 */
    ACCEPTED("ACCEPTED", "已采纳", 20),

    /** 编辑后采纳。 */
    EDITED("EDITED", "编辑后采纳", 30),

    /** 驳回。 */
    REJECTED("REJECTED", "已驳回", 40);

    private final String code;

    private final String label;

    private final int sortNo;

    CaseStatus(String code, String label, int sortNo) {
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
     * 是否属于"已评审"（指标分母口径）。
     *
     * @return ACCEPTED/EDITED/REJECTED 返回 true
     */
    public boolean isReviewed() {
        return this == ACCEPTED || this == EDITED || this == REJECTED;
    }
}
