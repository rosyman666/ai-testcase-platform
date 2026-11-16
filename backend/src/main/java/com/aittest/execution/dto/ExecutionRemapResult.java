package com.aittest.execution.dto;

import java.math.BigDecimal;

/**
 * 重跑用例映射的结果（{@code POST /api/executions/{id}/remap}）。
 *
 * <p><b>只回三个数，不回整份详情</b>：调用方拿到这三个数就能就地更新"映射率"这一块 UI；
 * 若回整份 {@link ExecutionRunDetailVO}，前端得再拉一次明细才看得到"映射到的用例"列的变化，
 * 两次往返之间还会出现"比率已变、明细未变"的短暂不一致。需要更详细信息时前端自己
 * 重新拉详情即可（多一次请求是显式的，好过隐式的半新半旧）。</p>
 *
 * @param mappedCount 重算后映射到用例的条数
 * @param totalCount  本批结果总数（分母，取自批次计数）
 * @param mappedRate  映射率（0~1，4 位小数；与列表/详情同一算法）
 * @author 寇豆码
 */
public record ExecutionRemapResult(Integer mappedCount, Integer totalCount, BigDecimal mappedRate) {

    /**
     * 由两个计数构造。
     *
     * @param mappedCount 映射条数
     * @param totalCount  总条数
     * @return 结果
     */
    public static ExecutionRemapResult of(int mappedCount, int totalCount) {
        return new ExecutionRemapResult(mappedCount, totalCount,
                ExecutionRunVO.rateOf(mappedCount, totalCount));
    }
}
