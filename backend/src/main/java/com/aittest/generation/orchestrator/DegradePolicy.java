package com.aittest.generation.orchestrator;

/**
 * 三档降级的<b>判定规则</b>：纯函数，无副作用，不碰 Model、不碰数据库、不打日志。
 *
 * <p><b>为什么单独抽出来</b>：档位判定是整个降级链路里唯一"有业务判断"的一步，
 * 其余步骤（渲染、调用、解析、落库）都是搬运。把它写成纯函数有三个直接好处：</p>
 * <ol>
 *   <li><b>可穷举验证</b>：5 个 boolean/计数入参的组合是有限的，{@code CaseGenerateOrchestratorTest}
 *       可以逐档断言，不必每次都跑一遍真实 mock 调用链；</li>
 *   <li><b>改规则不会牵动 I/O</b>："截断算不算降级""返修成功算不算正常"这类口径一定会变，
 *       改这里不会碰到 mapper、事务、SSE；</li>
 *   <li><b>可解释</b>：每次都带一句 {@code reason} 落日志，事后能回答"这批为什么是 0.5 置信度"。</li>
 * </ol>
 *
 * <p><b>判定顺序（顺序本身就是规则，不要重排）</b>：</p>
 * <ol>
 *   <li>{@code validItemCount <= 0} → 宽松：一条都没保住，调用方应当判失败；</li>
 *   <li>{@code truncated} → 宽松：输出被截断，丢掉了不完整元素（D-03）；</li>
 *   <li>{@code responseDegraded} → 宽松：本次采纳的输出未经完整结构校验/上游自报降级；</li>
 *   <li>{@code repairRoundsUsed > 0} → 修复；</li>
 *   <li>{@code localRepaired} → 正常（本地语法修复不改语义，只是把围栏/尾逗号去掉）；</li>
 *   <li>否则 → 正常。</li>
 * </ol>
 *
 * @author 寇豆码
 */
public final class DegradePolicy {

    private DegradePolicy() {
    }

    /**
     * 判定结果：档位 + 一句可落日志的中文原因。
     *
     * @param level  档位
     * @param reason 原因（非空，用于日志与排障）
     */
    public record Verdict(DegradeLevel level, String reason) {
    }

    /**
     * 按事实判定档位。
     *
     * @param localRepaired    本地 {@code JsonRepairer} 是否改动过模型输出（围栏/尾逗号/单引号/截断补全）
     * @param truncated        是否存在"截断补全"（{@code finish_reason=length} 或括号不配平）
     * @param responseDegraded 本次采纳的输出是否属于降级输出：
     *                         上游自报 {@code degraded}，或调用方在宽松档采纳了
     *                         <b>未经完整 schema 校验</b> 的元素级筛选结果
     * @param repairRoundsUsed 已经用掉的返修轮次（0 表示没调用过模型返修）
     * @param validItemCount   最终可落库的合规条目数
     * @return 档位与原因（永不为 {@code null}）
     */
    public static Verdict decide(boolean localRepaired, boolean truncated, boolean responseDegraded,
                                 int repairRoundsUsed, int validItemCount) {
        if (validItemCount <= 0) {
            return new Verdict(DegradeLevel.LENIENT, "宽松档：元素级筛选后没有可保留的合规用例");
        }
        if (truncated) {
            return new Verdict(DegradeLevel.LENIENT,
                    "宽松档：模型输出被截断，丢弃不完整元素后保留 " + validItemCount + " 条");
        }
        if (responseDegraded) {
            return new Verdict(DegradeLevel.LENIENT,
                    "宽松档：本次采纳的输出未经完整结构校验，元素级筛选后保留 " + validItemCount + " 条");
        }
        if (repairRoundsUsed > 0) {
            return new Verdict(DegradeLevel.REPAIRED,
                    "修复档：模型返修 " + repairRoundsUsed + " 轮后通过结构校验，共 " + validItemCount + " 条");
        }
        if (localRepaired) {
            return new Verdict(DegradeLevel.NORMAL,
                    "正常档：本地语法修复后一次通过结构校验（未调用模型返修），共 " + validItemCount + " 条");
        }
        return new Verdict(DegradeLevel.NORMAL,
                "正常档：模型输出一次通过解析与结构校验，共 " + validItemCount + " 条");
    }
}
