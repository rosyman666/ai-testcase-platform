package com.aittest.testpoint.service;

import com.aittest.aicore.LlmMessage;
import com.aittest.aicore.LlmRequest;
import com.aittest.aicore.LlmResponse;
import com.aittest.aicore.LlmException;
import com.aittest.aicore.prompt.PromptRenderer;
import com.aittest.aicore.prompt.PromptTemplate;
import com.aittest.aicore.prompt.PromptTemplateService;
import com.aittest.aicore.provider.LoggingLlmProvider;
import com.aittest.aicore.structured.JsonSchemaValidator;
import com.aittest.aicore.structured.StructuredOutputParser;
import com.aittest.common.enums.CaseType;
import com.aittest.common.enums.TaskScene;
import com.aittest.config.AiProperties;
import com.aittest.doc.domain.DocSource;
import com.aittest.doc.mapper.DocSourceMapper;
import com.aittest.testpoint.dto.ExtractUnit;
import com.aittest.testpoint.dto.TestPointDraft;
import com.aittest.testpoint.dto.TestPointExtractReport;
import com.aittest.testpoint.dto.TestPointExtractResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 测试点抽取内核：**一个**待分析单元 → 一批测试点落库。
 *
 * <p>链路只有一条，走完即返回：{@code 渲染模板 → 调模型 → 修复坏 JSON → schema 校验 → 去重落库}。
 * T04 交付的每一件资产在这里第一次被真实使用，本类自己<b>不重写任何一环</b>——
 * 重写就意味着两套修复策略、两套校验口径，迟早不一致。</p>
 *
 * <p><b>三处刻意的"不信模型"</b>：</p>
 * <ol>
 *   <li><b>{@code sourceRef} 用服务端生成的值</b>，模型写的只留在 {@link TestPointDraft} 里供诊断。
 *       它的用途是反查回原文，真源是数据库外键，不能交给一个可能写错的输出。</li>
 *   <li><b>{@code category} 白名单由 {@link CaseType} 枚举现算</b>，不写死在代码里。
 *       枚举加了一个分类而提示词漏了，模型就只能写出被 schema 拒掉的值。</li>
 *   <li><b>schema 校验是硬闸门</b>：不合规直接当失败，不"尽量入库"。
 *       半个合格的测试点进了评审页，人要花时间去分辨哪些可信——比少抽几条更贵。</li>
 * </ol>
 *
 * <p><b>失败不抛异常、返回 {@code failed(...)}</b>（验收 ⑤）：批量抽取时编排层要把 N 个单元的
 * 结果汇总成一份报告，抛异常只能表达"第一个失败"，后面 N-1 个的去向就没了。
 * 真正的程序错误（NPE 之类）照旧抛出，不吞。</p>
 *
 * @author 寇豆码
 */
@Service
public class TestPointExtractService {

    private static final Logger log = LoggerFactory.getLogger(TestPointExtractService.class);

    /** 提示词模板编码（{@code prompts/testpoint_extract.v1.md} 的 front-matter {@code code}）。 */
    private static final String TEMPLATE_CODE = "testpoint_extract";

    /** schema 里 {@code maxItems} 的上限，与 {@code schema/testpoint.schema.json} 保持一致。 */
    private static final int MAX_ITEMS = 30;

    /** 模板查询与渲染。 */
    private final PromptTemplateService promptTemplateService;

    /** 提示词渲染器。 */
    private final PromptRenderer promptRenderer;

    /** 记账装饰器（必须走它，否则抽取不进成本台账）。 */
    private final LoggingLlmProvider loggingLlmProvider;

    /** schema 读取与校验。 */
    private final JsonSchemaValidator jsonSchemaValidator;

    /** 结构化解析。 */
    private final StructuredOutputParser structuredOutputParser;

    /** JSON 转换（把解析出的节点映射成 {@link TestPointDraft}）。 */
    private final ObjectMapper objectMapper;

    /** 模型配置（温度）。 */
    private final AiProperties aiProperties;

    /** 落库与去重。 */
    private final TestPointService testPointService;

    /** 文档主表（取标题与单号）。 */
    private final DocSourceMapper docSourceMapper;

