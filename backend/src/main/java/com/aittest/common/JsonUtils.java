package com.aittest.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;

/**
 * JSON 工具：解析模型原始输出、写审计快照（{@code case_review.old_content}）时使用。
 *
 * <p>刻意<b>不复用</b> Spring 的全局 ObjectMapper —— 全局那份把 {@code Long} 转成了字符串
 * （为了前端精度），而内部序列化/反序列化要真实类型（把 {@code old_content} 反序列化回实体时，
 * id 变成字符串会直接抛异常）。两份配置各司其职，别合并。</p>
 *
 * @author 寇豆码
 */
public final class JsonUtils {

    /** 内部专用 ObjectMapper：保留真实类型，忽略未知字段。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonUtils() {
    }

    /**
     * 取内部 ObjectMapper（需要自定义读写时用它，不要 new）。
     *
     * @return 内部 ObjectMapper
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * 对象转 JSON 字符串。
     *
     * @param value 任意对象
     * @return JSON 字符串；失败抛 IllegalStateException（属于编码错误，不是业务错误）
     */
    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化 JSON 失败：" + ex.getOriginalMessage(), ex);
        }
    }

    /**
     * 对象转格式化 JSON（审计快照、日志排查用）。
     *
     * @param value 任意对象
     * @return 缩进后的 JSON 字符串
     */
    public static String toPrettyJson(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化 JSON 失败：" + ex.getOriginalMessage(), ex);
        }
    }

    /**
     * JSON 转对象。
     *
     * @param json  JSON 字符串
     * @param clazz 目标类型
     * @param <T>   目标类型
     * @return 对象；解析失败抛 IllegalArgumentException
     */
    public static <T> T parse(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("解析 JSON 失败：" + ex.getOriginalMessage(), ex);
        }
    }

    /**
     * JSON 转复杂泛型（如 {@code List<Map<String,Object>>}）。
     *
     * @param json          JSON 字符串
     * @param typeReference 类型引用
     * @param <T>           目标类型
     * @return 对象；解析失败抛 IllegalArgumentException
     */
    public static <T> T parse(String json, TypeReference<T> typeReference) {
        try {
            return MAPPER.readValue(json, typeReference);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("解析 JSON 失败：" + ex.getOriginalMessage(), ex);
        }
    }

    /**
     * JSON 数组转为 List。
     *
     * @param json  JSON 数组字符串
     * @param clazz 元素类型
     * @param <T>   元素类型
     * @return 列表；解析失败抛 IllegalArgumentException
     */
    public static <T> List<T> parseList(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory().constructCollectionType(List.class, clazz));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("解析 JSON 数组失败：" + ex.getOriginalMessage(), ex);
        }
    }

    /**
     * 判断字符串是否为合法 JSON（解析容错逻辑里频繁使用）。
     *
     * @param json 待判断字符串
     * @return 合法返回 true
     */
    public static boolean isValidJson(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            MAPPER.readTree(json);
            return true;
        } catch (JsonProcessingException ex) {
            return false;
        }
    }
}
