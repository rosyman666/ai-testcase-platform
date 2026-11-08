package com.aittest.generation.orchestrator;

import com.aittest.testcase.dto.CaseDraft;
import java.util.List;

/**
 * 一次用例生成的<b>纯输出</b>：草稿 + 累计指标 + 档位。
 *
 * <p><b>指标是"本次 item 的全部调用之和"</b>，不是主调用的值：三档降级下，一个 item 可能
 * 调用 1 次（正常/截断）、2 次（返修一轮成功）、3 次（返修两轮）。若只回主调用的 token，
 * "每个用例的成本"会随降级率系统性偏低，而恰恰降级批次才是成本最高的那批——
 * 报表方向正好反了。</p>
 *
 * <p><b>{@code latencyMs} 是整段墙钟时间</b>（含返修往返），不是某一次调用的耗时：
 * 上层用它回答"这个 item 让用户等了多久"，用户不会关心内部拆成了几次调用。</p>
 *
 * <p><b>{@code drafts} 为空有两种含义，靠 {@code level} 区分</b>：</p>
 * <ul>
 *   <li>{@code NORMAL} + 空：模型给了一个通过校验的空数组（仅在未配 schema 时可达，见 D-41）；</li>
 *   <li>{@code LENIENT} + 空：三档全部走完仍然一条都救不回来 → 调用方应判
 *       {@code E_SCHEMA_INVALID} 失败，<b>不是</b> {@code E_LLM_EMPTY}。</li>
 * </ul>
 *
 * @param drafts           解析出的用例草稿（可能为空，不会为 {@code null}）
 * @param promptTokens     本次 item 全部调用的 prompt token 之和
 * @param completionTokens 本次 item 全部调用的 completion token 之和
 * @param costMicro        本次 item 全部调用的成本之和（微元）
 * @param latencyMs        整段墙钟耗时（毫秒）
 * @param level            解析保真度档位
 * @param degradeReason    档位判定原因（非空，直接进日志）
 * @param truncated        是否存在截断补全（为真必定 {@code LENIENT}）
 * @param repairRounds     实际用掉的模型返修轮次（0~{@code MAX_REPAIR_ROUNDS}）
 * @author 寇豆码
 */
public record GenerationResult(List<CaseDraft> drafts,
                               int promptTokens,
                               int completionTokens,
                               long costMicro,
                               int latencyMs,
                               DegradeLevel level,
                               String degradeReason,
                               boolean truncated,
                               int repairRounds) {

    /**
     * 是否走了"宽松档"（落库时 {@code degraded=1}、{@code confidence=0.500}）。
     *
     * @return 档位是否为 {@link DegradeLevel#LENIENT}
     */
    public boolean lenient() {
        return level == DegradeLevel.LENIENT;
    }

    /**
     * 产出条数。
     *
     * @return {@code drafts.size()}
     */
    public int produced() {
        return drafts == null ? 0 : drafts.size();
    }
}
