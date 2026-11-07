package com.aittest.testpoint.service;

import com.aittest.doc.domain.ApiOperation;
import com.aittest.doc.domain.DocSection;
import com.aittest.doc.domain.DocSource;
import com.aittest.doc.mapper.ApiOperationMapper;
import com.aittest.doc.mapper.DocSectionMapper;
import com.aittest.testpoint.dto.ExtractUnit;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 文档切分器：把一篇文档切成 N 个待分析单元。
 *
 * <p><b>为什么单独立一个类</b>：切分规则本身有需要被守住的约束（验收 ①②），
 * 而 {@code TestPointExtractService} 已经带了 11 个依赖——把它塞进去，
 * 单测就得先构造整条 AI 链路才能验"一个接口是不是一个单元"。职责分开后，
 * 这条规则可以用三个 mapper mock 直接测。</p>
 *
 * <p><b>切分规则</b>：</p>
 * <ul>
 *   <li><b>OpenAPI 文档</b>：按 {@code api_operation} 逐个单元。<b>调用次数 = 接口数</b>
 *       （验收 ①），不能一次全量塞给模型——理由见 {@link ExtractUnit} 类注释。</li>
 *   <li><b>纯文本文档</b>：按 {@code doc_section} 逐个单元（验收 ②）。</li>
 *   <li><b>两者不混合</b>：一篇文档的 {@code doc_type} 是单一的，混合切分会让
 *       "调用次数"失去意义，而成本估算直接依赖这个数。</li>
 * </ul>
 *
 * @author 寇豆码
 */
@Component
public class ExtractPlanner {

    /** 每个待分析单元默认最多抽取的测试点数（提示词的 {@code {{max_points}}}）。 */
    static final int DEFAULT_MAX_POINTS = 10;

    /** 接口操作表（OpenAPI 文档的切分单位）。 */
    private final ApiOperationMapper apiOperationMapper;

    /** 章节表（纯文本文档的切分单位）。 */
    private final DocSectionMapper docSectionMapper;

    public ExtractPlanner(ApiOperationMapper apiOperationMapper,
                          DocSectionMapper docSectionMapper) {
        this.apiOperationMapper = apiOperationMapper;
        this.docSectionMapper = docSectionMapper;
    }

    /**
     * 切分一篇文档。
     *
     * @param doc 文档
     * @return 单元列表（两者都没有时为空列表）
     */
    public List<ExtractUnit> plan(DocSource doc) {
        List<ExtractUnit> units = new ArrayList<>();

        List<ApiOperation> operations = apiOperationMapper.selectList(
                new LambdaQueryWrapper<ApiOperation>()
                        .eq(ApiOperation::getDocId, doc.getId())
                        .orderByAsc(ApiOperation::getId));
        if (!operations.isEmpty()) {
            for (ApiOperation op : operations) {
                units.add(new ExtractUnit(doc.getId(), op.getId(), null,
                        operationRef(op), doc.getName(), operationText(op), DEFAULT_MAX_POINTS));
            }
            return units;
        }

        List<DocSection> sections = docSectionMapper.selectList(
                new LambdaQueryWrapper<DocSection>()
                        .eq(DocSection::getDocId, doc.getId())
                        .orderByAsc(DocSection::getSeqNo));
        for (DocSection section : sections) {
            units.add(new ExtractUnit(doc.getId(), null, section.getId(),
                    "section:" + section.getSeqNo(), doc.getName(), sectionText(section),
                    DEFAULT_MAX_POINTS));
        }
        return units;
    }

    /**
     * 接口的权威来源定位。
     *
     * <p>优先用 {@code operationId}（OpenAPI 里它是稳定的机器标识），为空时退到
     * {@code METHOD /path}。两者都能反查回 {@code api_operation} 行。</p>
     *
     * @param op 接口
     * @return 来源定位
     */
    String operationRef(ApiOperation op) {
        String operationId = op.getOperationId();
        return (operationId == null || operationId.isBlank())
                ? "operation:" + op.getMethod() + " " + op.getPath()
                : "operation:" + operationId;
    }

    /**
     * 把接口拼成模型输入。
     *
     * <p>三个 schema 片段（参数/请求体/响应体）是<b>已裁剪的 JSON 片段</b>，由 T03 的
     * {@code OpenApiParser} 生成。裁剪过的意义在这里体现：模型只看到与本接口相关的字段，
     * 不会被整篇 OpenAPI 的其它部分稀释注意力。</p>
     *
     * @param op 接口
     * @return 模型输入文本
     */
    String operationText(ApiOperation op) {
        StringBuilder out = new StringBuilder();
        out.append(op.getMethod()).append(' ').append(op.getPath()).append('\n');
        appendField(out, "摘要", op.getSummary());
        appendField(out, "标签", op.getTags());
        appendField(out, "参数", op.getParamSchema());
        appendField(out, "请求体", op.getRequestSchema());
        appendField(out, "响应体", op.getResponseSchema());
        return out.toString();
    }

    /**
     * 把章节拼成模型输入。
     *
     * @param section 章节
     * @return 模型输入文本
     */
    String sectionText(DocSection section) {
        StringBuilder out = new StringBuilder();
        appendField(out, "章节标题", section.getHeading());
        out.append("层级: L").append(section.getLevel()).append('\n');
        out.append("序号: ").append(section.getSeqNo()).append('\n');
        out.append("正文:\n").append(section.getContent() == null ? "" : section.getContent());
        return out.toString();
    }

    /**
     * 追加一个字段行（值为空则跳过，避免给模型一堆 {@code null} 噪声）。
     *
     * @param out   目标缓冲
     * @param label 中文标签
     * @param value 字段值
     */
    private void appendField(StringBuilder out, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        out.append(label).append(": ").append(value.trim()).append('\n');
    }
}
