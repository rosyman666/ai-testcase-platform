package com.aittest.doc.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 文档章节（{@code doc_section}）：纯文本文档（Markdown / TXT / DOCX）的切分结果。
 *
 * <p><b>只追加表</b>（§3.2.1）：只有 {@code created_at}、**刻意没有 {@code updated_at}**，
 * 也没有 {@code deleted}。因此：</p>
 * <ul>
 *   <li>重传同一文件时走**物理删**（{@code DELETE FROM doc_section WHERE doc_id = ?}），
 *       MP 的逻辑删除对这张表不生效，也不需要写自定义 XML 去"绕"（§3.2.2 实现约束 1）；</li>
 *   <li>不要给它加 {@code @TableLogic}，也不要让 {@code MetaObjectHandler} 填 {@code updatedAt}——
 *       表上根本没有这一列，填了会报 unknown column。</li>
 * </ul>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString
@TableName("doc_section")
public class DocSection {

    /** 主键（雪花 ID）。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属文档，关联 {@code doc_source.id}。 */
    private Long docId;

    /** 章节标题（保留原始层级文本；无标题文档用"正文"兜底）。 */
    private String heading;

    /** 标题层级：1 = 一级标题。上限 500 字符由解析器保证。 */
    private Integer level;

    /** 同一文档内的顺序号（从 1 开始）。 */
    private Integer seqNo;

    /** 章节正文（纯文本；超长章节在解析器里二次切块，避免单片过大）。 */
    private String content;

    /** 创建时间（UTC），由 {@code AuditMetaObjectHandler} 填充。 */
    private java.time.LocalDateTime createdAt;
}
