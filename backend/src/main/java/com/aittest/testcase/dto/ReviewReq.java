package com.aittest.testcase.dto;

import jakarta.validation.constraints.Size;

/**
 * 单条评审入参（{@code POST /api/cases/{id}/review}）。
 *
 * <p><b>只支持 ACCEPT / REJECT</b>：编辑必须带内容，而本请求没有内容字段；
 * 传 {@code EDIT} 会得到一条明确的 {@code 40001}（"编辑请走 PUT"），
 * 而不是被静默当成"把状态改成已编辑"。见 {@code ReviewAction} 类注释。</p>
 *
 * <p><b>{@code action} 为什么不加 {@code @NotBlank}</b>：加了的后果是 Spring 校验先抛
 * {@code 40001}，而"忘了选动作"在语义上是<b>必填缺失</b>（{@code 40002}）。
 * 两个码要让前端能区分（一个高亮表单、一个是参数错误），所以在
 * {@code ReviewAction.of(...)} 里判空。</p>
 *
 * <p><b>{@code rejectReasonCode} 的三态语义</b>（验收②）：</p>
 * <ul>
 *   <li>{@code action=REJECT} 且为空 → {@code 40002 必填缺失}（"忘了选原因"）；</li>
 *   <li>{@code action=REJECT} 且字典里不存在或已停用 → {@code 40001}（"选了一个用不了的原因"）；</li>
 *   <li>{@code action=ACCEPT} 时传了 → <b>忽略并落 NULL</b>，不报错：前端复用同一张表单，
 *       残留值报错只会让人困惑，而落库又会污染"驳回原因分布"。</li>
 * </ul>
 *
 * @param action           动作（ACCEPT / REJECT；必填）
 * @param rejectReasonCode 驳回原因字典码（REJECT 必填，其余忽略）
 * @param reasonText       自由文本说明（选填，任何动作都可带）
 * @author 寇豆码
 */
public record ReviewReq(
        @Size(max = 16, message = "动作最多 16 个字符")
        String action,

        @Size(max = 32, message = "驳回原因码最多 32 个字符")
        String rejectReasonCode,

        @Size(max = 1000, message = "说明最多 1000 个字符")
        String reasonText) {
}
