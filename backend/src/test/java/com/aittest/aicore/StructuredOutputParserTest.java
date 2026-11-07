package com.aittest.aicore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aittest.aicore.structured.JsonRepairer;
import com.aittest.aicore.structured.JsonSchemaValidator;
import com.aittest.aicore.structured.StructuredOutputParser;
import com.aittest.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 结构化输出解析的回归测试（T04 ③④）。
 *
 * <p>纯单元测试：不启动 Spring、不连库、不联网，所以 CI 上必跑，也必须是<b>确定</b>的。
 * 期望值都写死，不用"非空/大于 0"这类弱断言——弱断言在"字段整体错位"时照样绿，
 * 而那正是这类解析器最容易犯的错。</p>
 *
 * @author 寇豆码
 */
class StructuredOutputParserTest {

    /** schema 路径（随 jar 发布，测试里同样从 classpath 读）。 */
    private static final String CASE_SCHEMA = "classpath:schema/testcase.schema.json";

    /** 被测对象：解析器只依赖一个无状态的校验器，直接 new 即可。 */
    private final StructuredOutputParser parser = new StructuredOutputParser(new JsonSchemaValidator());

    /** 一条合法用例（各坏输出用例都从它派生，保证"只有坏在那一处"）。 */
    private static final String VALID = """
            [{"title":"name 为 64 字符时创建成功","precondition":"无",
              "steps":["构造 64 字符 name","调用接口"],"expected":"返回 code=0 且新增 1 行",
              "priority":"P1","caseType":"BOUNDARY"},
             {"title":"name 为 65 字符时返回 40004","precondition":"无",
              "steps":["构造 65 字符 name","调用接口"],"expected":"返回 code=40004",
              "priority":"P1","caseType":"BOUNDARY"}]
            """;

    @Test
    @DisplayName("基线：合法裸数组可解析出 2 条")
    void parsesPlainArray() {
        StructuredOutputParser.ParseResult result = parser.parseArrayWithMeta(VALID, CASE_SCHEMA);
        assertThat(result.items()).hasSize(2);
        assertThat(result.repaired()).isFalse();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("坏输出①：markdown 围栏 + 前后解说文字")
    void repairsMarkdownFence() {
        String raw = "下面是生成结果：\n```json\n" + VALID + "\n```\n以上，希望对你有帮助。";
        StructuredOutputParser.ParseResult result = parser.parseArrayWithMeta(raw, CASE_SCHEMA);
        assertThat(result.items()).hasSize(2);
        // 修复过 → repaired 必须为真，否则"首次通过率"会把修复救回的也算成一次成功
        assertThat(result.repaired()).isTrue();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("坏输出②：尾逗号")
    void repairsTrailingComma() {
        String raw = VALID.replace("}]\n", "},]\n");
        StructuredOutputParser.ParseResult result = parser.parseArrayWithMeta(raw, CASE_SCHEMA);
        assertThat(result.items()).hasSize(2);
        assertThat(result.repaired()).isTrue();
    }

    @Test
    @DisplayName("坏输出③：单引号 key 与单引号字符串")
    void repairsSingleQuotes() {
        String raw = VALID.replace('"', '\'');
        StructuredOutputParser.ParseResult result = parser.parseArrayWithMeta(raw, CASE_SCHEMA);
        assertThat(result.items()).hasSize(2);
        assertThat(result.repaired()).isTrue();
    }

    @Test
    @DisplayName("坏输出④：被截断到最后一个元素中间 → 退到最后一个完整元素")
    void repairsTruncated() {
        // 故意在第二条中间截断：只保留第一条 + 半条
        int cut = VALID.indexOf("{\"title\":\"name 为 65") + 20;
        String raw = VALID.substring(0, cut);
        StructuredOutputParser.ParseResult result = parser.parseArrayWithMeta(raw, CASE_SCHEMA);
        assertThat(result.items()).hasSize(1);
        assertThat(result.truncated()).isTrue();
        // 补全后的元素必须是**完整的**第一条，而不是被截一半
        assertThat(result.items().get(0).path("title").asText()).isEqualTo("name 为 64 字符时创建成功");
    }

    @Test
    @DisplayName("完整 JSON 不得被'截断补全'误伤（尾字段丢失的回归）")
    void doesNotTruncateBalancedJson() {
        String raw = "{\"category\":\"TIMEOUT\",\"confidence\":0.8,\"reason\":\"读取超时\"}";
        JsonRepairer.RepairOutcome outcome = JsonRepairer.repairWithMeta(raw);
        assertThat(outcome.truncated()).isFalse();
        assertThat(outcome.text()).contains("\"reason\"");
        assertThat(outcome.text()).isEqualTo(raw);
    }

    @Test
    @DisplayName("括号配对必须字符串感知：值里含 } 不能提前收尾")
    void pairsBracketsAwareOfStrings() {
        String raw = "{\"cases\":[{\"title\":\"表达式 a}b 的处理\",\"precondition\":\"无\","
                + "\"steps\":[\"传入 a}b\"],\"expected\":\"不报错\",\"priority\":\"P2\","
                + "\"caseType\":\"FUNCTIONAL\"}]}";
        StructuredOutputParser.ParseResult result = parser.parseArrayWithMeta(raw, CASE_SCHEMA);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).path("title").asText()).contains("a}b");
    }

    @Test
    @DisplayName("④ schema 不合规：抛 E_SCHEMA_INVALID 且带字段路径")
    void reportsSchemaPath() {
        String raw = VALID.replaceFirst("\"priority\":\"P1\"", "\"priority\":\"高\"");
        assertThatThrownBy(() -> parser.parseArray(raw, CASE_SCHEMA))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("[0].priority")
                .satisfies(ex -> assertThat(((LlmException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_SCHEMA_INVALID));
    }

    @Test
    @DisplayName("外层包一层对象也能解包（response_format=json_object 的产物）")
    void unwrapsObjectWrapper() {
        String raw = "{\"cases\":" + VALID + "}";
        StructuredOutputParser.ParseResult result = parser.parseArrayWithMeta(raw, CASE_SCHEMA);
        assertThat(result.items()).hasSize(2);
    }

    @Test
    @DisplayName("完全无法解析：抛 E_LLM_PARSE_EXHAUSTED（可重试），与 schema 错误分开")
    void failsOnGarbage() {
        assertThatThrownBy(() -> parser.parseToNode("我觉得这个接口没什么问题"))
                .isInstanceOf(LlmException.class)
                .satisfies(ex -> {
                    LlmException llm = (LlmException) ex;
                    assertThat(llm.getErrorCode()).isEqualTo(ErrorCode.E_LLM_PARSE_EXHAUSTED);
                    assertThat(llm.isRetryable()).isTrue();
                });
    }

    @Test
    @DisplayName("空输出不算解析成功，要给可重试的解析失败")
    void failsOnEmpty() {
        assertThatThrownBy(() -> parser.parseToNode("   "))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("未返回任何内容");
    }

    @Test
    @DisplayName("修复器不能改动字符串内部内容（正则替换的经典误伤）")
    void normalizeKeepsStringContent() {
        JsonNode node = parser.parseToNode("{\"note\":\"含 ,] 与 \\\" 的值\"}");
        assertThat(node.path("note").asText()).isEqualTo("含 ,] 与 \" 的值");
    }
}
