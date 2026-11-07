package com.aittest.aicore.structured;

import com.aittest.aicore.LlmException;
import com.aittest.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.JsonNodePath;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * JSON Schema 校验（networknt），schema 从 classpath 加载并缓存。
 *
 * <p><b>校验为什么必须做</b>：语法合法的 JSON 完全可能"结构不对"——{@code priority} 写成
 * {@code "高"}、{@code steps} 少一层数组、{@code cases} 是对象而不是数组。这些都能顺利
 * {@code readTree} 成功，然后在落库时变成 {@code NULL} 或 1406，错误现场离真正的原因
 * 隔着两三百行代码。schema 校验把失败点<b>提前到解析处</b>，并且给出字段路径。</p>
 *
 * <p><b>为什么错误路径长这样（{@code [2].priority}）</b>：模型的坏输出通常是"99 个元素里有 1 个
 * 字段写错"。只报"校验失败"等于让人自己拿 99 条数据去比对；带上路径可以直接定位到第 3 个元素。
 * 这条信息还会被贴进 repair 提示词（§8.6 的 {@code {{validation_error}}}），
 * <b>越精确、修复轮次的命中率越高</b>——所以路径格式是本类的对外契约，不是日志美化。</p>
 *
 * @author 寇豆码
 */
@Component
public class JsonSchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(JsonSchemaValidator.class);

    /** classpath 前缀，与 {@code prompt_template.output_schema} 的取值格式一致（§8.6）。 */
    public static final String CLASSPATH_PREFIX = "classpath:";

    /** Draft-07：两个 schema 文件都按它写，避免"用了 2020-12 语法但校验器按 draft-07 解释"的错配。 */
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    /** 已加载的 schema 缓存：路径 → 编译后的 schema（schema 随 jar 发布，进程内不会变）。 */
    private final Map<String, JsonSchema> cache = new ConcurrentHashMap<>();

    /**
     * 校验一个节点。
     *
     * @param node       待校验节点
     * @param schemaPath schema 路径，支持 {@code classpath:schema/x.schema.json} 或裸资源路径
     * @throws LlmException {@link ErrorCode#E_SCHEMA_INVALID}，消息里带首个字段路径
     */
    public void validate(JsonNode node, String schemaPath) {
        JsonSchema schema = load(schemaPath);
        Set<ValidationMessage> errors = schema.validate(node);
        if (errors == null || errors.isEmpty()) {
            return;
        }
        String detail = describe(errors);
        log.warn("[SCHEMA] 校验不通过 schema={} 首个错误={}", schemaPath, detail);
        throw new LlmException(ErrorCode.E_SCHEMA_INVALID, "模型输出未通过结构校验：" + detail, true);
    }

    /**
     * 加载（并缓存）schema。
     *
     * @param schemaPath schema 路径
     * @return 编译后的 schema
     */
    public JsonSchema load(String schemaPath) {
        String key = normalizePath(schemaPath);
        JsonSchema cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        String content = readResource(key);
        JsonSchema compiled = FACTORY.getSchema(content);
        cache.put(key, compiled);
        return compiled;
    }

    /**
     * 把校验错误压成一句话：路径 + 原因，多条时只取前 3 条。
     *
     * <p>只取前 3 条是刻意的：模型一次错 20 个字段时，把 20 条全塞进 repair 提示词会
     * 挤掉原输出，模型反而修不动；给 3 条它通常能一起改对。</p>
     *
     * @param errors 校验错误集合
     * @return 人类可读的一句话
     */
    public String describe(Set<ValidationMessage> errors) {
        List<ValidationMessage> sorted = new ArrayList<>(errors);
        // 按实例路径排序：让"最靠前的字段"先出现，读起来与数据顺序一致
        sorted.sort(Comparator.comparing(message -> formatPath(message.getInstanceLocation())));
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < sorted.size() && i < 3; i++) {
            ValidationMessage message = sorted.get(i);
            String path = formatPath(message.getInstanceLocation());
            // message.getMessage() 自带"$[2].priority: xxx"这种前缀，直接拼会得到
            // "[2].priority $[2].priority: xxx"。去掉前缀，只保留我们自己的短路径。
            parts.add(path + " " + stripLocationPrefix(message.getMessage()));
        }
        String joined = String.join("；", parts);
        if (sorted.size() > 3) {
            joined = joined + "；等共 " + sorted.size() + " 处";
        }
        return joined;
    }

    /**
     * 去掉 networknt 消息里的实例位置前缀（{@code $[2].priority: }）。
     *
     * @param message 原始消息
     * @return 只有原因部分
     */
    static String stripLocationPrefix(String message) {
        if (message == null || !message.startsWith("$")) {
            return message;
        }
        int idx = message.indexOf(": ");
        if (idx < 0) {
            return message;
        }
        // 位置段里不会有空格；有空格说明这个冒号不是位置分隔符
        if (message.substring(0, idx).indexOf(' ') >= 0) {
            return message;
        }
        return message.substring(idx + 2);
    }

    /**
     * {@link JsonNodePath} → 变量名风格路径（{@code [2].priority}、{@code .cases[0].title}）。
     *
     * <p>根节点返回 {@code "$"}（JSONPath 惯例）；数组下标写成 {@code [n]}，
     * 对象字段写成 {@code .name}——与报错位置在数据里的形状一一对应。</p>
     *
     * @param path 实例路径
     * @return 可读路径
     */
    static String formatPath(JsonNodePath path) {
        if (path == null || path.getNameCount() == 0) {
            return "$";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.getNameCount(); i++) {
            Object element = path.getElement(i);
            if (element instanceof Integer index) {
                sb.append('[').append(index).append(']');
            } else {
                sb.append('.').append(element);
            }
        }
        return sb.toString();
    }

    /**
     * 读取 schema 原文。
     *
     * <p>{@code MockLlmProvider} 需要它：mock 靠 schema 里的关键字（{@code testpoints} / {@code cases}）
     * 判断该吐哪种假数据，而它属于 {@code aicore}，<b>不能</b>依赖业务包里"这次是抽点还是生成"的枚举。</p>
     *
     * @param schemaPath schema 路径
     * @return schema 原文
     */
    public String readSchemaText(String schemaPath) {
        return readResource(normalizePath(schemaPath));
    }

    /**
     * 去掉 {@code classpath:} 前缀（{@link #readResource} 用 classloader 取，不需要前缀）。
     *
     * @param schemaPath 原始路径
     * @return 资源路径
     */
    private String normalizePath(String schemaPath) {
        if (schemaPath == null || schemaPath.isBlank()) {
            throw new LlmException(ErrorCode.E_INTERNAL, "模板未配置 output_schema，无法校验模型输出", false);
        }
        String path = schemaPath.trim();
        return path.startsWith(CLASSPATH_PREFIX) ? path.substring(CLASSPATH_PREFIX.length()) : path;
    }

    /**
     * 读 classpath 资源。
     *
     * @param resourcePath 资源路径（不含 {@code classpath:}）
     * @return 文本内容
     * @throws LlmException 读不到时抛（<b>不可重试</b>：配置错了，重试一万次也读不到）
     */
    private String readResource(String resourcePath) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                // 配置错了，不是上游问题：不可重试，也用不着 50001（那会让前端提示"模型服务异常"）
                throw new LlmException(ErrorCode.E_INTERNAL, "未找到输出 schema 文件：" + resourcePath, false);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new LlmException(ErrorCode.E_INTERNAL, "读取输出 schema 失败：" + resourcePath, false, ex);
        }
    }
}
