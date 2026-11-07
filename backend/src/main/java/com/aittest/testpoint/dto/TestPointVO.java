package com.aittest.testpoint.dto;

import com.aittest.testpoint.domain.TestPoint;
import java.time.LocalDateTime;

/**
 * 测试点出参。
 *
 * <p><b>返回 {@code contentHash} 是刻意的</b>：它是这条点的"身份证"，用户/运维排障时会问
 * "这两条是不是同一个点"。它不敏感（由标题+分类算出，不含用户数据），
 * 但省掉它就要让人手工算 SHA-256 去对。</p>
 *
 * @param id            主键（雪花 ID，序列化成字符串）
 * @param docId         所属文档
 * @param operationId   来源接口（纯文本为 null）
 * @param sectionId     来源章节（OpenAPI 为 null）
 * @param title         标题
 * @param category      分类编码
 * @param riskLevel     风险等级编码
 * @param description   描述
 * @param sourceRef     来源定位（可反查回原文）
 * @param status        状态编码
 * @param promptVersion 抽取所用的提示词版本（手补点为空串）
 * @param contentHash   幂等去重键
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 * @author 寇豆码
 */
public record TestPointVO(Long id,
                          Long docId,
                          Long operationId,
                          Long sectionId,
                          String title,
                          String category,
                          String riskLevel,
                          String description,
                          String sourceRef,
                          String status,
                          String promptVersion,
                          String contentHash,
                          LocalDateTime createdAt,
                          LocalDateTime updatedAt) {

    /**
     * 实体转出参。
     *
     * @param entity 实体
     * @return 出参
     */
    public static TestPointVO from(TestPoint entity) {
        return new TestPointVO(entity.getId(), entity.getDocId(), entity.getOperationId(),
                entity.getSectionId(), entity.getTitle(), entity.getCategory(),
                entity.getRiskLevel(), entity.getDescription(), entity.getSourceRef(),
                entity.getStatus(), entity.getPromptVersion(), entity.getContentHash(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
