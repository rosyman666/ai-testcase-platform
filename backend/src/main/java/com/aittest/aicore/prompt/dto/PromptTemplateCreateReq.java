package com.aittest.aicore.prompt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建模板版本入参（{@code POST /api/prompt-templates}）。
 *
 * <p><b>版本号不在入参里</b>：它由服务端按"同 code 最大值 + 1"生成。让前端传版本号，
 * 两个人同时提交就会撞成同一个号（然后就有人"顺手"让它覆盖），而模板版本一旦可覆盖，
 * {@code test_case.prompt_version} 指向的就是一个内容已经变了的东西——回溯彻底失效。</p>
 *
 * @param code         模板编码（新建版本时复用已有 code）
 * @param name         模板名称
 * @param scene        场景，取值见 {@code TaskScene} / {@code CASE_REPAIR} / {@code FAILURE_TRIAGE}
 * @param content      模板正文
 * @param outputSchema 输出 schema 路径（如 {@code classpath:schema/testcase.schema.json}）
 * @param modelHint    推荐模型（可空）
 * @author 寇豆码
 */
public record PromptTemplateCreateReq(
        @NotBlank(message = "模板编码不能为空")
        @Size(max = 64, message = "模板编码最多 64 个字符")
        @Pattern(regexp = "^[a-z0-9_]+$", message = "模板编码只能用小写字母、数字和下划线")
        String code,

        @NotBlank(message = "模板名称不能为空")
        @Size(max = 200, message = "模板名称最多 200 个字符")
        String name,

        @NotBlank(message = "场景不能为空")
        @Size(max = 32, message = "场景最多 32 个字符")
        String scene,

        @NotBlank(message = "模板正文不能为空")
        String content,

        @Size(max = 255, message = "schema 路径最多 255 个字符")
        String outputSchema,

        @Size(max = 64, message = "推荐模型最多 64 个字符")
        String modelHint) {
}
