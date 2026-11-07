package com.aittest.doc.dto;

import com.aittest.doc.domain.ApiOperation;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.ToString;

/**
 * 接口操作出参（详情内嵌 + {@code GET /api/docs/{id}/operations} 分页）。
 *
 * <p><b>三个 schema 字段为什么带 {@code @JsonInclude(NON_NULL)}</b>：§4.4 规定
 * "列表场景不带 schema 三兄弟，详情才带"。同一个 VO 两处复用，靠"列表场景传 null"实现，
 * 若不加这个注解，列表响应里会出现三个 {@code "requestSchema": null}——
 * 前端会以为"这个字段存在但为空"，而实际语义是"这个接口不看这个字段"。
 * 用 NON_NULL 让键直接不出现，语义清晰。</p>
 *
 * <p>schema 是 {@code MEDIUMTEXT}，详情返回时已按 20000 字符截断；{@link #schemaTruncated}
 * 告诉前端"你看到的不是全部"，避免用户拿残缺 JSON 去做校验。</p>
 *
 * @author 寇豆码
 */
@Getter
@ToString
public class ApiOperationVO {

    /** 截断标记（解析器写在被截断字符串尾部）。 */
    private static final String TRUNCATE_MARK = "…[truncated:originalLength=";

    /** 记录 ID。 */
    private final Long id;

    /** HTTP 方法（大写）。 */
    private final String method;

    /** 接口路径。 */
    private final String path;

    /** 接口摘要。 */
    private final String summary;

    /** tags（逗号分隔）。 */
    private final String tags;

    /** 文档内顺序号（从 1 开始）。 */
    private final Integer seqNo;

    /** 请求体 schema（仅详情返回）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String requestSchema;

    /** 响应体 schema（仅详情返回）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String responseSchema;

    /** 参数 schema（仅详情返回）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String paramSchema;

    /** schema 是否被截断。 */
    private final boolean schemaTruncated;

    private ApiOperationVO(Long id, String method, String path, String summary, String tags, Integer seqNo,
            String requestSchema, String responseSchema, String paramSchema, boolean schemaTruncated) {
        this.id = id;
        this.method = method;
        this.path = path;
        this.summary = summary;
        this.tags = tags;
        this.seqNo = seqNo;
        this.requestSchema = requestSchema;
        this.responseSchema = responseSchema;
        this.paramSchema = paramSchema;
        this.schemaTruncated = schemaTruncated;
    }

    /**
     * 实体转列表 VO：**不带** schema 三兄弟。
     *
     * @param entity 实体
     * @return VO；入参为 null 时返回 null
     */
    public static ApiOperationVO fromList(ApiOperation entity) {
        if (entity == null) {
            return null;
        }
        return new ApiOperationVO(entity.getId(), entity.getMethod(), entity.getPath(), entity.getSummary(),
                entity.getTags(), entity.getSeqNo(), null, null, null, false);
    }

    /**
     * 实体转详情 VO：带 schema 三兄弟（已由解析器截断）。
     *
     * @param entity 实体
     * @return VO；入参为 null 时返回 null
     */
    public static ApiOperationVO fromDetail(ApiOperation entity) {
        if (entity == null) {
            return null;
        }
        String request = blankToNull(entity.getRequestSchema());
        String response = blankToNull(entity.getResponseSchema());
        String param = blankToNull(entity.getParamSchema());
        boolean truncated = containsMark(request) || containsMark(response) || containsMark(param);
        return new ApiOperationVO(entity.getId(), entity.getMethod(), entity.getPath(), entity.getSummary(),
                entity.getTags(), entity.getSeqNo(), request, response, param, truncated);
    }

    /**
     * 空串转 null（NON_NULL 才能生效，否则会输出 {@code ""}）。
     *
     * @param value 值
     * @return 非空值或 null
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 判断字符串是否带截断标记。
     *
     * @param value 值
     * @return 带标记返回 true
     */
    private static boolean containsMark(String value) {
        return value != null && value.contains(TRUNCATE_MARK);
    }
}
