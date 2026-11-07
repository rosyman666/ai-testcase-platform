package com.aittest.aicore.prompt.dto;

import com.aittest.aicore.prompt.PromptTemplate;
import java.time.LocalDateTime;

/**
 * 模板列表出参（{@code GET /api/prompt-templates}），见 §4.4。
 *
 * <p><b>不含 {@code content}</b>：一条模板正文三千到八千字，10 个版本就是几十 KB，
 * 而列表页只显示"哪套模板、第几版、是否生效"。正文由详情接口单独取——
 * 这是 §4.2 把"列表"与"详情"分成两个端点的原因，不是冗余。</p>
 *
 * @param id           主键（Jackson 序列化为字符串）
 * @param code         模板编码
 * @param version      版本号
 * @param scene        场景
 * @param name         模板名称
 * @param outputSchema 输出 schema 路径
 * @param modelHint    推荐模型（仅供参考，不参与路由）
 * @param active       是否当前生效版本
 * @param createdAt    创建时间（UTC）
 * @param updatedAt    更新时间（UTC）
 * @author 寇豆码
 */
public record PromptTemplateVO(Long id, String code, Integer version, String scene, String name,
                               String outputSchema, String modelHint, Boolean active,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {

    /**
     * 实体 → 出参（显式逐字段，不用 {@code BeanUtils} 全量拷贝）。
     *
     * @param template 实体
     * @return 出参
     */
    public static PromptTemplateVO from(PromptTemplate template) {
        return new PromptTemplateVO(
                template.getId(),
                template.getCode(),
                template.getVersion(),
                template.getScene(),
                template.getName(),
                template.getOutputSchema(),
                template.getModelHint(),
                template.getIsActive() != null && template.getIsActive() == 1,
                template.getCreatedAt(),
                template.getUpdatedAt());
    }
}
