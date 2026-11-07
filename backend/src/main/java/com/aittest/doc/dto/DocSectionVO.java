package com.aittest.doc.dto;

import com.aittest.doc.domain.DocSection;
import lombok.Getter;
import lombok.ToString;

/**
 * 章节出参（详情内嵌 + {@code GET /api/docs/{id}/sections} 分页）。
 *
 * <p>{@link #content} 在详情内嵌场景只给**预览**（避免一篇几 MB 的文档把详情响应撑爆），
 * 需要全文时走分页接口拿 {@link #fromFull}。预览会额外给出 {@link #contentLength}，
 * 前端据此显示"已截断"，不让用户以为文档就这么多内容。</p>
 *
 * @author 寇豆码
 */
@Getter
@ToString
public class DocSectionVO {

    /** 详情内嵌时正文预览的最大字符数。 */
    private static final int PREVIEW_MAX = 1_000;

    /** 章节 ID。 */
    private final Long id;

    /** 章节标题。 */
    private final String heading;

    /** 标题层级（1 = 一级标题）。 */
    private final Integer level;

    /** 文档内顺序号（从 1 开始）。 */
    private final Integer seqNo;

    /** 正文（详情场景为预览，可能已截断）。 */
    private final String content;

    /** 正文原始字符数（用于提示"已截断"）。 */
    private final int contentLength;

    private DocSectionVO(Long id, String heading, Integer level, Integer seqNo, String content, int contentLength) {
        this.id = id;
        this.heading = heading;
        this.level = level;
        this.seqNo = seqNo;
        this.content = content;
        this.contentLength = contentLength;
    }

    /**
     * 实体转 VO（全文，用于分页接口）。
     *
     * @param entity 章节实体
     * @return VO；入参为 null 时返回 null
     */
    public static DocSectionVO fromFull(DocSection entity) {
        if (entity == null) {
            return null;
        }
        String content = entity.getContent() == null ? "" : entity.getContent();
        return new DocSectionVO(entity.getId(), entity.getHeading(), entity.getLevel(), entity.getSeqNo(),
                content, content.length());
    }

    /**
     * 实体转 VO（预览，用于文档详情内嵌）。
     *
     * @param entity 章节实体
     * @return VO；入参为 null 时返回 null
     */
    public static DocSectionVO fromPreview(DocSection entity) {
        if (entity == null) {
            return null;
        }
        String content = entity.getContent() == null ? "" : entity.getContent();
        int length = content.length();
        String preview = length <= PREVIEW_MAX ? content : content.substring(0, PREVIEW_MAX) + "…";
        return new DocSectionVO(entity.getId(), entity.getHeading(), entity.getLevel(), entity.getSeqNo(),
                preview, length);
    }
}
