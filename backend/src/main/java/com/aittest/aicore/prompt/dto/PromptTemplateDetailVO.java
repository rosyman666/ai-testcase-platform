package com.aittest.aicore.prompt.dto;

import com.aittest.aicore.prompt.PromptTemplate;
import java.time.LocalDateTime;

/**
 * 模板详情出参（{@code GET /api/prompt-templates/{id}}）：比列表多一个 {@code content}。
 *
 * @param id           主键
 * @param code         模板编码
 * @param version      版本号
 * @param scene        场景
 * @param name         模板名称
 * @param content      模板正文（含 {@code &#123;&#123;var&#125;&#125;} 占位符）
 * @param outputSchema 输出 schema 路径
 * @param modelHint    推荐模型
 * @param active       是否当前生效版本
 * @param createdAt    创建时间（UTC）
 * @param updatedAt    更新时间（UTC）
 * @author 寇豆码
 */
public record PromptTemplateDetailVO(Long id, String code, Integer version, String scene, String name,
                                     String content, String outputSchema, String modelHint, Boolean active,
                                     LocalDateTime createdAt, LocalDateTime updatedAt) {

    /**
     * 实体 → 出参。
     *
     * @param template 实体
     * @return 出参
     */
    public static PromptTemplateDetailVO from(PromptTemplate template) {
        return new PromptTemplateDetailVO(
                template.getId(),
                template.getCode(),
                template.getVersion(),
                template.getScene(),
                template.getName(),
                template.getContent(),
                template.getOutputSchema(),
                template.getModelHint(),
                template.getIsActive() != null && template.getIsActive() == 1,
                template.getCreatedAt(),
                template.getUpdatedAt());
    }
}
