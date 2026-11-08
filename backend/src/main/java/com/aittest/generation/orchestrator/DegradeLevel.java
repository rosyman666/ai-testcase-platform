package com.aittest.generation.orchestrator;

/**
 * 用例生成的<b>解析保真度档位</b>（D-39 的三档语义）。
 *
 * <p>它不是"模型质量评分"，也不是"用例好坏"，只描述<b>这一次输出是怎么被拿到手的</b>：</p>
 * <ol>
 *   <li>{@link #NORMAL}：解析 + schema 校验一次通过（本地语法修复不算降级，见 D-03）；</li>
 *   <li>{@link #REPAIRED}：本地修不好，把"原输出 + 校验错误"回给模型返修后通过；</li>
 *   <li>{@link #LENIENT}：返修也没救回来，退到<b>元素级</b>筛选，只保留合规元素
 *       （或被截断时丢弃不完整元素）。</li>
 * </ol>
 *
 * <p><b>为什么要把这三档显式建模</b>：三档对下游的影响完全不同——{@code NORMAL} 与
 * {@code REPAIRED} 落 {@code confidence=1.000}、{@code gen_task_item.degraded=0}；
 * {@code LENIENT} 落 {@code confidence=0.500}、{@code degraded=1}。
 * 如果只在代码里"顺手"打一个 boolean，哪个分支忘了置位都不会报错，
 * 结果就是"降级批"混进正常批，验收③（三档可复现）再也无法从库里区分出来。</p>
 *
 * @author 寇豆码
 */
public enum DegradeLevel {

    /** 正常：一次通过（本地语法修复仍算正常，它不改语义）。 */
    NORMAL("正常"),

    /** 修复：模型返修后通过。 */
    REPAIRED("修复"),

    /** 宽松：只保住合规元素，产出可信度打折。 */
    LENIENT("宽松");

    private final String label;

    DegradeLevel(String label) {
        this.label = label;
    }

    /**
     * 中文短名（日志/落库备注用）。
     *
     * @return 中文短名
     */
    public String label() {
        return label;
    }
}
