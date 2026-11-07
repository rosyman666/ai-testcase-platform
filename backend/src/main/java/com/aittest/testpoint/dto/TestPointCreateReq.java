package com.aittest.testpoint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 手动补点入参（{@code POST /api/testpoints}）。
 *
 * <p><b>手补点与 AI 抽的点共用同一张表、同一套状态</b>：区别只在 {@code promptVersion}（手补为空串）。
 * 拆成两张表会让评审页写两套列表/编辑/启停逻辑，而且"采纳率"这类指标的分母永远算不清。</p>
 *
 * <p><b>不接受 {@code sourceRef}</b>：手补点没有来源文档上下文，来源定位是空串。
 * 让用户填一个自由文本的来源，只会填出无法反查的值。</p>
 *
 * @param docId       所属文档
 * @param title       标题（4~500 字，与列宽一致）
 * @param category    分类编码，见 {@code common.enums.CaseType}
 * @param riskLevel   风险等级编码（可空，默认 MEDIUM）
 * @param description 描述（可空）
 * @author 寇豆码
 */
public record TestPointCreateReq(
        @NotNull(message = "所属文档不能为空")
        Long docId,

        @NotBlank(message = "标题不能为空")
        @Size(min = 4, max = 500, message = "标题需 4~500 个字符")
        String title,

        @NotBlank(message = "分类不能为空")
        @Size(max = 32, message = "分类最多 32 个字符")
        String category,

        @Size(max = 16, message = "风险等级最多 16 个字符")
        String riskLevel,

        @Size(max = 4000, message = "描述最多 4000 个字符")
        String description) {
}
