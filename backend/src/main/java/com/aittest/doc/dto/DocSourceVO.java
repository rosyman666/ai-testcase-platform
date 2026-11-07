package com.aittest.doc.dto;

import com.aittest.doc.domain.DocSource;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.ToString;

/**
 * 文档列表出参（{@code GET /api/docs}），见 §4.4。
 *
 * <p><b>只带列表需要的字段</b>：{@code storage_path}、{@code deleted}、{@code created_by}
 * 一律不出现在响应里（前两个是实现细节，第三个是单用户形态下的多余信息，暴露出去会让前端
 * 开始依赖它，之后做权限就得先改契约）。</p>
 *
 * <p>转换用显式逐字段赋值（{@link #from}），**不用** {@code BeanUtils.copyProperties}——
 * 全量拷贝会在有人给实体加字段时自动把新字段也漏出去，而这里"新增字段默认不泄露"是默认行为。</p>
 *
 * @author 寇豆码
 */
@Getter
@ToString
public class DocSourceVO {

    /** 文档 ID（Jackson 全局序列化为字符串）。 */
    private final Long id;

    /** 业务单号。 */
    private final String docNo;

    /** 原始文件名。 */
    private final String fileName;

    /** 文档类型编码（MD/TXT/DOCX/OPENAPI_JSON）。 */
    private final String docType;

    /** 文件字节数。 */
    private final Long fileSize;

    /** 解析状态（PENDING/PARSED/FAILED）。 */
    private final String parseStatus;

    /** 解析失败原因（中文可读，空串表示无错误）。 */
    private final String parseError;

    /** 章节数。 */
    private final Integer sectionCount;

    /** 接口操作数。 */
    private final Integer operationCount;

    /** 创建时间（UTC）。 */
    private final LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private final LocalDateTime updatedAt;

    private DocSourceVO(Long id, String docNo, String fileName, String docType, Long fileSize,
            String parseStatus, String parseError, Integer sectionCount, Integer operationCount,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.docNo = docNo;
        this.fileName = fileName;
        this.docType = docType;
        this.fileSize = fileSize;
        this.parseStatus = parseStatus;
        this.parseError = parseError;
        this.sectionCount = sectionCount;
        this.operationCount = operationCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 实体转列表 VO（显式逐字段，见类注释）。
     *
     * @param entity 文档实体
     * @return 列表 VO；入参为 null 时返回 null
     */
    public static DocSourceVO from(DocSource entity) {
        if (entity == null) {
            return null;
        }
        return new DocSourceVO(
                entity.getId(),
                entity.getDocNo(),
                entity.getName(),
                entity.getDocType(),
                entity.getFileSize(),
                entity.getParseStatus(),
                entity.getParseError(),
                entity.getSectionCount(),
                entity.getOperationCount(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
