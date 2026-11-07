package com.aittest.doc.dto;

import com.aittest.doc.domain.ApiOperation;
import com.aittest.doc.domain.DocSection;
import com.aittest.doc.domain.DocSource;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.ToString;

/**
 * 文档详情出参（{@code GET /api/docs/{id}}），见 §4.4。
 *
 * <p><b>为什么与 {@link DocSourceVO} 分开而不是继承</b>：详情要带章节树与接口列表（嵌套结构），
 * 列表只要摘要。合成一个 VO 只有两种结局——列表为了"字段完整"拖全量（把 MEDIUMTEXT schema
 * 也带上），或者详情返回一堆 {@code null} 字段让前端做空值兼容。分开的成本只是多一个类
 * （§4.4 原文就是这么规定的，这里不重复取舍）。</p>
 *
 * <p>同理，本类**不继承** {@code DocSourceVO}：§4.4 要求 VO 与实体不共享父类，
 * VO 之间也不靠继承复用字段——继承会让"给列表加个字段"悄悄改变详情契约。</p>
 *
 * @author 寇豆码
 */
@Getter
@ToString
public class DocDetailVO {

    /** 文档 ID。 */
    private final Long id;

    /** 业务单号。 */
    private final String docNo;

    /** 原始文件名。 */
    private final String fileName;

    /** 文档类型编码。 */
    private final String docType;

    /** 文件字节数。 */
    private final Long fileSize;

    /** 解析状态。 */
    private final String parseStatus;

    /** 解析失败原因（中文可读）。 */
    private final String parseError;

    /** 章节数（取自 {@code doc_source} 冗余字段，不是列表长度）。 */
    private final Integer sectionCount;

    /** 接口操作数（同上）。 */
    private final Integer operationCount;

    /** 创建时间（UTC）。 */
    private final LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private final LocalDateTime updatedAt;

    /** 章节树（正文为预览）。 */
    private final List<DocSectionVO> sections;

    /** 接口列表（含已截断的 schema）。 */
    private final List<ApiOperationVO> operations;

    private DocDetailVO(DocSource entity, List<DocSection> sections, List<ApiOperation> operations,
            int sectionLimit, int operationLimit) {
        this.id = entity.getId();
        this.docNo = entity.getDocNo();
        this.fileName = entity.getName();
        this.docType = entity.getDocType();
        this.fileSize = entity.getFileSize();
        this.parseStatus = entity.getParseStatus();
        this.parseError = entity.getParseError();
        this.sectionCount = entity.getSectionCount();
        this.operationCount = entity.getOperationCount();
        this.createdAt = entity.getCreatedAt();
        this.updatedAt = entity.getUpdatedAt();
        this.sections = sections == null ? List.of()
                : sections.stream().limit(sectionLimit).map(DocSectionVO::fromPreview).toList();
        this.operations = operations == null ? List.of()
                : operations.stream().limit(operationLimit).map(ApiOperationVO::fromDetail).toList();
    }

    /**
     * 构造详情 VO。
     *
     * <p>章节与接口各取前 N 条内嵌：文档详情页首屏只需要看结构，全量请走
     * {@code /sections}、{@code /operations} 两个分页接口。上限取 200 是经验值——
     * 足够覆盖绝大多数需求文档，又能把响应体压在几百 KB 内。</p>
     *
     * @param entity         文档实体
     * @param sections       章节列表（已按 seq_no 排序）
     * @param operations     接口列表（已按 seq_no 排序）
     * @return 详情 VO；{@code entity} 为 null 时返回 null
     */
    public static DocDetailVO of(DocSource entity, List<DocSection> sections, List<ApiOperation> operations) {
        if (entity == null) {
            return null;
        }
        return new DocDetailVO(entity, sections, operations, 200, 200);
    }
}
