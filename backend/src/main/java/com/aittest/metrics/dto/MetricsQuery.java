package com.aittest.metrics.dto;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.Getter;
import lombok.Setter;

/**
 * #43–#47 的统一入参与时间窗归一化（docs/07 §3.1 / §4.0.2）。
 *
 * <p><b>时区口径写死</b>：{@code from}/{@code to} 按东八区民用日解释，
 * 全链路 {@code datetime(3)} 存 UTC。转换只用 {@link #SHANGHAI_OFFSET_HOURS} 这个固定
 * {@code +8} 偏移做算术，与 SQL 里 {@code DATE(created_at + INTERVAL 8 HOUR)} 同源；
 * 不引入第二条时间真源，也不在 SQL 里求当前时间。</p>
 *
 * <p><b>左闭右开</b> {@code [from, to+1day)}：{@code fromUtc = from 00:00 - 8h}，
 * {@code toUtcExclusive = (to+1day) 00:00 - 8h}，SQL 用 {@code >= ... AND < ...}
 * 而不是 {@code BETWEEN}（右边界会闭合）。</p>
 *
 * <p><b>为什么用静态 {@link #parse} 而不是 Spring 数据绑定</b>：{@code LocalDate} 绑定失败
 * 会走 {@code BindException} 分支，错误文案不受控；显式解析能把「格式错 / to&lt;from」
 * 稳定映射到 {@code 40001} 并给出可展示的中文。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
public class MetricsQuery {

    /** 固定 UTC+8 偏移（无 DST）；与 SQL 的 {@code INTERVAL 8 HOUR} 同源同一常量。 */
    public static final int SHANGHAI_OFFSET_HOURS = 8;

    /** 默认近 7 天：to - 6 天。 */
    private static final int DEFAULT_RANGE_DAYS = 6;

    private LocalDate from;
    private LocalDate to;
    private Long docId;
    private Long taskId;
    private String groupBy;

    /**
     * 从 HTTP 参数构造并校验查询对象。
     *
     * @param fromText 起始日 {@code yyyy-MM-dd}（可空）
     * @param toText 结束日 {@code yyyy-MM-dd}（可空）
     * @param docId 文档 ID（可空）
     * @param taskId 任务 ID（可空）
     * @param groupBy 分组视角（可空）
     * @return 已归一化的查询对象
     * @throws BizException 日期格式错 / {@code to < from} → 40001
     */
    public static MetricsQuery parse(String fromText, String toText, Long docId,
                                     Long taskId, String groupBy) {
        MetricsQuery query = new MetricsQuery();
        query.docId = docId;
        query.taskId = taskId;
        query.groupBy = groupBy;

        LocalDate today = LocalDateTime.now(ZoneOffset.UTC)
                .plusHours(SHANGHAI_OFFSET_HOURS)
                .toLocalDate();
        query.to = parseDate(toText, "to", today);
        query.from = parseDate(fromText, "from", query.to.minusDays(DEFAULT_RANGE_DAYS));
        if (query.to.isBefore(query.from)) {
            throw new BizException(ErrorCode.E_PARAM_INVALID, "结束日期不能早于开始日期（to < from）");
        }
        return query;
    }

    /**
     * 解析单个日期；空值回落，格式必须是严格的 {@code yyyy-MM-dd}。
     *
     * @param text 原始文本
     * @param name 参数名（用于错误文案）
     * @param fallback 空值时的回落
     * @return 解析结果
     */
    private static LocalDate parseDate(String text, String name, LocalDate fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(text.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ex) {
            throw new BizException(ErrorCode.E_PARAM_INVALID,
                    name + " 日期格式不正确，应为 yyyy-MM-dd：" + text);
        }
    }

    /**
     * 起始时间（UTC），供 XML 的 {@code #{q.fromUtc}} 绑定。
     *
     * @return {@code from 00:00 - 8h}
     */
    public LocalDateTime getFromUtc() {
        return from == null ? null : from.atStartOfDay().minusHours(SHANGHAI_OFFSET_HOURS);
    }

    /**
     * 结束时间（UTC，开区间），供 XML 的 {@code #{q.toUtcExclusive}} 绑定。
     *
     * @return {@code (to+1day) 00:00 - 8h}
     */
    public LocalDateTime getToUtcExclusive() {
        return to == null ? null : to.plusDays(1).atStartOfDay().minusHours(SHANGHAI_OFFSET_HOURS);
    }

    /** @return 起始时间（UTC），与 {@link #getFromUtc()} 同源 */
    public LocalDateTime fromUtc() {
        return getFromUtc();
    }

    /** @return 结束时间（UTC，开区间），与 {@link #getToUtcExclusive()} 同源 */
    public LocalDateTime toUtcExclusive() {
        return getToUtcExclusive();
    }

    /**
     * 白名单校验 {@code groupBy}，空值回落第一项。
     *
     * <p>本批 #44 只允许 {@code day}、#46 只允许 {@code task/day/model}、#47 只允许 {@code day}；
     * {@code template} / {@code category} 尚未定义映射，放进白名单等于交付一个不生效的筛选。</p>
     *
     * @param allowedCsv 逗号分隔的可选值，第一项为默认值
     * @return 归一化后的 groupBy
     * @throws BizException 非法值 40001，message 列出可选值
     */
    public String requireGroupBy(String allowedCsv) {
        List<String> allowed = new ArrayList<>();
        for (String token : allowedCsv.split(",", -1)) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                allowed.add(trimmed);
            }
        }
        if (allowed.isEmpty()) {
            throw new BizException(ErrorCode.E_PARAM_INVALID, "groupBy 可选值配置为空");
        }
        if (groupBy == null || groupBy.isBlank()) {
            return allowed.get(0);
        }
        String candidate = groupBy.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(candidate)) {
            throw new BizException(ErrorCode.E_PARAM_INVALID,
                    "groupBy 含非法值：" + groupBy + "；可选值：" + String.join("/", allowed));
        }
        return candidate;
    }
}
