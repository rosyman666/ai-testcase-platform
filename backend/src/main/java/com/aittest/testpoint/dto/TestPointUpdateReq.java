package com.aittest.testpoint.dto;

import jakarta.validation.constraints.Size;

/**
 * 更新测试点入参（{@code PATCH /api/testpoints/{id}}），字段全部可选。
 *
 * <p><b>改 {@code title} 或 {@code category} 会改变身份</b>：{@code content_hash = sha256(doc_id + title + category)}。
 * 去重键一变，下次重抽时这条点会被判成"旧点消失、新点出现"——旧的置 {@code SUPERSEDED}、
 * 新的重新插入。<b>这不是 bug，是"改标题等于换一个点"的必然结果</b>，服务层会同步重算
 * {@code contentHash} 以保证唯一索引不被脏值卡住。</p>
 *
 * <p><b>{@code description} 与 {@code riskLevel} 改动不影响身份</b>：这正是它们不进哈希的原因
 * （见 {@code TestPointHasher} 类注释）——人可以反复修订描述，而不必担心点被重抽冲掉。</p>
 *
 * @param title       标题（可空）
 * @param category    分类编码（可空）
 * @param riskLevel   风险等级编码（可空）
 * @param description 描述（可空）
 * @param status      状态编码（可空：ACTIVE/DISABLED；SUPERSEDED 不允许手工设置）
 * @author 寇豆码
 */
public record TestPointUpdateReq(
        @Size(min = 4, max = 500, message = "标题需 4~500 个字符")
        String title,

        @Size(max = 32, message = "分类最多 32 个字符")
        String category,

        @Size(max = 16, message = "风险等级最多 16 个字符")
        String riskLevel,

        @Size(max = 4000, message = "描述最多 4000 个字符")
        String description,

        @Size(max = 20, message = "状态最多 20 个字符")
        String status) {
}
