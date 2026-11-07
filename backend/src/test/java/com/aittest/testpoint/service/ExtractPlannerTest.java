package com.aittest.testpoint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aittest.doc.domain.ApiOperation;
import com.aittest.doc.domain.DocSection;
import com.aittest.doc.domain.DocSource;
import com.aittest.doc.mapper.ApiOperationMapper;
import com.aittest.doc.mapper.DocSectionMapper;
import com.aittest.testpoint.dto.ExtractUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 文档切分规则的回归测试（验收 ①②）。
 *
 * <p><b>本类要挡住的具体事故</b>：把整篇文档一次性塞给模型（调用次数从 N 变成 1）、
 * 接口和章节混着切（成本估算失去意义）、来源定位写成无法反查的形式。
 * 这些都不会报错，只会让成本翻倍、或者让"这条点来自哪"永远查不回去。</p>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class ExtractPlannerTest {

    /** 所属文档。 */
    private static final Long DOC = 500L;

    @Mock
    private ApiOperationMapper apiOperationMapper;

    @Mock
    private DocSectionMapper docSectionMapper;

    @InjectMocks
    private ExtractPlanner planner;

    /**
     * 构造一个接口。
     *
     * @param id          主键
     * @param method      方法
     * @param path        路径
     * @param operationId operationId（可空）
     * @return 接口实体
     */
    private ApiOperation operation(long id, String method, String path, String operationId) {
        ApiOperation op = new ApiOperation();
        op.setId(id);
        op.setDocId(DOC);
        op.setMethod(method);
        op.setPath(path);
        op.setOperationId(operationId);
        op.setSummary("创建用户");
        op.setTags("用户管理");
        op.setParamSchema("{\"name\":\"path\"}");
        op.setRequestSchema("{\"type\":\"object\"}");
        op.setResponseSchema("{\"type\":\"object\"}");
        return op;
    }

    /**
     * 构造一个章节。
     *
     * @param seqNo   序号
     * @param heading 标题
     * @param content 正文
     * @return 章节实体
     */
    private DocSection section(int seqNo, String heading, String content) {
        DocSection sec = new DocSection();
        sec.setId((long) seqNo);
        sec.setDocId(DOC);
        sec.setSeqNo(seqNo);
        sec.setLevel(2);
        sec.setHeading(heading);
        sec.setContent(content);
        return sec;
    }

    /**
     * 构造文档。
     *
     * @return 文档实体
     */
    private DocSource doc() {
        DocSource source = new DocSource();
        source.setId(DOC);
        source.setName("用户中心接口文档");
        source.setDocNo("D20261107-000001");
        return source;
    }

    @Test
    @DisplayName("① OpenAPI 按 operation 逐个切：3 个接口 = 3 个单元（不是 1 次全量塞）")
    void splitsByOperation() {
        when(apiOperationMapper.selectList(any())).thenReturn(List.of(
                operation(1L, "POST", "/api/users", "createUser"),
                operation(2L, "GET", "/api/users/{id}", "getUser"),
                operation(3L, "DELETE", "/api/users/{id}", "")));

        List<ExtractUnit> units = planner.plan(doc());

        assertThat(units).hasSize(3);
        assertThat(units).extracting(ExtractUnit::sourceRef)
                .containsExactly("operation:createUser", "operation:getUser", "operation:DELETE /api/users/{id}");
        // 一个单元 = 一个接口，且带上对应的 operationId，供落库时写外键
        assertThat(units).extracting(ExtractUnit::operationId).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("② 纯文本文档按 section 切，来源写 section:序号")
    void splitsBySection() {
        when(apiOperationMapper.selectList(any())).thenReturn(List.of());
        when(docSectionMapper.selectList(any())).thenReturn(List.of(
                section(1, "登录流程", "正文一"),
                section(2, "注册流程", "正文二")));

        List<ExtractUnit> units = planner.plan(doc());

        assertThat(units).hasSize(2);
        assertThat(units).extracting(ExtractUnit::sourceRef)
                .containsExactly("section:1", "section:2");
        assertThat(units).extracting(ExtractUnit::sectionId).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("接口与章节不混合：两类都有时只走接口，否则调用次数失去意义")
    void doesNotMixOperationsAndSections() {
        when(apiOperationMapper.selectList(any())).thenReturn(List.of(
                operation(1L, "POST", "/api/users", "createUser")));
        // 刻意不 stub docSectionMapper：走到那里就说明切分规则被改坏了。
        // 下面的 verify(never()) 是本条测试的断言本体

        List<ExtractUnit> units = planner.plan(doc());

        assertThat(units).hasSize(1);
        assertThat(units.get(0).sourceRef()).isEqualTo("operation:createUser");
        verify(docSectionMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("两者都没有时返回空列表，不报错也不造一个空单元")
    void returnsEmptyWhenNothingToSplit() {
        when(apiOperationMapper.selectList(any())).thenReturn(List.of());
        when(docSectionMapper.selectList(any())).thenReturn(List.of());

        assertThat(planner.plan(doc())).isEmpty();
    }

    @Test
    @DisplayName("接口输入包含方法、路径与三个 schema 片段；空字段不占行")
    void buildsOperationText() {
        ApiOperation op = operation(1L, "POST", "/api/users", "createUser");
        op.setTags("");      // 空标签不该出现一行 "标签:"

        String text = planner.operationText(op);

        assertThat(text).startsWith("POST /api/users\n");
        assertThat(text).contains("摘要: 创建用户");
        assertThat(text).contains("参数: {\"name\":\"path\"}");
        assertThat(text).contains("请求体: {\"type\":\"object\"}");
        assertThat(text).contains("响应体: {\"type\":\"object\"}");
        assertThat(text).doesNotContain("标签:");
    }

    @Test
    @DisplayName("章节输入包含标题、层级、序号与正文，模型能据此定位到原文")
    void buildsSectionText() {
        String text = planner.sectionText(section(3, "登录流程", "用户输入账号密码"));

        assertThat(text).contains("章节标题: 登录流程");
        assertThat(text).contains("层级: L2");
        assertThat(text).contains("序号: 3");
        assertThat(text).contains("用户输入账号密码");
    }
}