    /** 文档切分（把一篇文档切成 N 个待分析单元，见 {@link ExtractPlanner}）。 */
    private final ExtractPlanner extractPlanner;

    public TestPointExtractService(PromptTemplateService promptTemplateService,
                                   PromptRenderer promptRenderer,
                                   LoggingLlmProvider loggingLlmProvider,
                                   JsonSchemaValidator jsonSchemaValidator,
                                   StructuredOutputParser structuredOutputParser,
                                   ObjectMapper objectMapper,
                                   AiProperties aiProperties,
                                   TestPointService testPointService,
                                   DocSourceMapper docSourceMapper,
                                   ExtractPlanner extractPlanner) {
        this.promptTemplateService = promptTemplateService;
        this.promptRenderer = promptRenderer;
        this.loggingLlmProvider = loggingLlmProvider;
        this.jsonSchemaValidator = jsonSchemaValidator;
        this.structuredOutputParser = structuredOutputParser;
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
        this.testPointService = testPointService;
        this.docSourceMapper = docSourceMapper;
        this.extractPlanner = extractPlanner;
    }

    /**
     * 抽取一个单元的测试点并落库（<b>不</b>做文档级的"取代消失的点"）。
     *
     * <p>单独调用本方法时，只合并本单元的产出。"本轮没再产出的点置 {@code SUPERSEDED}"
     * 是整篇文档抽完之后的判定，由 {@link #extractDocument(Long)} 统一执行——
     * 提前做会把还没处理的单元的产出标掉（见 {@code TestPointService#mergeExtraction}）。</p>
     *
     * @param unit 待分析单元
     * @return 抽取结果（失败时 {@code success=false} 且带中文原因，不抛异常）
     */
    public TestPointExtractResult extract(ExtractUnit unit) {
        return safeExtract(unit).result();
    }

    /**
     * 同 {@link #extract}，但<b>额外返回本单元产出的去重键</b>。
     *
     * <p>异步引擎（T06）需要把这批键持久化到 {@code gen_task_item.kept_hashes}，
     * 因为收尾时的 {@code supersedeMissing} 要拿"本轮产出的全集"当 {@code kept}——
     * 同步路径里它在内存中跨单元累加，异步下 item 各自执行还可能跨进程重启，
     * 只能落库（见 §11.1 D-26）。同步调用方仍用 {@link #extract} 即可。</p>
     *
     * @param unit 待分析单元
     * @return 结果 + 去重键
     */
    public UnitResult extractUnit(ExtractUnit unit) {
        return extractUnit(unit, null, null);
    }

    /**
     * 同 {@link #extractUnit(ExtractUnit)}，但额外把<b>任务上下文</b>透传进成本台账。
     *
     * <p><b>T06 异步链路专用</b>：{@code LlmRequest.taskId / taskItemId} 是"只用于记账"
     * 的字段（见 {@code LlmRequest} 类注释），{@code LoggingLlmProvider} 除了请求对象
     * 什么都看不到。异步引擎手里同时有 {@code task.getId()} 与 {@code item.getId()}，
     * 必须在这里传进来，否则 {@code llm_call_log} 这两列恒 NULL——T11 成本按 task
     * 视角求和、按任务清理台账、T07 逐 item 回写佐证全部失去依据。</p>
     *
     * <p><b>同步路径传 null 合法</b>：{@link #extract(ExtractUnit)} 与
     * {@link #extractDocument(Long)} 没有任务上下文，两个参数留 null 即可；
     * {@code LlmRequest} 的这两个字段本身声明为可空，不改变 T05 的既有语义。</p>
     *
     * <p>{@code attemptNo} 仍按 1 记账（异步重试的真实次数归因留 T07，见本批
     * 收口说明）：改动它需要把 item 的 attempts 再透一层进 {@code ExtractUnit}，
     * 收益低于跨 T04/T05/T06 的改动面。</p>
     *
     * @param unit       待分析单元
     * @param taskId     关联任务 ID（同步路径为 null）
     * @param taskItemId 关联任务子项 ID（同步路径为 null）
     * @return 结果 + 去重键
     */
    public UnitResult extractUnit(ExtractUnit unit, Long taskId, Long taskItemId) {
        UnitOutcome outcome = safeExtract(unit, taskId, taskItemId);
        return new UnitResult(outcome.result(), outcome.hashes());
    }

