package com.aittest.aicore.prompt;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 模板渲染器：{@code &#123;&#123;var&#125;&#125;} → 实际值。
 *
 * <p><b>唯一的硬规则：缺变量必须抛异常，不许静默替换成空串</b>（§8.6）。</p>
 *
 * <p>这条规则的代价与收益非常不对称。静默替换空串时：模板照常渲染出一个"看起来完整"的
 * 提示词，但 {@code &#123;&#123;api_spec&#125;&#125;} 的位置是空的——模型于是拿到"请为下面的接口生成用例："后面什么都没有，
 * 它会<b>很有礼貌地编一个</b>。结果是一条语法合法、schema 通过、内容与真实接口毫无关系的用例，
 * 并且它会正常入库、正常进入评审池，<b>没有任何一步报错</b>。等到有人发现"用例怎么都对不上接口"，
 * 已经过去了几十次调用，而日志里每条 prompt 都是"渲染成功"。</p>
 *
 * <p>反过来说，抛异常只损失一次调用，且失败点精确指向"哪个变量没传"。</p>
 *
 * <p><b>为什么不用 {@code String.replace} 循环</b>：替换进去的值本身可能含 {@code &#123;&#123;x&#125;&#125;}
 * （比如接口文档里就有这种示例），循环 replace 会把第二次替换作用在第一次的结果上，
 * 形成"值里的占位符被再次展开"。这里用 {@link Matcher#appendReplacement} 单趟替换，
 * 替换进去的内容<b>不会再被扫描</b>——这是模板渲染的基本要求，踩过一次就会记一辈子。</p>
 *
 * @author 寇豆码
 */
@Component
public class PromptRenderer {

    /** 占位符：{@code {{name}}}，允许大括号内有空白与下划线数字。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    /**
     * 渲染模板。
     *
     * @param template 模板正文（可为空，为空时返回空串）
     * @param variables 变量表（值可为空串，但 <b>key 必须存在</b>）
     * @return 渲染后的文本
     * @throws BizException {@link ErrorCode#E_PARAM_INVALID}：缺变量时列出全部缺失项
     */
    public String render(String template, Map<String, ?> variables) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Map<String, ?> safeVariables = variables == null ? Map.of() : variables;
        Set<String> missing = new LinkedHashSet<>();
        // 一次性收集**所有**缺失变量再报错：只报第一个会让人改一轮跑一轮，改三次才跑得通
        Matcher probe = PLACEHOLDER.matcher(template);
        while (probe.find()) {
            String name = probe.group(1);
            if (safeVariables.get(name) == null) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new BizException(ErrorCode.E_PARAM_INVALID,
                    "模板渲染缺少变量：" + String.join("、", missing),
                    missingAnswer(missing));
        }

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 64);
        while (matcher.find()) {
            Object value = safeVariables.get(matcher.group(1));
            // quoteReplacement：值里出现 $ 或 \ 时，直接 appendReplacement 会被当成组引用
            matcher.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 列出模板里出现的全部变量名（去重，保持出现顺序）。
     *
     * @param template 模板正文
     * @return 变量名集合
     */
    public Set<String> placeholders(String template) {
        Set<String> names = new LinkedHashSet<>();
        if (template == null) {
            return names;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * 校验变量表是否覆盖模板全部占位符（不渲染，只检查）。
     *
     * <p>给"保存模板"用：保存时就能发现"模板加了新变量但调用方没传"，
     * 而不是等下一次生成任务失败。</p>
     *
     * @param template  模板正文
     * @param variables 变量表
     * @return 缺失的变量名（空集合表示齐了）
     */
    public List<String> missingVariables(String template, Map<String, ?> variables) {
        Set<String> names = placeholders(template);
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            if (variables == null || variables.get(name) == null) {
                missing.add(name);
            }
        }
        return missing;
    }

    /**
     * 缺失变量的附加信息（前端可直接高亮表单）。
     *
     * @param missing 缺失变量名
     * @return 附加数据
     */
    private Map<String, Object> missingAnswer(Set<String> missing) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("missingVariables", new ArrayList<>(missing));
        return payload;
    }
}
