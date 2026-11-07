package com.aittest.aicore.log;

import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 模型单价（{@code model_price} 的只读投影）。
 *
 * <p>为什么是"投影"而不是完整实体：算成本只需要 4 个字段（model + 两个单价 + 生效日期），
 * 其余列（currency / enabled / 时间戳）对记账没有意义。**只读投影能防住一类错**：
 * 有人图省事拿它去 {@code INSERT}，而它没有主键、没有必填列，写出来的行一定是坏的。</p>
 *
 * <p>单价单位是<b>每 1M token 的微元数</b>（§8.3）：{@code 1 元 = 1_000_000 微元}，
 * 所以"每百万 token 2 元"记作 {@code 2_000_000}。这样乘法不需要浮点：
 * {@code cost = (prompt×in + completion×out) / 1_000_000}，最后再四舍五入——全程整数，
 * 不会出现 0.1+0.2 那种尾差累积到"总成本对不上"。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString
public class ModelPrice {

    /** 模型名（与 {@code llm_call_log.model} 对应）。 */
    private String model;

    /** 输入单价：每 1M token 的微元数。 */
    private Integer inputPriceMicro;

    /** 输出单价：每 1M token 的微元数。 */
    private Integer outputPriceMicro;

    /** 生效日期（同一 model 取最新一条）。 */
    private LocalDate effectiveFrom;
}
