package com.aittest.testcase.domain;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.CaseStatus;
import com.aittest.common.enums.CodedEnum;

/**
 * 评审动作（{@code case_review.action}）：<b>三种动作，三种目标状态，一一对应</b>。
 *
 * <p><b>为什么把"动作 → 状态"写进枚举</b>：{@code test_case.status} 只能由评审动作改
 * （{@code PUT /api/cases/{id}} 也不许改 status），而动作与状态的映射一旦散落在 service 的
 * if/else 里，"加一个动作忘了映射状态"就变成一条静默的脏数据——台账里有这次评审，
 * 用例状态却还停在 DRAFT。放在枚举里，编译器会逼着每加一个动作就写一行映射。</p>
 *
 * <p><b>为什么 {@link #EDIT} 不通过 {@code POST /{id}/review} 触发</b>：编辑必须带内容，
 * 而评审请求里没有内容字段。没有内容的"编辑"只能写出 {@code old_content == new_content}
 * 的快照，那种台账行对 diff 毫无价值，却会被"编辑次数"指标当真。所以编辑走
 * {@code PUT /api/cases/{id}}，由 {@code CaseReviewService#edit} 负责快照。</p>
 *
 * @author 寇豆码
 */
public enum ReviewAction implements CodedEnum {

    /** 采纳（原文通过）。 */
    ACCEPT("ACCEPT", "采纳", 10, CaseStatus.ACCEPTED),

    /** 编辑后采纳（内容在 PUT 里改，本枚举负责它的台账与状态）。 */
    EDIT("EDIT", "编辑", 20, CaseStatus.EDITED),

    /** 驳回（必须带字典原因码）。 */
    REJECT("REJECT", "驳回", 30, CaseStatus.REJECTED);

    private final String code;

    private final String label;

    private final int sortNo;

    private final CaseStatus targetStatus;

    ReviewAction(String code, String label, int sortNo, CaseStatus targetStatus) {
        this.code = code;
        this.label = label;
        this.sortNo = sortNo;
        this.targetStatus = targetStatus;
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
     * 该动作对应的用例状态。
     *
     * @return 目标状态（永不 {@code null}）
     */
    public CaseStatus targetStatus() {
        return targetStatus;
    }

    /**
     * 解析动作码。
     *
     * <p><b>缺值与错值用不同的错误码</b>：不传 action 是"忘了选"（{@code 40002 必填缺失}），
     * 传了不认识的值是"参数非法"（{@code 40001}）。混成一个码，前端就没法区分
     * "表单没填"和"传了个脏值"，两种情况的修复方式完全不同。</p>
     *
     * @param raw 动作码（大小写不敏感，允许前后空白）
     * @return 动作
     * @throws BizException 缺值 {@code E_REQUIRED_MISSING} / 错值 {@code E_PARAM_INVALID}
     */
    public static ReviewAction of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.E_REQUIRED_MISSING, "缺少必填参数：action");
        }
        String wanted = raw.trim();
        for (ReviewAction action : values()) {
            if (action.code.equalsIgnoreCase(wanted)) {
                return action;
            }
        }
        throw new BizException(ErrorCode.E_PARAM_INVALID,
                "action 只能是 ACCEPT / EDIT / REJECT，实际：" + raw);
    }
}
