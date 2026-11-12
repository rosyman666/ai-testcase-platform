package com.aittest.metrics.dto;

import lombok.Getter;

/**
 * 成本为 0 的三成因（docs/07 §3.2 / PRD v1.2 T11-P0-10）。
 *
 * <p><b>不隐藏、不现算、不伪造</b>：{@code SUM(cost_micro)=0} 在现行记账口径下是
 * 正确算出的结果，前端要显示「成本 0（原因：…）」而不是把 0 藏掉，也不能按
 * {@code model_price} 在读取侧补算一个看起来更合理的数。</p>
 *
 * <p>其中 {@link #MODEL_PRICE_MISSING} 是<b>配置缺口，不是代码缺陷</b>：排查方向是
 * {@code .env} 的 {@code AI_MODEL} 或历史验收脚本传参，不是 {@code MockLlmProvider}。</p>
 *
 * @author 寇豆码
 */
@Getter
public enum CostZeroReason {

    /** mock-model 单价配置为 0/0（正常配置）。 */
    MOCK_PRICE_ZERO("mock-model 单价配置为 0/0"),

    /** 失败调用无 token 回显（正常）。 */
    FAILED_CALL_NO_TOKEN("失败调用无 token 回显"),

    /** 模型缺少 model_price 单价行（配置缺口，非代码缺陷）。 */
    MODEL_PRICE_MISSING("模型缺少 model_price 单价行（配置缺口）");

    /** 可直接展示的中文文案。 */
    private final String label;

    CostZeroReason(String label) {
        this.label = label;
    }
}