    /**
     * 取一篇文档的待分析单元定位清单（供异步引擎建 item 用）。
     *
     * <p>T06 把抽取搬进 {@code gen_task} 后，"切成哪几个单元"仍由 {@link ExtractPlanner}
     * 说了算——任务创建只消费它给出的 {@code sourceRef}，<b>不重新实现切分规则</b>，
     * 否则同一个 docId 在"建任务"与"执行"两个时刻可能切出不同的单元。</p>
     *
     * @param docId 文档
     * @return 单元定位（{@code operation:xxx} / {@code section:n}），顺序与规划一致
     * @throws com.aittest.common.BizException 文档不存在
     */
    public java.util.List<String> unitRefsOf(Long docId) {
        DocSource doc = docSourceMapper.selectById(docId);
        if (doc == null) {
            throw com.aittest.common.BizException.notFound("需求文档");
        }
        java.util.List<String> refs = new ArrayList<>();
        for (ExtractUnit unit : extractPlanner.plan(doc)) {
            refs.add(unit.sourceRef());
        }
        return refs;
    }

    /**
     * 一个单元的对外产出：结果 + 去重键（{@code extractUnit} 专用）。
     *
     * @param result     逐单元结果
     * @param keptHashes 本单元产出（含命中已有行）的去重键；失败时是该单元原有点的键（保全用）
     * @author 寇豆码
     */
    public record UnitResult(TestPointExtractResult result, Set<String> keptHashes) {
    }

    /**
     * 抽取一整篇文档：切成 N 个单元逐个抽取，汇总成报告。
     *
     * <p><b>切分规则</b>（验收 ①②）：OpenAPI 文档按 {@code api_operation} 逐个单元——
     * 调用次数必须等于接口数，不能一次全量塞给模型；纯文本文档按 {@code doc_section} 逐个单元。
     * 两者不会同时有内容：{@code doc_type} 决定走哪条。</p>
     *
     * <p><b>失败隔离</b>（验收 ⑤）：单元之间互不牵连，{@link #safeExtract} 已经把
     * 预期内的失败收成 {@code failed(...)}，这里只负责继续下一个。一个接口抽挂了，
     * 整篇文档仍然出结果，报告里单独标出是哪几个。</p>
     *
     * <p><b>取代动作在这里做、只做一次</b>：把全部单元产出的去重键汇成 {@code kept}，
     * 最后交给 {@code supersedeMissing}。这是"一轮抽取"的真正边界。
     * 失败单元的原有点也进 {@code kept}（见 {@code keptHashesOfUnit}）——
     * 取代要求"确认原文里没有了"，而一次超时给不出这个确认。</p>
     *
     * @param docId 文档 ID
     * @return 汇总报告
     * @throws com.aittest.common.BizException 文档不存在
     */
    public TestPointExtractReport extractDocument(Long docId) {
        DocSource doc = docSourceMapper.selectById(docId);
        if (doc == null) {
            throw com.aittest.common.BizException.notFound("需求文档");
        }
        List<ExtractUnit> units = extractPlanner.plan(doc);
        List<TestPointExtractResult> results = new ArrayList<>(units.size());
        Set<String> kept = new HashSet<>();
        for (ExtractUnit unit : units) {
            UnitOutcome outcome = safeExtract(unit);
            kept.addAll(outcome.hashes());
            results.add(outcome.result());
        }
        // 所有单元处理完，才能判定"哪些点本轮没再出现"
        testPointService.supersedeMissing(docId, kept);

        TestPointExtractReport report = TestPointExtractReport.of(doc.getDocNo(), results);
        log.info("[TESTPOINT] 文档抽取完成 docNo={} 单元={} 成功={} 失败={} 新增={}",
                report.docNo(), report.unitCount(), report.successCount(),
                report.failedCount(), report.insertedCount());
        return report;
    }

