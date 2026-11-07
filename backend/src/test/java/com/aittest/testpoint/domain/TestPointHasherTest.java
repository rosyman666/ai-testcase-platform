package com.aittest.testpoint.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 测试点幂等身份的回归测试。
 *
 * <p><b>为什么这个类值得单独测</b>：它一个方法，但它决定的是"什么算同一个测试点"。
 * 判严了会把不同的点并成一个（少抽点），判松了会把同一个点判成两个（数量翻倍，
 * 正是验收 ④ 要挡的东西）。两种错误都不会抛异常，只会让评审页上的数字悄悄变。</p>
 *
 * @author 寇豆码
 */
class TestPointHasherTest {

    /** 基准样本。 */
    private static final Long DOC = 100L;

    @Test
    @DisplayName("确定性：同一输入永远得到同一个哈希（重复抽取不翻倍的前提）")
    void isDeterministic() {
        String first = TestPointHasher.hashOf(DOC, "创建用户", "FUNCTIONAL");
        String second = TestPointHasher.hashOf(DOC, "创建用户", "FUNCTIONAL");
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("形状：64 位小写十六进制，与 VARCHAR(64) 列宽严格一致")
    void hasExpectedShape() {
        String hash = TestPointHasher.hashOf(DOC, "创建用户", "FUNCTIONAL");
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("标题首尾空白不算差异：模型爱写 '创建用户 '，不 trim 就会重抽出重复点")
    void trimsTitleEdges() {
        assertThat(TestPointHasher.hashOf(DOC, "  创建用户  ", "FUNCTIONAL"))
                .isEqualTo(TestPointHasher.hashOf(DOC, "创建用户", "FUNCTIONAL"));
    }

    @Test
    @DisplayName("标题内部空白算差异：'创建 用户' 与 '创建用户' 可能是两条不同的点，不能并")
    void keepsInnerWhitespace() {
        assertThat(TestPointHasher.hashOf(DOC, "创建 用户", "FUNCTIONAL"))
                .isNotEqualTo(TestPointHasher.hashOf(DOC, "创建用户", "FUNCTIONAL"));
    }

    @Test
    @DisplayName("分类不同是不同的点：同一个标题的功能点与边界点都要留下")
    void differsByCategory() {
        assertThat(TestPointHasher.hashOf(DOC, "创建用户", "BOUNDARY"))
                .isNotEqualTo(TestPointHasher.hashOf(DOC, "创建用户", "FUNCTIONAL"));
    }

    @Test
    @DisplayName("文档不同互不干扰：两篇文档抽到同名点不能互相去重")
    void differsByDoc() {
        assertThat(TestPointHasher.hashOf(200L, "创建用户", "FUNCTIONAL"))
                .isNotEqualTo(TestPointHasher.hashOf(DOC, "创建用户", "FUNCTIONAL"));
    }

    @Test
    @DisplayName("分隔符挡住边界碰撞：docId=1,title='2x' 与 docId=12,title='x' 必须不同")
    void separatesFieldBoundaries() {
        // 直接拼接会得到 1+"2x" 与 12+"x"，两边拼出来都是 "12x" —— 分隔符就是为这个存在的
        assertThat(TestPointHasher.hashOf(1L, "2x", "FUNCTIONAL"))
                .isNotEqualTo(TestPointHasher.hashOf(12L, "x", "FUNCTIONAL"));
    }

    @Test
    @DisplayName("同一标题正文变了不影响身份：description 不参与哈希（人会改描述）")
    void ignoresDescriptionEntirely() {
        // hashOf 的签名里根本没有 description —— 用一条断言把这个契约钉死：
        // 将来谁"顺手"把 description 加进来，这条会编译不过或立刻红
        assertThat(TestPointHasher.class.getDeclaredMethods())
                .filteredOn(m -> m.getName().equals("hashOf"))
                .singleElement()
                .satisfies(m -> assertThat(m.getParameterCount()).as("hashOf 只接受 docId/title/category 三参").isEqualTo(3));
    }

    @Test
    @DisplayName("缺参一律抛 IllegalArgumentException，不许用空串悄悄算出一个哈希")
    void rejectsMissingFields() {
        assertThatThrownBy(() -> TestPointHasher.hashOf(null, "x", "FUNCTIONAL"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TestPointHasher.hashOf(DOC, "   ", "FUNCTIONAL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
        assertThatThrownBy(() -> TestPointHasher.hashOf(DOC, "x", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("category");
    }
}
