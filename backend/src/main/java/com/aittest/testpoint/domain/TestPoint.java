package com.aittest.testpoint.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 测试点（{@code test_point}）：结构化上下文的**终点**，用例生成的**原点**。
 *
 * <p><b>这张表是"AI 产出"与"人工产出"的共同落点</b>，不是 AI 专用表。手补的测试点和模型抽出来的
 * 走同一套字段、同一套状态机、同一张评审页——否则前端要写两套列表/编辑/启停逻辑，
 * 而且"这条点是 AI 抽的还是人写的"会变成一个统计口径永远算不清的问题。</p>
 *
 * <p><b>三条改代码前必须知道的约束</b>：</p>
 * <ol>
 *   <li>{@code content_hash} 是<b>幂等去重键</b>（唯一索引 {@code uk_test_point_doc_hash}），
 *       取值见 {@link TestPointHasher}。同一文档重复抽取时靠它保证"数量不翻倍"；
 *       <b>不要</b>在 hash 里掺 description——描述会被人改，掺进去就会把人工修订的点判成新点重复插入。</li>
 *   <li>{@code operation_id} 与 {@code section_id} 是<b>二选一</b>的来源定位（OpenAPI 文档取前者，
 *       纯文本文档取后者），DDL 里都是 {@code SET NULL} 的外键：来源文档被物理删掉时，
 *       测试点本身<b>不跟着删</b>，只失去反查能力。这是刻意的——用例已经引用了这些测试点。</li>
 *   <li>{@code promptVersion} 记录<b>抽取时用的模板版本</b>，不是当前生效版本。
 *       模板改版后老点的这个字段<b>不能</b>跟着变，否则"这批点质量差是模板几版本的问题"就无从回溯。</li>
 * </ol>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString
@TableName("test_point")
public class TestPoint {

    /** 主键（雪花 ID，Jackson 全局序列化成字符串）。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属文档，关联 {@code doc_source.id}。 */
    private Long docId;

    /** 来源接口（OpenAPI 文档），关联 {@code api_operation.id}；纯文本文档为 {@code null}。 */
    private Long operationId;

    /** 来源章节（纯文本文档），关联 {@code doc_section.id}；OpenAPI 文档为 {@code null}。 */
    private Long sectionId;

    /** 测试点标题（≤500 字符，超长由解析器前置拦截，不指望数据库报 1406）。 */
    private String title;

    /**
     * 分类编码，取值见 {@code common.enums.CaseType}：
     * FUNCTIONAL/BOUNDARY/EXCEPTION/SECURITY/PERFORMANCE/COMPATIBILITY。
     *
     * <p>与测试用例共用同一个枚举，理由同 §11.1 D-14：分类是一个横切概念，
     * 拆成两套枚举后"测试点的 BOUNDARY"和"用例的 BOUNDARY"迟早不是一回事。</p>
     */
    private String category;

    /** 风险等级编码，取值见 {@code common.enums.RiskLevel}（HIGH/MEDIUM/LOW）。 */
    private String riskLevel;

    /** 测试点描述（要验证什么、为什么值得验证）。人可以改，因此<b>不进</b> content_hash。 */
    private String description;

    /**
     * 来源定位：可反查回 {@code METHOD /path}（OpenAPI）或章节标题（纯文本）。
     *
     * <p>是<b>给人看的</b>冗余文本，不是外键——外键是 {@code operationId}/{@code sectionId}。
     * 两者都留是因为反查场景不同：程序关联走外键，评审页/导出里的"这条点来自哪"走文本。</p>
     */
    private String sourceRef;

    /** 状态编码，取值见 {@code common.enums.TestPointStatus}（ACTIVE/DISABLED/SUPERSEDED）。 */
    private String status;

    /** 抽取时使用的提示词版本（如 {@code testpoint_extract.v1}），便于回溯模板质量。 */
    private String promptVersion;

    /** 幂等去重键：{@code sha256(doc_id + title + category)}，见 {@link TestPointHasher}。 */
    private String contentHash;

    /** 创建时间（UTC）。 */
    private java.time.LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private java.time.LocalDateTime updatedAt;
}
