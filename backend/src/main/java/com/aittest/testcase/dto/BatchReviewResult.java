package com.aittest.testcase.dto;

import java.util.List;

/**
 * 批量评审出参（{@code POST /api/cases/batch-review} 的 {@code data}），验收④。
 *
 * <p><b>为什么不是"返回一个数字"</b>：批量按钮在评审看板上一次点几十条，其中一部分
 * 可能刚被别人评过、或者已被删掉。只返回 {@code updated=17} 时，用户无法知道
 * "我选了 20 条，剩下 3 条去哪了"——他会以为系统吃了数据，然后重试，
 * 于是又产生一轮无意义的写。<b>"跳过原因可查"不是锦上添花，它是这个接口可信度的来源。</b></p>
 *
 * <p><b>为什么 {@code skipped} 里带 {@code id} 而不是下标</b>：前端要按 id 回填到行上
 * （把跳过的行标黄），下标在去重、排序之后与列表位置不再对应。</p>
 *
 * <p><b>为什么 {@code updated} 是 {@code int} 而不是 {@code boolean}</b>：部分成功是常态
 * （20 条里 3 条已评审），不是异常。返回布尔值会逼前端把"部分成功"当失败处理。</p>
 *
 * @param updated 实际落库的条数（台账 + 状态都写成功的）
 * @param skipped 被跳过的用例及原因（永不 {@code null}）
 * @author 寇豆码
 */
public record BatchReviewResult(int updated, List<Skipped> skipped) {

    /**
     * 规范化入参：{@code skipped} 为空时给空列表而不是 {@code null}。
     *
     * <p>前端 {@code data.skipped.length} 是它唯一的分支判据，返回 {@code null} 会让
     * 前端在"全部成功"这条最常见的路径上抛 {@code TypeError}。</p>
     */
    public BatchReviewResult {
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }

    /**
     * 构造出参。
     *
     * @param updated 实际落库条数
     * @param skipped 跳过明细（可为 {@code null}）
     * @return 出参
     */
    public static BatchReviewResult of(int updated, List<Skipped> skipped) {
        return new BatchReviewResult(updated, skipped);
    }

    /**
     * 一条跳过明细。
     *
     * @param id     用例主键
     * @param reason 可读中文原因（如 {@code 已评审（当前状态 ACCEPTED）}、{@code 用例不存在}）
     * @author 寇豆码
     */
    public record Skipped(Long id, String reason) {
    }
}
