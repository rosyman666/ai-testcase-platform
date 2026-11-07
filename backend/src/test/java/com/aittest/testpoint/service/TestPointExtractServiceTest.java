package com.aittest.testpoint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aittest.aicore.LlmException;
import com.aittest.aicore.LlmRequest;
import com.aittest.aicore.LlmResponse;
import com.aittest.aicore.prompt.PromptRenderer;
import com.aittest.aicore.prompt.PromptTemplate;
import com.aittest.aicore.prompt.PromptTemplateService;
import com.aittest.aicore.provider.LoggingLlmProvider;
import com.aittest.aicore.structured.JsonSchemaValidator;
import com.aittest.aicore.structured.StructuredOutputParser;
import com.aittest.common.ErrorCode;
import com.aittest.config.AiProperties;
import com.aittest.doc.domain.DocSource;
import com.aittest.doc.mapper.DocSourceMapper;
import com.aittest.testpoint.dto.ExtractUnit;
import com.aittest.testpoint.dto.TestPointExtractReport;
import com.aittest.testpoint.dto.TestPointExtractResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 抽取内核的<b>失败隔离</b>回归测试（验收 ⑤）。
 *
 * <p><b>为什么单独立一个类锁这件事</b>：失败隔离曾经被一次编辑悄悄弄丢过——
 * 类注释写着"失败返回 {@code failed(...)} 不抛异常"，可代码里的 catch 没了。
 * 结果是模型返回一次坏 JSON，整篇文档的抽取就变成一个错误码抛给前端：
 * 前面已经落库的单元白干，用户只看到"请重试"，看不出是哪个接口抽挂了。
 * 端到端验收要起服务连库，容易被跳过；这类"契约在注释里、实现在代码里"的约定，
 * 必须有跑得快、跑得勤的单元测试盯着。</p>
 *
 * <p>本类只关心三条契约，不重复测去重逻辑（那是 {@code TestPointServiceTest} 的事）：</p>
 * <ol>
 *   <li>模型链路抛 {@link LlmException} 时，{@code extract()} 返回 {@code failed(...)} 而不是抛出去；</li>
 *   <li>一个单元抽挂了，同一篇文档的其它单元照常出结果（顺序不变）；</li>
 *   <li>抽挂的单元，它已有的测试点必须躲过本轮取代——超时不等于"原文里没有了"。</li>
 * </ol>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TestPointExtractServiceTest {

    private static final Long DOC = 800L;

    @Mock
    private PromptTemplateService promptTemplateService;
    @Mock
    private PromptRenderer promptRenderer;
    @Mock
    private LoggingLlmProvider loggingLlmProvider;
    @Mock
    private JsonSchemaValidator jsonSchemaValidator;
    @Mock
    private StructuredOutputParser structuredOutputParser;
    @Mock
    private TestPointService testPointService;
    @Mock
    private DocSourceMapper docSourceMapper;
    @Mock
    private ExtractPlanner extractPlanner;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TestPointExtractService service;

    @BeforeEach
    void setUp() {
        service = new TestPointExtractService(promptTemplateService, promptRenderer,
                loggingLlmProvider, jsonSchemaValidator, structuredOutputParser,
                objectMapper, new AiProperties(), testPointService,
                docSourceMapper, extractPlanner);

        PromptTemplate template = new PromptTemplate();
        template.setCode("testpoint_extract");
        template.setVersion(1);
        template.setContent("{{doc_title}}|{{operation_json}}|{{categories}}|{{max_points}}");
        template.setOutputSchema("schema/testpoint.schema.json");
        when(promptTemplateService.activeOf("testpoint_extract")).thenReturn(template);
        when(promptRenderer.render(anyString(), any())).thenReturn("rendered");
        when(jsonSchemaValidator.readSchemaText(anyString())).thenReturn("{}");
    }

    /**
     * 构造一个待分析单元。
     *
     * @param ref        来源定位
     * @param operationId 来源接口
     * @return 单元
     */
    private ExtractUnit unit(String ref, Long operationId) {
        return new ExtractUnit(DOC, operationId, null, ref, "用户中心", "{}", 10);
    }

    /** 一个解析成功的空数组结果（走通链路即可，本类不测内容）。 */
    private void stubEmptySuccess() {
        ArrayNode empty = objectMapper.createArrayNode();
        when(loggingLlmProvider.chat(any(LlmRequest.class)))
                .thenReturn(LlmResponse.builder().content("[]").build());
        when(structuredOutputParser.parseArrayWithMeta(anyString(), any()))
                .thenReturn(new StructuredOutputParser.ParseResult(empty, false, false));
        when(testPointService.mergeExtraction(anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(new TestPointService.MergeOutcome(Set.of(), 0));
    }

    @Test
    @DisplayName("⑤ 模型链路抛异常时返回 failed(...)，不许抛出去（否则整篇文档只剩一个错误码）")
    void convertsLlmExceptionToFailedResult() {
        when(loggingLlmProvider.chat(any(LlmRequest.class)))
                .thenThrow(new LlmException(ErrorCode.E_LLM_PARSE_EXHAUSTED, "模型输出无法解析为 JSON", true));

        assertThatCode(() -> {
            TestPointExtractResult result = service.extract(unit("operation:createUser", 1L));
            assertThat(result.success()).isFalse();
            assertThat(result.sourceRef()).isEqualTo("operation:createUser");
            assertThat(result.error()).isEqualTo("模型输出无法解析为 JSON");
            assertThat(result.insertedCount()).isZero();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⑤ 一个单元抽挂，同篇文档其它单元照常出结果，顺序与单元顺序一致")
    void isolatesUnitFailureFromSiblings() {
        DocSource doc = new DocSource();
        doc.setId(DOC);
        doc.setDocNo("REQ-001");
        when(docSourceMapper.selectById(DOC)).thenReturn(doc);
        when(extractPlanner.plan(doc)).thenReturn(List.of(
                unit("operation:createUser", 1L),
                unit("operation:getUser", 2L),
                unit("operation:listUsers", 3L)));

        stubEmptySuccess();
        // 第二个单元抛，第一、三个照常成功
        when(loggingLlmProvider.chat(any(LlmRequest.class)))
                .thenReturn(LlmResponse.builder().content("[]").build())
                .thenThrow(new LlmException(ErrorCode.E_LLM_UPSTREAM, "上游模型超时", true))
                .thenReturn(LlmResponse.builder().content("[]").build());
        when(testPointService.keptHashesOfUnit(DOC, 2L, null)).thenReturn(Set.of("hash-of-getUser"));

        TestPointExtractReport report = service.extractDocument(DOC);

        assertThat(report.unitCount()).isEqualTo(3);
        assertThat(report.successCount()).isEqualTo(2);
        assertThat(report.failedCount()).isEqualTo(1);
        // 顺序必须与单元顺序一致，前端按这个顺序渲染
        assertThat(report.results()).extracting(TestPointExtractResult::sourceRef)
                .containsExactly("operation:createUser", "operation:getUser", "operation:listUsers");
        assertThat(report.results().get(1).success()).isFalse();
        assertThat(report.results().get(1).error()).isEqualTo("上游模型超时");
    }

    @Test
    @DisplayName("⑤ 抽挂的单元，它已有的测试点必须躲过本轮取代：超时 ≠ 原文里没有了")
    void keepsFailedUnitPointsFromBeingSuperseded() {
        DocSource doc = new DocSource();
        doc.setId(DOC);
        doc.setDocNo("REQ-002");
        when(docSourceMapper.selectById(DOC)).thenReturn(doc);
        when(extractPlanner.plan(doc)).thenReturn(List.of(unit("operation:getUser", 2L)));

        when(loggingLlmProvider.chat(any(LlmRequest.class)))
                .thenThrow(new LlmException(ErrorCode.E_LLM_UPSTREAM, "上游模型超时", true));
        when(testPointService.keptHashesOfUnit(DOC, 2L, null)).thenReturn(Set.of("a", "b"));

        service.extractDocument(DOC);

        // 关键断言：取代判定拿到的 kept 必须包含失败单元的原有点
        verify(testPointService).supersedeMissing(org.mockito.ArgumentMatchers.eq(DOC),
                org.mockito.ArgumentMatchers.eq(Set.of("a", "b")));
        // 失败的单元不会去落库
        verify(testPointService, never()).mergeExtraction(anyLong(), any(), any(), any(), any(), any());
    }
}
