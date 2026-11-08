package com.aittest.generation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.aittest.config.JacksonConfig;
import com.aittest.generation.domain.GenTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 任务出参 VO 的时间契约（T06）。
 *
 * <p>实体里的 {@code LocalDateTime} 存的是 <b>UTC</b>（列语义，见 §3.2.1.1），
 * 转成 {@code Instant} 时若误用系统默认时区（本机 +08）会整体偏 8 小时，
 * 而且前端拿到的是合法 ISO 串、看不出错。这里把"同一个 instant、带 Z、无漂移"锁住。</p>
 *
 * @author 寇豆码
 */
class GenTaskVOTest {

    private static final LocalDateTime CREATED =
            LocalDateTime.of(2026, 11, 7, 4, 16, 24, 128_000_000);

    private static final LocalDateTime STARTED =
            LocalDateTime.of(2026, 11, 7, 4, 16, 25, 0);

    private static final LocalDateTime FINISHED =
            LocalDateTime.of(2026, 11, 7, 4, 16, 30, 500_000_000);

    private static GenTask entity() {
        GenTask t = new GenTask();
        t.setId(1234567890123456789L);
        t.setTaskNo("T20261107-000001");
        t.setScene("TESTPOINT_EXTRACT");
        t.setStatus("SUCCESS");
        t.setProgress(100);
        t.setDoneItems(3);
        t.setTotalItems(3);
        t.setSuccessItems(3);
        t.setFailedItems(0);
        // V6 契约：produced 读 produced_items（7），不是 accepted_cases（99，评审采纳数）
        t.setAcceptedCases(99);
        t.setProducedItems(7);
        t.setProvider("mock");
        t.setModel("model-x");
        t.setDocId(9001L);
        t.setErrorCode("");
        t.setErrorMsg("");
        t.setCreatedAt(CREATED);
        t.setStartedAt(STARTED);
        t.setFinishedAt(FINISHED);
        return t;
    }

    @Test
    @DisplayName("LocalDateTime(UTC) → Instant 无时区漂移，toString 带 Z")
    void utcLocalDateTimeConvertsWithoutDrift() {
        GenTaskVO vo = GenTaskVO.of(entity());

        assertThat(vo.createdAt()).isEqualTo(CREATED.toInstant(ZoneOffset.UTC));
        assertThat(vo.createdAt()).isEqualTo(Instant.parse("2026-11-07T04:16:24.128Z"));
        assertThat(vo.createdAt().toString()).isEqualTo("2026-11-07T04:16:24.128Z");
        assertThat(vo.startedAt().toString()).endsWith("Z");
        assertThat(vo.finishedAt().toString()).endsWith("Z");
        // 明确排除 +08 漂移：若写成系统默认时区，会变成前一天的 20:16
        assertThat(vo.createdAt()).isNotEqualTo(CREATED.toInstant(
                java.time.ZoneId.of("Asia/Shanghai").getRules().getOffset(CREATED)));
    }

    @Test
    @DisplayName("Jackson 出参固定 3 位毫秒 + Z（秒级时间也补 .000）")
    void serializedInstantsHaveFixedMillisAndZ() throws Exception {
        // 不启 Spring 上下文：直接把 Boot 的 customizer 应用到 builder 上，验证真实序列化器
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().jacksonCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();

        String json = mapper.writeValueAsString(GenTaskVO.of(entity()));

        assertThat(json).contains("\"createdAt\":\"2026-11-07T04:16:24.128Z\"");
        assertThat(json).contains("\"startedAt\":\"2026-11-07T04:16:25.000Z\"");
        assertThat(json).contains("\"finishedAt\":\"2026-11-07T04:16:30.500Z\"");
        assertThat(json).contains("\"taskId\":\"1234567890123456789\"");
        assertThat(json).contains("\"docId\":\"9001\"");
    }

    @Test
    @DisplayName("时间为 null → VO 里也是 null；数值为 null → 回落 0")
    void nullFieldsAreHandled() {
        GenTask t = new GenTask();
        t.setId(1L);
        t.setTaskNo("T20261107-000002");
        t.setScene("CASE_GENERATE");
        t.setStatus("PENDING");

        GenTaskVO vo = GenTaskVO.of(t);

        assertThat(vo.startedAt()).isNull();
        assertThat(vo.finishedAt()).isNull();
        assertThat(vo.createdAt()).isNull();
        assertThat(vo.progress()).isZero();
        assertThat(vo.done()).isZero();
        assertThat(vo.total()).isZero();
        assertThat(vo.success()).isZero();
        assertThat(vo.failed()).isZero();
        assertThat(vo.produced()).isZero();
        assertThat(vo.docId()).isNull();
    }

    @Test
    @DisplayName("普通字段逐个透传，不串位")
    void scalarFieldsAreMapped() {
        GenTaskVO vo = GenTaskVO.of(entity());

        assertThat(vo.taskId()).isEqualTo("1234567890123456789");
        assertThat(vo.taskNo()).isEqualTo("T20261107-000001");
        assertThat(vo.scene()).isEqualTo("TESTPOINT_EXTRACT");
        assertThat(vo.status()).isEqualTo("SUCCESS");
        assertThat(vo.progress()).isEqualTo(100);
        assertThat(vo.done()).isEqualTo(3);
        assertThat(vo.total()).isEqualTo(3);
        assertThat(vo.success()).isEqualTo(3);
        assertThat(vo.failed()).isZero();
        assertThat(vo.produced()).isEqualTo(7);
        assertThat(vo.provider()).isEqualTo("mock");
        assertThat(vo.model()).isEqualTo("model-x");
        assertThat(vo.docId()).isEqualTo("9001");
        assertThat(vo.errorCode()).isEmpty();
        assertThat(vo.errorMsg()).isEmpty();
    }
}
