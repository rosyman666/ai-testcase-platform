package com.aittest.common.enums;

/**
 * 失败归因来源（{@code execution_result.triage_source}）。
 *
 * <p>规则优先、LLM 兜底、人工可覆盖：记录来源是为了评估"规则覆盖了多少"，
 * 而不是把三种来源混在一起当成同一个准确性指标。</p>
 *
 * @author 寇豆码
 */
public enum TriageSource implements CodedEnum {

    /** 规则归因（关键词/异常类型匹配）。 */
    RULE("RULE", "规则", 10),

    /** 模型归因。 */
    LLM("LLM", "模型", 20),

    /** 人工归因。 */
    MANUAL("MANUAL", "人工", 30);

    private final String code;

    private final String label;

    private final int sortNo;

    TriageSource(String code, String label, int sortNo) {
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