    /**
     * 单元抽取的<b>安全边界</b>：预期内的失败收成 {@code failed(...)}，不往外抛。
     *
     * <p><b>为什么不抛</b>（验收 ⑤）：批量抽取要把 N 个单元的结果汇总成一份报告，
     * 抛异常只能表达"第一个失败"，后面 N-1 个的去向就没了。曾经这里漏了 catch，
     * 结果是模型返回一次坏 JSON，整篇文档的抽取报告就变成了一个错误码——
     * 前面已经落库的单元白干，用户只看到"重试"，看不出是哪个接口抽挂了。</p>
     *
     * <p><b>失败单元的原有点必须进 {@code kept}</b>：取代的语义是"原文里已经没有这个点了"，
     * 模型超时并不能证明这一点。漏了这一步，一次上游抖动会把该单元的测试点整批静默废掉。</p>
     *
     * @param unit 待分析单元
     * @return 成功 → 结果 + 去重键；失败 → {@code failed(...)} + 该单元原有点的去重键
     */
    private UnitOutcome safeExtract(ExtractUnit unit) {
        return safeExtract(unit, null, null);
    }

    /**
     * 带任务上下文的安全边界：两个 id 一路透到 {@link #doExtract} 的
     * {@code LlmRequest}，失败分支"保留原有点"的逻辑与同步路径完全一致。
     *
     * @param unit       待分析单元
     * @param taskId     关联任务 ID（同步路径为 null）
     * @param taskItemId 关联任务子项 ID（同步路径为 null）
     * @return 成功 → 结果 + 去重键；失败 → {@code failed(...)} + 该单元原有点的去重键
     */
    private UnitOutcome safeExtract(ExtractUnit unit, Long taskId, Long taskItemId) {
        try {
            return doExtract(unit, taskId, taskItemId);
        } catch (LlmException ex) {
            log.warn("[TESTPOINT] 单元抽取失败，保留该单元原有测试点 sourceRef={} 错误码={} 原因={}",
                    unit.sourceRef(), ex.getErrorCode(), ex.getMessage());
            return new UnitOutcome(
                    TestPointExtractResult.failed(unit.sourceRef(), userFacingMessage(ex)),
                    testPointService.keptHashesOfUnit(unit.docId(), unit.operationId(), unit.sectionId()));
        }
    }

    /**
     * 把 {@link LlmException} 翻成面向用户的中文失败原因。
     *
     * <p>与 {@code LlmExceptionHandler} 的口径保持一致：优先用异常自带的消息，
     * 它是各环节写好的中文；为空时退回错误码的默认文案。</p>
     *
     * @param ex 模型链路异常
     * @return 面向用户的中文原因
     */
    private String userFacingMessage(LlmException ex) {
        if (ex.getMessage() != null && !ex.getMessage().isBlank()) {
            return ex.getMessage();
        }
        return ex.getErrorCode().getMessage();
    }

