package com.aittest.doc.service.parser;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.DocType;
import static com.aittest.doc.service.parser.DocumentParser.OPERATION_ID_MAX;
import static com.aittest.doc.service.parser.DocumentParser.PATH_MAX;
import static com.aittest.doc.service.parser.DocumentParser.SCHEMA_MAX;
import static com.aittest.doc.service.parser.DocumentParser.SUMMARY_MAX;
import static com.aittest.doc.service.parser.DocumentParser.TAGS_MAX;
import static com.aittest.doc.service.parser.DocumentParser.requireFitsColumn;
import static com.aittest.doc.service.parser.DocumentParser.truncateForColumn;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * OpenAPI / Swagger JSON 解析器：把 {@code paths} 展开成一行一个 operation。
 *
 * <p><b>{@code $ref} 的两条护栏（缺一条就会挂）：</b></p>
 * <ol>
 *   <li><b>深度 ≤ {@value #MAX_REF_DEPTH} 层</b>——按需求约定的裁剪深度，超过就保留原始
 *       {@code $ref} 字符串不再展开。</li>
 *   <li><b>用 {@code Set} 记录已访问的指针</b>，而不是只靠深度计数。这是关键：
 *       {@code Node.properties.child.$ref = "#/components/schemas/Node"} 这种<b>自引用</b>在
 *       深度 1 就能无限展开，深度限制根本拦不住它（每展开一层深度 +1 会到 3 才停，
 *       但每次都要重新序列化整棵子树，实际会先把内存和栈打爆）。命中已访问指针 → 停止展开。</li>
 * </ol>
 *
 * <p><b>长度校验在这里做，不交给数据库</b>：本机 {@code sql_mode} 含
 * {@code STRICT_TRANS_TABLES}，{@code path} 超过 {@code VARCHAR(500)} 会抛
 * {@code ERROR 1406} 整行被拒 → 落到兜底处理器变成 {@code 50000 系统内部错误}，
 * 用户看到"系统内部错误"，实际只是某个接口路径太长。按裁决（Q4），超限即整篇解析失败。</p>
 *
 * @author 寇豆码
 */
@Component
public class OpenApiParser implements DocumentParser {

    /** {@code $ref} 最大展开深度。 */
    private static final int MAX_REF_DEPTH = 3;

    /** 结构嵌套硬上限（防止恶意深嵌套 JSON 打爆栈）。 */
    private static final int MAX_NODE_DEPTH = 64;

    /** 支持的 HTTP 方法（小写，与 OpenAPI 的 key 一致）。 */
    private static final List<String> HTTP_METHODS =
            List.of("get", "post", "put", "patch", "delete", "head", "options", "trace");

    /** JSON 序列化器（只用于把裁剪后的 schema 片段转成字符串存库）。 */
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public DocType supports() {
        return DocType.OPENAPI_JSON;
    }

    /**
     * 解析 OpenAPI JSON。
     *
     * @param bytes 文件字节
     * @param ctx   解析上下文
     * @return 接口操作列表
     */
    @Override
    public ParseResult parse(byte[] bytes, ParseContext ctx) {
        JsonNode root;
        try {
            root = mapper.readTree(bytes);
        } catch (IOException e) {
            log.warn("[DOC-PARSE] OpenAPI JSON 不合法：{}", ctx == null ? "-" : ctx.brief(), e);
            throw new BizException(ErrorCode.E_DOC_PARSE_FAILED,
                    "JSON 格式不合法，无法解析。请确认上传的是有效的 OpenAPI/Swagger JSON 文件");
        }
        if (root == null || !root.isObject()) {
            throw new BizException(ErrorCode.E_DOC_PARSE_FAILED,
                    "JSON 顶层不是对象，无法作为 OpenAPI 文档解析");
        }
        JsonNode paths = root.path("paths");
        if (!paths.isObject() || paths.isEmpty()) {
            throw new BizException(ErrorCode.E_DOC_PARSE_FAILED,
                    "这不是有效的 OpenAPI 文档：缺少 paths 节点（或 paths 为空）");
        }

        List<OperationDraft> operations = new ArrayList<>();
        int seq = 0;
        Iterator<Map.Entry<String, JsonNode>> pathIterator = paths.fields();
        while (pathIterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = pathIterator.next();
            String path = entry.getKey();
            JsonNode pathItem = expand(entry.getValue(), root, 0, 0, new HashSet<>());
            if (pathItem == null || !pathItem.isObject()) {
                continue;
            }
            for (String method : HTTP_METHODS) {
                JsonNode operation = pathItem.get(method);
                if (operation == null || !operation.isObject()) {
                    continue;
                }
                seq++;
                operations.add(toDraft(method, path, operation, root, seq, ctx));
            }
        }

        if (operations.isEmpty()) {
            throw new BizException(ErrorCode.E_DOC_PARSE_FAILED,
                    "OpenAPI 文档里没有找到任何接口定义（paths 下没有 GET/POST/PUT/PATCH/DELETE 等操作）");
        }
        return ParseResult.ofOperations(operations);
    }

    /**
     * 由一个 operation 节点构造草稿。
     *
     * @param method    HTTP 方法（小写）
     * @param path      路径
     * @param operation operation 节点
     * @param root      根节点（供 {@code $ref} 解析）
     * @param seq       顺序号
     * @param ctx       解析上下文
     * @return 草稿
     */
    private OperationDraft toDraft(String method, String path, JsonNode operation, JsonNode root,
            int seq, ParseContext ctx) {
        String where = "第 " + seq + " 个接口（" + method.toUpperCase(Locale.ROOT) + " " + abbreviate(path) + "）";
        // path 参与唯一键 uk_api_operation_doc_method_path，超限必须整篇失败，不能截断
        requireFitsColumn(path, PATH_MAX, "接口路径 path", where);

        String summary = text(operation, "summary");
        if (summary.isBlank()) {
            summary = firstLine(text(operation, "description"));
        }
        summary = truncateForColumn(summary, SUMMARY_MAX, "summary", ctx);
        String operationId = truncateForColumn(text(operation, "operationId"), OPERATION_ID_MAX, "operationId", ctx);
        String tags = truncateForColumn(joinTags(operation.get("tags")), TAGS_MAX, "tags", ctx);

        JsonNode requestNode = operation.get("requestBody");
        if (requestNode == null) {
            // Swagger 2.0：请求体写成 in=body 的 parameter
            requestNode = findBodyParameter(operation.get("parameters"));
        }
        String requestSchema = toSchema(expand(requestNode, root, 0, 0, new HashSet<>()), ctx, "requestSchema");
        String responseSchema = toSchema(
                expand(operation.get("responses"), root, 0, 0, new HashSet<>()), ctx, "responseSchema");
        String paramSchema = toSchema(
                expand(operation.get("parameters"), root, 0, 0, new HashSet<>()), ctx, "paramSchema");

        return new OperationDraft(method.toUpperCase(Locale.ROOT), path, nullToEmpty(operationId),
                nullToEmpty(summary), nullToEmpty(tags), requestSchema, responseSchema, paramSchema, seq);
    }

    /**
     * 从 Swagger 2.0 的 parameters 里找 {@code in=body} 的那一项。
     *
     * @param parameters parameters 节点
     * @return body 参数节点；没有返回 null
     */
    private JsonNode findBodyParameter(JsonNode parameters) {
        if (parameters == null || !parameters.isArray()) {
            return null;
        }
        for (JsonNode parameter : parameters) {
            if (parameter != null && "body".equalsIgnoreCase(parameter.path("in").asText(""))) {
                return parameter;
            }
        }
        return null;
    }

    /**
     * 递归展开 {@code $ref}，带深度上限与"已访问指针"集合。
     *
     * @param node      当前节点
     * @param root      根节点
     * @param refDepth  已展开的 {@code $ref} 层数
     * @param nodeDepth 结构嵌套深度（防深嵌套打爆栈）
     * @param visited   已访问过的 {@code $ref} 指针
     * @return 展开后的节点（可能是原节点）
     */
    private JsonNode expand(JsonNode node, JsonNode root, int refDepth, int nodeDepth, Set<String> visited) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (nodeDepth > MAX_NODE_DEPTH) {
            return node;
        }
        if (node.isObject()) {
            JsonNode refNode = node.get("$ref");
            if (refNode != null && refNode.isTextual()) {
                String pointer = refNode.asText();
                // 环引用或超过深度：保留原样的 $ref 字符串，不再展开
                if (refDepth >= MAX_REF_DEPTH || visited.contains(pointer)) {
                    return node;
                }
                JsonNode target = resolvePointer(root, pointer);
                if (target == null) {
                    // 外部文件引用（如 other.yaml#/...）无法在单文件内解析，保留原值
                    return node;
                }
                Set<String> next = new HashSet<>(visited);
                next.add(pointer);
                return expand(target, root, refDepth + 1, nodeDepth + 1, next);
            }
            ObjectNode result = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.set(field.getKey(), expand(field.getValue(), root, refDepth, nodeDepth + 1, visited));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            for (JsonNode item : node) {
                result.add(expand(item, root, refDepth, nodeDepth + 1, visited));
            }
            return result;
        }
        return node;
    }

    /**
     * 解析文内 JSON Pointer（{@code #/components/schemas/Foo}）。
     *
     * @param root    根节点
     * @param pointer 指针字符串
     * @return 目标节点；解析不到返回 null
     */
    private JsonNode resolvePointer(JsonNode root, String pointer) {
        if (pointer == null || !pointer.startsWith("#/")) {
            return null;
        }
        JsonNode current = root;
        String[] segments = pointer.substring(2).split("/");
        for (String rawSegment : segments) {
            if (current == null) {
                return null;
            }
            // JSON Pointer 转义：~1 => '/'，~0 => '~'
            String segment = rawSegment.replace("~1", "/").replace("~0", "~");
            current = current.get(segment);
        }
        return current;
    }

    /**
     * 节点转 schema 字符串，超长截断并留痕。
     *
     * @param node  节点
     * @param ctx   解析上下文
     * @param field 字段名（日志用）
     * @return JSON 字符串；节点为空返回 null（VO 里就不出现）
     */
    private String toSchema(JsonNode node, ParseContext ctx, String field) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String json;
        try {
            json = mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.warn("[DOC-PARSE] schema 序列化失败 {} field={}: {}", ctx == null ? "-" : ctx.brief(), field,
                    e.getMessage());
            return null;
        }
        if (json.length() <= SCHEMA_MAX) {
            return json;
        }
        log.warn("[DOC-SCHEMA] schema 超长已截断：{} field={} 原长={} 上限={}",
                ctx == null ? "-" : ctx.brief(), field, json.length(), SCHEMA_MAX);
        return json.substring(0, SCHEMA_MAX) + "…[truncated:originalLength=" + json.length() + "]";
    }

    /**
     * 读取文本字段。
     *
     * @param node  节点
     * @param field 字段名
     * @return 文本，缺失或非文本返回空串
     */
    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isTextual() ? "" : value.asText("").trim();
    }

    /**
     * tags 数组转逗号分隔字符串。
     *
     * @param tags tags 节点
     * @return 逗号分隔字符串
     */
    private String joinTags(JsonNode tags) {
        if (tags == null || !tags.isArray()) {
            return "";
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode tag : tags) {
            if (tag != null && tag.isTextual() && !tag.asText().isBlank()) {
                values.add(tag.asText().trim());
            }
        }
        return String.join(",", values);
    }

    /**
     * 取文本首行。
     *
     * @param text 文本
     * @return 首行（已 trim）
     */
    private String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int index = text.indexOf('\n');
        return (index < 0 ? text : text.substring(0, index)).trim();
    }

    /**
     * 截短用于错误文案的路径显示。
     *
     * @param path 路径
     * @return 最多 80 字符
     */
    private String abbreviate(String path) {
        if (path == null) {
            return "";
        }
        return path.length() <= 80 ? path : path.substring(0, 80) + "…";
    }

    /**
     * null 转空串。
     *
     * @param value 值
     * @return 非 null 字符串
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
