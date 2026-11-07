package com.aittest.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.TimeZone;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局配置：Long → String、时间统一 ISO-8601 UTC。
 *
 * <p>两个约定都是"跨文件契约"，必须在唯一出口配置，不能靠各处注解：</p>
 * <ol>
 *   <li>主键是雪花 {@code bigint}，超过 JS {@code Number.MAX_SAFE_INTEGER}，序列化成字符串
 *       才不会在浏览器里被静默改写（前端 TS 类型一律声明为 {@code string}）；</li>
 *   <li>数据库所有 {@code datetime(3)} 存 UTC，出参统一 {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'}，
 *       由前端 dayjs 转本地时区展示。</li>
 * </ol>
 *
 * <p><b>{@link Instant} 必须与 {@link LocalDateTime} 用同一套格式</b>：默认的
 * {@code Instant.toString()} 会输出 9 位纳秒（{@code ...T03:32:35.159162900Z}），
 * 而 {@code LocalDateTime} 走本类的 3 位毫秒序列化器 —— 同一个后端两种时间格式，
 * 前端 dayjs 解析虽然都能过，但"看起来不一样"会被误判为 bug，也让日志难以按字符串对齐。
 * 因此这里给 {@link Instant} 注册固定 3 位毫秒的序列化器。</p>
 *
 * @author 寇豆码
 */
@Configuration
public class JacksonConfig {

    /** 出参时间格式（UTC，带毫秒与 Z 后缀）。 */
    private static final DateTimeFormatter ISO_UTC_WITH_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    /** {@link Instant} 专用格式（必须带时区，否则无法格式化 Instant）。 */
    private static final DateTimeFormatter ISO_UTC_WITH_MILLIS_ZONED =
            ISO_UTC_WITH_MILLIS.withZone(ZoneOffset.UTC);

    /** 入参可接受的时间格式：带 Z / 带毫秒但不带 Z / 秒级。 */
    private static final DateTimeFormatter[] LENIENT_INPUT_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    };

    /**
     * 定制 Spring Boot 自动配置的 ObjectMapper，避免直接覆盖导致 Boot 的默认特性丢失。
     *
     * @return 定制器
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            // 1) Long 一律序列化为字符串（含基本类型 long）
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);

            // 2) LocalDateTime / Instant 出参统一 UTC ISO-8601（固定 3 位毫秒），入参宽松解析
            builder.serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(ISO_UTC_WITH_MILLIS));
            builder.deserializerByType(LocalDateTime.class, new LenientLocalDateTimeDeserializer());
            builder.serializerByType(Instant.class, new FixedInstantSerializer());
            builder.deserializerByType(Instant.class, new LenientInstantDeserializer());

            builder.timeZone(TimeZone.getTimeZone("UTC"));
            builder.featuresToDisable(
                    SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                    SerializationFeature.FAIL_ON_EMPTY_BEANS,
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        };
    }

    /**
     * {@link Instant} 序列化器：固定输出 3 位毫秒 + {@code Z}。
     *
     * <p>与 {@code Instant.toString()} 的区别：后者在毫秒为 0 时输出 0 位、纳秒非 0 时输出 9 位，
     * 长度不定，无法作为稳定的对外契约。</p>
     */
    static class FixedInstantSerializer extends JsonSerializer<Instant> {

        @Override
        public void serialize(Instant value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            if (value == null) {
                generator.writeNull();
                return;
            }
            generator.writeString(ISO_UTC_WITH_MILLIS_ZONED.format(value));
        }
    }

    /**
     * 宽松的 {@link Instant} 反序列化器：能收纳秒/毫秒/秒级、带 Z 或带偏移量、以及不带时区（按 UTC 解释）。
     */
    static class LenientInstantDeserializer extends JsonDeserializer<Instant> {

        @Override
        public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String text = parser.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            String value = text.trim();
            try {
                // 覆盖 2026-11-07T03:32:35.159162900Z / 带 +08:00 偏移 等标准写法
                return Instant.parse(value);
            } catch (DateTimeParseException ignored) {
                // 继续尝试其他格式
            }
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException ignored) {
                // 继续尝试其他格式
            }
            for (DateTimeFormatter formatter : LENIENT_INPUT_FORMATS) {
                try {
                    return LocalDateTime.parse(value, formatter).toInstant(ZoneOffset.UTC);
                } catch (DateTimeParseException ignored) {
                    // 尝试下一种格式
                }
            }
            try {
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ex) {
                throw new IOException("无法解析时间字符串：" + value, ex);
            }
        }
    }

    /**
     * 宽松的 LocalDateTime 反序列化器：前端可能传带 Z、不带 Z、带毫秒或不带毫秒的字符串。
     *
     * <p>库内时间统一按 UTC 存储，因此解析出的 {@link LocalDateTime} 不做时区换算，
     * 直接作为 UTC 字面量使用。</p>
     */
    static class LenientLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

        @Override
        public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String text = parser.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            String value = text.trim();
            for (DateTimeFormatter formatter : LENIENT_INPUT_FORMATS) {
                try {
                    return LocalDateTime.parse(value, formatter);
                } catch (DateTimeParseException ignored) {
                    // 尝试下一种格式
                }
            }
            try {
                // 兜底交给 ISO_LOCAL_DATE_TIME，覆盖 2026-11-07T02:30:00.123456 之类
                return LocalDateTime.parse(value);
            } catch (DateTimeParseException ex) {
                throw new IOException("无法解析时间字符串：" + value, ex);
            }
        }
    }
}
