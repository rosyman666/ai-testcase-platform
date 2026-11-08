package com.aittest.testcase.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量评审入参（{@code POST /api/cases/batch-review}）。
 *
 * <p><b>字段与单条评审保持一致</b>（{@code action} / {@code rejectReasonCode} / {@code reasonText}），
 * 只多一个 {@code ids}。前端"全选 + 点驳回"和"单条驳回"复用同一份校验规则，
 * 若两边字段名或语义有差异，就会出现"单条能评、批量报 40001"的诡异现象。</p>
 *
 * <p><b>{@code ids} 为什么不加 {@code @NotEmpty}</b>：与 {@link ReviewReq#action()} 同一个理由——
 * "一条都没选"在语义上是<b>必填缺失</b>（{@code 40002}），加 {@code @NotEmpty} 会被 Spring
 * 校验先抛成 {@code 40001}。判空放在 {@code CaseReviewService#batchReview} 里，
 * 与"没选驳回原因"用同一个错误码，前端一套 toast 逻辑就能覆盖。</p>
 *
 * <p><b>上限 200 条</b>：批量评审是一个事务里的 N 次 INSERT + N 次 UPDATE，
 * 无上限时前端"全选"会把整页几千条塞进来，一个长事务持有大量行锁，
 * 期间任何一条用例的评审都会被阻塞。200 条 ≈ 10 页看板，覆盖真实操作习惯。</p>
 *
 * @param ids              待评审的用例 ID（去重后处理，重复的只算一条）
 * @param action           动作（ACCEPT / REJECT；<b>不支持 EDIT</b>，编辑必须逐条带内容）
 * @param rejectReasonCode 驳回原因字典码（{@code action=REJECT} 时必填）
 * @param reasonText       自由文本说明（选填，整批共用一条）
 * @author 寇豆码
 */
public record BatchReviewReq(
        @Size(max = 200, message = "一次最多评审 200 条用例")
        List<Long> ids,

        @Size(max = 16, message = "动作最多 16 个字符")
        String action,

        @Size(max = 32, message = "驳回原因码最多 32 个字符")
        String rejectReasonCode,

        @Size(max = 1000, message = "说明最多 1000 个字符")
        String reasonText) {
}
