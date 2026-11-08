package com.aittest.aicore.structured;

import com.aittest.aicore.LlmException;
import com.aittest.aicore.LlmResponse;
import com.aittest.common.ErrorCode;
import com.aittest.common.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 结构化输出解析器：<b>修复 → 解析 → 解包 → 校验 → 反序列化</b> 的唯一入口。
 *
 * <p>它把"模型只会说话、程序要数据"这件事收口：上游只给一段文本，这里给出强类型列表。
 * 调用方（T05 抽点、T07 生成）不需要知道围栏、尾逗号、schema 这些东西存在。</p>
 *
 * <p><b>为什么"解包"是独立一步</b>：{@code response_format=json_object} 要求模型返回
 * <b>JSON 对象</b>，不能是裸数组；所以提示词要求 {@code {"cases": [...]}}，而 schema 描述的是
 * <b>数组</b>（这样校验失败的路径才是 {@code [2].priority}，而不是 {@code .cases[2].priority}，
 * 更短、也更贴近"第 3 条用例"的直觉）。两种形状都要能吃下：裸数组直接用，
 * 对象则取其中第一个数组字段。</p>
 *
 * <p><b>两类异常不要混</b>：</p>
 * <ul>
 *   <li>语法修不好 → {@link ErrorCode#E_LLM_PARSE_EXHAUSTED}（5 轮修复后仍失败）；</li>
 *   <li>语法好了但结构不对 → {@link ErrorCode#E_SCHEMA_INVALID}（带字段路径）。</li>
 * </ul>
 * <p>它们对应完全不同的处置：前者要把"原输出 + 语法错误"回给模型重写，后者要把
 * "原输出 + 字段路径"回给模型纠正。<b>把两者合成一个"解析失败"，修复提示词就只能靠猜。</b></p>
 *
 * @author 寇豆码
 */
@Component
public class StructuredOutputParser {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputParser.class);

    /** schema 校验。 */
    private final JsonSchemaValidator jsonSchemaValidator;

    public StructuredOutputParser(JsonSchemaValidator jsonSchemaValidator) {
        this.jsonSchemaValidator = jsonSchemaValidator;
    }

    /**
     * 解析成 JSON 节点（<b>只做语法层，不校验 schema</b>）。
     *
     * @param raw 模型原始输出
     * @return JSON 节点
     * @throws LlmException 修复后仍不是合法 JSON
     */
    public JsonNode parseToNode(String raw) {
        JsonRepairer.RepairOutcome outcome = JsonRepairer.repairWithMeta(raw);
        if (outcome.text().isBlank()) {
            throw new LlmException(ErrorCode.E_LLM_PARSE_EXHAUSTED, "模型未返回任何内容", true);
        }
        try {
            return JsonUtils.mapper().readTree(outcome.text());
        } catch (JsonProcessingException ex) {
            // 只记前 200 字符：把整段输出打进日志会把有用信息淹掉，也会撑爆日志文件
            log.warn("[PARSE] 修复后仍无法解析：{}；输出前 200 字={}", ex.getOriginalMessage(),
                    abbreviate(outcome.text(), 200));
            throw new LlmException(ErrorCode.E_LLM_PARSE_EXHAUSTED,
                    "模型输出无法解析为 JSON：" + ex.getOriginalMessage(), true, ex);
        }
    }

    /**
     * 解析成数组并做 schema 校验（返回元信息）。
     *
     * @param raw        模型原始输出
     * @param schemaPath schema 路径，如 {@code classpath:schema/testcase.schema.json}
     * @return 解析结果
     */
    public ParseResult parseArrayWithMeta(String raw, String schemaPath) {
        JsonRepairer.RepairOutcome outcome = JsonRepairer.repairWithMeta(raw);
        if (outcome.changed()) {
            log.debug("[PARSE] 模型输出经过修复（truncated={}），已修复后解析", outcome.truncated());
        }
        ArrayNode array = toArray(parseToNode(raw));
        jsonSchemaValidator.validate(array, schemaPath);
        return new ParseResult(array, outcome.changed(), outcome.truncated());
    }

    /**
     * 解析成数组并做 schema 校验（只要数据）。
     *
     * @param raw        模型原始输出
     * @param schemaPath schema 路径
     * @return 数组节点
     */
    public ArrayNode parseArray(String raw, String schemaPath) {
        return parseArrayWithMeta(raw, schemaPath).items();
    }

    /**
     * <b>只解包不校验</b>：修复 → 解析 → 归一成数组。
     *
     * <p><b>给谁用</b>：三档降级里的<b>宽松档</b>（{@code LENIENT}）。那条路径的前提正是
     * "整体不合规、但部分元素合规"，所以绝不能走 {@link #parseArrayWithMeta}——
     * 它一校验就抛 {@code E_SCHEMA_INVALID}，宽松档连数据都拿不到。
     * 调用方拿到数组后按元素逐个筛（{@code JsonSchemaValidator#filterValidItems}）。</p>
     *
     * <p><b>为什么不在这里再加一个"筛选"参数</b>：本类的职责边界是"文本 → 节点"，
     * 元素级筛选需要 schema，而 schema 归 {@code JsonSchemaValidator}。
     * 把筛选塞进来会让解析器依赖 schema 的字段路径语义，也就会长出第二份校验实现。</p>
     *
     * @param raw 模型原始输出
     * @return 数组节点（裸数组或 {@code {"cases": [...]}} 均可）
     * @throws LlmException 修复后仍不是合法 JSON，或既不是数组也找不到数组字段
     */
    public ArrayNode unwrapArray(String raw) {
        // 与 parseArrayWithMeta 走同一条解包路径：私有 toArray 只有一份实现，
        // 这里只把"入口"公开出来（复制一份解包逻辑就等着两处漂移）
        return toArray(parseToNode(raw));
    }

    /**
     * 解析成强类型列表。
     *
     * @param raw         模型原始输出
     * @param schemaPath  schema 路径
     * @param elementType 元素类型
     * @param <T>         元素类型
     * @return 列表（可能为空，但不会是 {@code null}）
     */
    public <T> List<T> parseList(String raw, String schemaPath, Class<T> elementType) {
        ArrayNode array = parseArray(raw, schemaPath);
        List<T> list = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JsonNode element = array.get(i);
            try {
                list.add(JsonUtils.mapper().treeToValue(element, elementType));
            } catch (JsonProcessingException ex) {
                // schema 已通过，这里失败说明"schema 与 DTO 定义不一致"——是代码 bug，不是模型问题
                throw new LlmException(ErrorCode.E_INTERNAL,
                        "输出无法映射为 " + elementType.getSimpleName() + "（第 " + i + " 个元素），请检查 schema 与 DTO 是否一致",
                        false, ex);
            }
        }
        return list;
    }

    /**
     * 把节点归一成数组：裸数组直接用；对象取第一个数组字段。
     *
     * @param node 解析出的节点
     * @return 数组节点
     * @throws LlmException 既不是数组、也找不到数组字段
     */
    private ArrayNode toArray(JsonNode node) {
        if (node instanceof ArrayNode array) {
            return array;
        }
        if (node != null && node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (entry.getValue() instanceof ArrayNode array) {
                    return array;
                }
            }
            throw new LlmException(ErrorCode.E_SCHEMA_INVALID,
                    "模型输出里没有数组字段（期望形如 {\"cases\": [...]}，实际字段："
                            + String.join("/", iterableOf(node.fieldNames())) + "）", true);
        }
        throw new LlmException(ErrorCode.E_SCHEMA_INVALID,
                "模型输出既不是数组也不是包含数组的对象（实际类型：" + (node == null ? "null" : node.getNodeType()) + "）",
                true);
    }

    /**
     * 收集字段名（只为拼报错信息）。
     *
     * @param iterator 字段名迭代器
     * @return 字段名列表
     */
    private List<String> iterableOf(java.util.Iterator<String> iterator) {
        List<String> names = new ArrayList<>();
        iterator.forEachRemaining(names::add);
        return names;
    }

    /**
     * 截断长文本（日志/报错用）。
     *
     * @param text      原文
     * @param maxLength 上限
     * @return 截断后的文本
     */
    static String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }

    /**
     * 解析结果 + 质量元信息。
     *
     * @param items     数组节点
     * @param repaired  模型输出是否被修复过（<b>首次通过率</b>的分子要排除它）
     * @param truncated 是否发生截断补全（为真必须置 {@code degraded}=1，见 D-03）
     */
    public record ParseResult(ArrayNode items, boolean repaired, boolean truncated) {
    }

    /**
     * 便捷方法：从 {@link LlmResponse} 直接解析（保留形参类型，避免调用方每次 {@code getContent()}）。
     *
     * @param response    模型响应
     * @param schemaPath  schema 路径
     * @param elementType 元素类型
     * @param <T>         元素类型
     * @return 列表
     */
    public <T> List<T> parseList(LlmResponse response, String schemaPath, Class<T> elementType) {
        if (response == null || response.getContent() == null) {
            throw new LlmException(ErrorCode.E_LLM_PARSE_EXHAUSTED, "模型未返回任何内容", true);
        }
        return parseList(response.getContent(), schemaPath, elementType);
    }
}
