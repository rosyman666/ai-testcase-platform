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

    /**
     * 内嵌章节/接口的条数上限。
     *
     * <p>公开给 {@code DocService} 用，让"SQL 的 LIMIT"与"VO 的 limit"是同一个数字——
     * 两处各写一个 200，改一处忘一处就会出现"查了 200 条但只返回 100 条"这种看不出错的现象。</p>
     */
    public static final int EMBED_LIMIT = 200;

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

    /** 章节树（正文为预览，最多 {@link #EMBED_LIMIT} 条）。 */
    private final List<DocSectionVO> sections;

    /** 接口列表（含已截断的 schema，最多 {@link #EMBED_LIMIT} 条）。 */
    private final List<ApiOperationVO> operations;

    /**
     * 章节总数（{@code COUNT(*)}，不是 {@link #sections} 的长度）。
     *
     * <p><b>为什么必须单独给</b>：内嵌列表有上限，前端拿到的 {@code sections.size()} 可能是
     * 被截断后的值。只给列表而不给总数，前端无法区分"文档只有 200 条"与"被截了 200 条"，
     * 用户会以为后面的内容不存在——静默截断比报错更难查。</p>
     */
    private final Integer totalSectionCount;

    /** 接口总数（同上，{@code COUNT(*)}）。 */
    private final Integer totalOperationCount;

    private DocDetailVO(DocSource entity, List<DocSection> sections, List<ApiOperation> operations,
            int totalSectionCount, int totalOperationCount) {
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
                : sections.stream().limit(EMBED_LIMIT).map(DocSectionVO::fromPreview).toList();
        this.operations = operations == null ? List.of()
                : operations.stream().limit(EMBED_LIMIT).map(ApiOperationVO::fromDetail).toList();
        this.totalSectionCount = totalSectionCount;
        this.totalOperationCount = totalOperationCount;
    }

    /**
     * 构造详情 VO。
     *
     * <p>章节与接口各取前 {@value #EMBED_LIMIT} 条内嵌：文档详情页首屏只需要看结构，
     * 全量请走 {@code /sections}、{@code /operations} 两个分页接口。上限取 200 是经验值——
     * 足够覆盖绝大多数需求文档，又能把响应体压在几百 KB 内。</p>
     *
     * <p><b>计数必须与列表一起传进来</b>：调用方拿 {@code COUNT(*)} 的真实总数填入
     * {@code totalSectionCount} / {@code totalOperationCount}，前端靠它判断是否发生了截断。
     * 不要在这里用 {@code sections.size()} 顶替——那正好抹掉了截断信息。</p>
     *
     * @param entity              文档实体
     * @param sections            章节列表（已按 seq_no 排序，最多取 {@value #EMBED_LIMIT} 条）
     * @param operations          接口列表（已按 seq_no 排序，最多取 {@value #EMBED_LIMIT} 条）
     * @param totalSectionCount   章节总数（{@code COUNT(*)}）
     * @param totalOperationCount 接口总数（{@code COUNT(*)}）
     * @return 详情 VO；{@code entity} 为 null 时返回 null
     */
    public static DocDetailVO of(DocSource entity, List<DocSection> sections,
            List<ApiOperation> operations, long totalSectionCount, long totalOperationCount) {
        if (entity == null) {
            return null;
        }
        return new DocDetailVO(entity, sections, operations,
                (int) totalSectionCount, (int) totalOperationCount);
    }
}
