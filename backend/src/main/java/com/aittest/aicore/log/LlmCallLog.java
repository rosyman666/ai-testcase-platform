package com.aittest.aicore.log;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 模型调用台账（{@code llm_call_log}）：<b>成本与质量指标的唯一真源</b>。
 *
 * <p><b>只追加表</b>（§3.2.1 / §11.2）：只有 {@code created_at}，<b>刻意没有 {@code updated_at}</b>——
 * 没有更新时间列，等于在表结构上承诺"台账不可改"。所以：</p>
 * <ul>
 *   <li>重试<b>不覆盖</b>：同一 item 的 3 次尝试落 3 行，{@code attempt_no} 递增（T04 ⑧）；</li>
 *   <li>不要给它加 {@code updated_at} 字段，也不要 {@code updateById}——账本一旦可改，
 *       "总成本"和"历史总成本"就会有两个值，而对账时没人分得清哪个是错的。</li>
 * </ul>
 *
 * <p><b>失败也记账</b>（§8.7 第 5 条）：上游 5xx 时 tokens 往往<b>已经消耗</b>
 * （网关先扣费再转发），不记这一行，成本会系统性偏低，而偏低的那部分恰好集中在
 * 失败重试——也就是最贵的场景。这就是"只记成功"看起来合理、实际最失真原因。</p>
 *
 * <p><b>写入路径</b>：走 {@code LlmCallLogMapper.append}（显式列名的 INSERT），
 * 不用 {@code BaseMapper.insert}。理由见 {@code mapper/LlmCallLogMapper.xml} 顶部注释。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString
@TableName("llm_call_log")
public class LlmCallLog {

    /** 主键（雪花 ID）。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联任务，{@code gen_task.id}；单次试跑为 {@code null}。 */
    private Long taskId;

    /** 关联任务子项，{@code gen_task_item.id}。 */
    private Long taskItemId;

    /** 链路 ID，与响应头 {@code X-Trace-Id} 一致。 */
    private String traceId;

    /** 调用场景：{@code TESTPOINT_EXTRACT} / {@code CASE_GENERATE} / {@code CASE_REPAIR} / {@code FAILURE_TRIAGE}。 */
    private String scene;

    /** 供应商：{@code mock} / {@code openai-compatible}。 */
    private String provider;

    /** 模型名（与 {@code model_price.model} 关联才能算出成本）。 */
    private String model;

    /** 输入 token 数。 */
    private Integer promptTokens;

    /** 输出 token 数。 */
    private Integer completionTokens;

    /** 本次成本（微元，1 元 = 1_000_000）。表单价 × token 数，四舍五入。 */
    private Long costMicro;

    /** 耗时（毫秒，含网络往返）。 */
    private Integer latencyMs;

    /** 第几次尝试（同 item 重试时递增，<b>不是</b>覆盖）。 */
    private Integer attemptNo;

    /** 调用结果：{@code SUCCESS} / {@code FAILED}。 */
    private String status;

    /** 失败错误码（如 {@code E_LLM_TIMEOUT}），成功时为空串。 */
    private String errorCode;

    /** 是否降级调用（{@code 1} 是）：请求声明降级或 Provider 自报降级，任一为真即 1。 */
    private Integer degraded;

    /** 创建时间（UTC），由 DDL 默认值 {@code CURRENT_TIMESTAMP(3)} 在<b>已钉死 UTC 的会话</b>上求值。 */
    private LocalDateTime createdAt;
}
