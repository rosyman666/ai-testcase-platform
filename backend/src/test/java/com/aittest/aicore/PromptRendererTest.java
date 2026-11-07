package com.aittest.aicore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aittest.aicore.prompt.PromptRenderer;
import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模板渲染的回归测试（T04 ⑥）。
 *
 * <p>核心断言只有一句话：<b>缺变量必须抛异常，不许静默替换成空串</b>。
 * 这条如果退化成"渲染成功但那个位置是空的"，模型会拿着半截提示词编内容，
 * 而链路全程绿灯——这正是最难查的一类问题（详见 {@link PromptRenderer} 的类注释）。</p>
 *
 * @author 寇豆码
 */
class PromptRendererTest {

    /** 被测对象（无状态，直接 new）。 */
    private final PromptRenderer renderer = new PromptRenderer();

    @Test
    @DisplayName("正常渲染：占位符被替换，允许大括号内有空白")
    void rendersPlaceholders() {
        String template = "标题={{doc_title}}，上限={{ max_points }}";
        Map<String, String> vars = Map.of("doc_title", "用户中心", "max_points", "5");
        assertThat(renderer.render(template, vars)).isEqualTo("标题=用户中心，上限=5");
    }

    @Test
    @DisplayName("缺变量：抛 40001 并一次列出**全部**缺失项")
    void throwsOnMissingVariables() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("doc_title", "用户中心");
        assertThatThrownBy(() -> renderer.render("{{doc_title}} {{a}} {{b}}", vars))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    BizException biz = (BizException) ex;
                    assertThat(biz.getErrorCode()).isEqualTo(ErrorCode.E_PARAM_INVALID);
                    // 一次列全：只报第一个会让人改一轮跑一轮
                    assertThat(biz.getMessage()).contains("a").contains("b");
                });
    }

    @Test
    @DisplayName("变量值为空串是合法的（业务上允许空），只有 key 缺失才报错")
    void allowsBlankValue() {
        assertThat(renderer.render("[{{x}}]", Map.of("x", ""))).isEqualTo("[]");
    }

    @Test
    @DisplayName("替换进去的值里含 {{...}} 不得被二次展开")
    void doesNotReRenderValues() {
        String rendered = renderer.render("{{doc}}", Map.of("doc", "正文里有 {{secret}} 字样"));
        assertThat(rendered).isEqualTo("正文里有 {{secret}} 字样");
    }

    @Test
    @DisplayName("替换值里的 $ 与 \\ 不得被当成正则/组引用")
    void escapesDollarAndBackslash() {
        assertThat(renderer.render("{{v}}", Map.of("v", "价格 $100 \\ 折扣"))).isEqualTo("价格 $100 \\ 折扣");
    }

    @Test
    @DisplayName("列变量名：去重且保持出现顺序")
    void listsPlaceholders() {
        List<String> names = List.copyOf(renderer.placeholders("{{b}} {{a}} {{b}}"));
        assertThat(names).containsExactly("b", "a");
    }

    @Test
    @DisplayName("保存期预检：能提前发现'模板加了变量但调用方没传'")
    void reportsMissingWithoutRendering() {
        assertThat(renderer.missingVariables("{{a}}{{b}}", Map.of("a", "1"))).containsExactly("b");
    }
}
