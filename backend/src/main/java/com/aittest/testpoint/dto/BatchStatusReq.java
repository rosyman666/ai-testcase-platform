package com.aittest.testpoint.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量启停入参（{@code POST /api/testpoints/batch-status}）。
 *
 * <p><b>为什么是批量而不是逐条 {@code PATCH}</b>：评审页上"停用这一批"是高频动作
 * （一次抽 30 条，人一眼扫出 8 条没意义的）。逐条 PATCH 要发 8 个请求，
 * 而且中途断网会留下"停了 3 条、剩下 5 条还是启用"的半成品状态——批量接口一次事务搞定。</p>
 *
 * @param ids    测试点 ID 列表
 * @param status 目标状态（{@code ACTIVE} / {@code DISABLED}）
 * @author 寇豆码
 */
public record BatchStatusReq(
        @NotEmpty(message = "至少选择一个测试点")
        @Size(max = 500, message = "一次最多处理 500 个")
        List<Long> ids,

        @Size(max = 20, message = "状态最多 20 个字符")
        String status) {
}
