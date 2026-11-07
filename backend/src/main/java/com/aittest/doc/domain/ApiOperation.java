package com.aittest.doc.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 接口操作（{@code api_operation}）：OpenAPI 文档按 operation 切分的结果。
 *
 * <p>这张表是"按接口维度切分调用"的物理载体：T05 抽测试点、T04 拼提示词都以行为单位。</p>
 *
 * <p><b>唯一键 {@code uk_api_operation_doc_method_path (doc_id, method, path)}</b> 决定了
 * 重传同一文档时**必须先物理删旧行再插入**——新旧行的 {@code (doc_id, method, path)} 完全相同，
 * 先插必撞键（§3.2.2 实现约束 2）。</p>
 *
 * <p>三个 schema 字段是 {@code MEDIUMTEXT}，存放**已裁剪**的 JSON 片段（{@code $ref} 浅解析 ≤3 层），
 * 因此不会出现循环引用导致的无限膨胀。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString
@TableName("api_operation")
public class ApiOperation {

    /** 主键（雪花 ID）。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属文档，关联 {@code doc_source.id}。 */
    private Long docId;

    /** HTTP 方法（大写）：GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS/TRACE。 */
    private String method;

    /** 接口路径（OpenAPI {@code paths} 的 key），上限 500 字符由解析器保证。 */
    private String path;

    /** OpenAPI {@code operationId}，可能为空串。 */
    private String operationId;

    /** 接口摘要（{@code summary}，为空时回退 {@code description} 首行）。 */
    private String summary;

    /** OpenAPI {@code tags}，逗号分隔。 */
    private String tags;

    /** 请求体 schema（已裁剪的 JSON 片段）。 */
    private String requestSchema;

    /** 响应体 schema（已裁剪的 JSON 片段）。 */
    private String responseSchema;

    /** path/query/header 参数 schema（已裁剪的 JSON 片段）。 */
    private String paramSchema;

    /** 同一文档内的顺序号（从 1 开始）。 */
    private Integer seqNo;

    /** 创建时间（UTC），由 {@code AuditMetaObjectHandler} 填充。 */
    private java.time.LocalDateTime createdAt;
}
