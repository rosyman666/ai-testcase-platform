package com.aittest.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.aittest.config.JacksonConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 分页出参的序列化契约：<b>计数是 number、ID 是 string</b>。
 *
 * <p><b>本类要挡住的具体事故</b>：</p>
 * <ul>
 *   <li><b>同一个分页体里 {@code "total":"1"} 而 {@code "totalPages":1}</b>：前者被
 *       {@code JacksonConfig} 的 {@code Long → String} 全局规则命中，后者是 {@code int}
 *       逃过一劫。前端共享类型声明 {@code total: number}，五个列表页
 *       {@code data?.total ?? 0} 直接塞给 {@code TablePagination.count}，
 *       靠 JS 隐式转换"看起来能用"，一旦有人 {@code .toFixed()} 或 {@code === 0}
 *       就静默错——D-58 同一族：类型在说谎、运行靠 coercion；</li>
 *   <li><b>为了把 {@code total} 变成数字而拆掉全局 {@code ToStringSerializer}</b>：
 *       雪花 ID（{@code 2119378105463029765}）超过 JS {@code Number.MAX_SAFE_INTEGER}，
 *       变成 number 后浏览器会静默改写末几位，前端按 id 去查/去 PATCH 会打到不存在的行。
 *       所以第二条断言（{@code records[0].id} 必须是字符串）和第一条同等重要，
 *       删掉它等于给下一个人递了把拆全局配置的刀。</li>
 * </ul>
 *
 * @author 寇豆码
 */
class PageResultTest {

    /** 走真实 {@link JacksonConfig} 的 ObjectMapper（不自己 new 一个干净的）。 */
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().jacksonCustomizer().customize(builder);
        mapper = builder.build();
    }

    @Test
    @DisplayName("total 是 JSON number、同一响应里的 id 仍是 JSON string（计数与标识的边界）")
    void serializesTotalAsNumberAndIdAsString() throws Exception {
        PageResult<Row> result = PageResult.of(List.of(new Row(2119378105463029765L, "回归批次")), 1L, 1, 5);

        JsonNode node = mapper.readTree(mapper.writeValueAsString(result));

        assertThat(node.get("total").isNumber()).as("total 必须是 number，不能是 \"1\"").isTrue();
        assertThat(node.get("total").asLong()).isEqualTo(1L);
        assertThat(node.get("totalPages").isNumber()).isTrue();
        assertThat(node.get("page").isNumber()).isTrue();
        assertThat(node.get("size").isNumber()).isTrue();
        assertThat(node.get("hasNext").isBoolean()).isTrue();
        // 关键的反向断言：全局 Long→String 不能被拆掉
        assertThat(node.get("records").get(0).get("id").isTextual())
                .as("雪花 ID 必须是 string，否则浏览器会静默丢精度")
                .isTrue();
        assertThat(node.get("records").get(0).get("id").asText())
                .isEqualTo("2119378105463029765");
    }

    @Test
    @DisplayName("total=0 的分页体也自洽（0 是 number，totalPages=0，hasNext=false）")
    void serializesEmptyPage() throws Exception {
        JsonNode node = mapper.readTree(
                mapper.writeValueAsString(PageResult.of(List.of(), 0L, 1, 20)));

        assertThat(node.get("total").isNumber()).isTrue();
        assertThat(node.get("total").asLong()).isZero();
        assertThat(node.get("totalPages").asInt()).isZero();
        assertThat(node.get("hasNext").asBoolean()).isFalse();
        assertThat(node.get("records").isArray()).isTrue();
        assertThat(node.get("records")).isEmpty();
    }

    /**
     * 测试用的行记录：一个雪花 ID + 一个文本，用来在同一响应里同时观察两类字段。
     *
     * @param id   雪花主键
     * @param name 名称
     */
    private record Row(Long id, String name) {
    }
}