    /**
     * 真正的抽取链路。
     *
     * <p>返回的 {@link UnitOutcome} 同时携带<b>给人看的结果</b>和<b>给取代判定用的去重键</b>：
     * 前者进报告，后者由 {@link #extractDocument(Long)} 汇总后交给
     * {@code supersedeMissing}。两者来自同一次落库，拆开返回就等于让调用方重新猜一遍。</p>
     *
     * @param unit       待分析单元
     * @param taskId     关联任务 ID（只用于记账，同步路径为 null）
     * @param taskItemId 关联任务子项 ID（只用于记账，同步路径为 null）
     * @return 成功结果 + 本单元产出的去重键
     * @throws LlmException 模型/解析/schema 任一环节失败
     */
    private UnitOutcome doExtract(ExtractUnit unit, Long taskId, Long taskItemId) {
        PromptTemplate template = promptTemplateService.activeOf(TEMPLATE_CODE);
        String schemaPath = template.getOutputSchema();

        // 版本号必须在这里记下：模板改版后老点的 promptVersion 不能跟着变（见 TestPoint 类注释）
        String promptVersion = template.getCode() + ".v" + template.getVersion();

        String rendered = promptRenderer.render(template.getContent(), Map.of(
                "doc_title", nullToEmpty(unit.docTitle()),
                "operation_json", unit.content(),
                "categories", allowedCategories(),
                "max_points", String.valueOf(clampMaxPoints(unit.maxPoints()))));

        LlmRequest request = LlmRequest.builder()
                .scene(TaskScene.TESTPOINT_EXTRACT.code())
                // 只用于记账：异步路径带任务/子项 id，同步路径为 null（字段本身可空）
                .taskId(taskId)
                .taskItemId(taskItemId)
                .attemptNo(1)
                .message(LlmMessage.user(rendered))
                .temperature(aiProperties.getTemperature().getTestpoint())
                .jsonSchema(schemaPath == null || schemaPath.isBlank()
                        ? null
                        : jsonSchemaValidator.readSchemaText(schemaPath))
                .build();

        LlmResponse response = loggingLlmProvider.chat(request);

        StructuredOutputParser.ParseResult parsed =
                structuredOutputParser.parseArrayWithMeta(response.getContent(), schemaPath);
        List<TestPointDraft> drafts = toDrafts(parsed.items());

        TestPointService.MergeOutcome merged = testPointService.mergeExtraction(unit.docId(), drafts,
                unit.operationId(), unit.sectionId(), unit.sourceRef(), promptVersion);

        TestPointExtractResult result = new TestPointExtractResult(unit.sourceRef(), true,
                drafts.size(), merged.inserted(), parsed.repaired(), parsed.truncated(), null);
        return new UnitOutcome(result, merged.keptHashes());
    }

    /**
     * 一个单元的抽取产出：结果（进报告）+ 去重键（供文档级取代判定汇总）。
     *
     * @param result 逐单元结果
     * @param hashes 本单元产出（含命中已有行）的去重键
     * @author 寇豆码
     */
    private record UnitOutcome(TestPointExtractResult result, Set<String> hashes) {
    }

    /**
     * 把解析出的 JSON 数组映射成 draft 列表。
     *
     * <p><b>映射失败被当成程序错误而不是"这一条抽取失败"</b>：走到这里时节点**已经通过**
     * {@code schema/testpoint.schema.json} 校验，再映射不上只有一种可能——
     * {@link TestPointDraft} 的字段与 schema 漂移了（有人改了其中一个没改另一个）。
     * 这是代码缺陷，必须炸出来让人去修；静默当成"这次没抽到"会让 schema 变得形同虚设。</p>
     *
     * @param items 已通过 schema 校验的数组
     * @return draft 列表
     * @throws IllegalStateException {@code TestPointDraft} 与 schema 字段漂移
     */
    private List<TestPointDraft> toDrafts(com.fasterxml.jackson.databind.node.ArrayNode items) {
        List<TestPointDraft> drafts = new ArrayList<>(items.size());
        for (JsonNode node : items) {
            try {
                drafts.add(objectMapper.treeToValue(node, TestPointDraft.class));
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException(
                        "TestPointDraft 与 schema/testpoint.schema.json 字段漂移，无法把模型输出映射成测试点："
                                + ex.getOriginalMessage(), ex);
            }
        }
        return drafts;
    }

    /**
     * 允许的分类串（提示词的 {@code {{categories}}}）。
     *
     * <p>从 {@link CaseType} 枚举现算：枚举是分类的单一真源（§11.1 D-14），
     * 这里写死一份就等于又造了一个真源，两边漂移时模型会被引导去写 schema 拒收的值。</p>
     *
     * @return 以 {@code /} 连接的分类编码
     */
    private String allowedCategories() {
        StringBuilder out = new StringBuilder();
        for (CaseType type : CaseType.values()) {
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(type.code());
        }
        return out.toString();
    }

    /**
     * 把 {@code maxPoints} 夹到 schema 允许的范围内。
     *
     * @param maxPoints 期望条数
     * @return [1, 30] 之间的条数
     */
    private int clampMaxPoints(int maxPoints) {
        return Math.max(1, Math.min(maxPoints, MAX_ITEMS));
    }

    /**
     * 空值转空串（提示词变量不允许 null，缺变量会被 PromptRenderer 抛 40001）。
     *
     * @param value 原值
     * @return 非 null 字符串
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
