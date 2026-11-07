package com.aittest.common;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 业务单号生成器（见 §8.2）：{@code D20261107-000001} / {@code T...} / {@code R...}。
 *
 * <p><b>为什么不是纯进程内计数器</b>：早期版本只维护一个 {@code currentSeq}，进程一重启就从
 * 1 重来，而 {@code uk_doc_source_doc_no} / {@code uk_gen_task_task_no} /
 * {@code uk_execution_run_run_no} 都是<b>真唯一索引</b>（软删行照样占号），于是「重启后第一次上传」
 * 必然撞键 → {@code DuplicateKeyException} → 被兜底处理器映射成 {@code 50000 系统内部错误}。
 * 注释里"调用方捕获唯一键冲突重试即可"是错的设计：<b>撞库重试不是分配机制</b>，单进程靠撞 N 次换一个
 * 能用的号，多实例两个进程各自从 1 开始会互撞。正确做法是「<b>播种 + 重试</b>」：
 * 每个 {@code 前缀+日期} 首次取号时向数据库要"当日已用的最大序号"作为起点（播种），
 * 撞键时<b>重新播种</b>后再取（不是简单 {@code ++}，否则多实例下会连续撞）。</p>
 *
 * <p>两条实现红线（改动前请先读）：
 * <ol>
 *   <li><b>计数按「前缀 + 日期」隔离</b>。三个前缀共用计数器会让 {@code doc_no} 出现跳号
 *       （传 3 个文档后建任务，{@code task_no} 从 000004 开始），而这两个号要写进 Excel 导出与
 *       JUnit 注释供人追溯，跳号会被误判成数据丢失。</li>
 *   <li><b>播种 SQL 不得带 {@code deleted} 条件</b>。唯一索引不看逻辑删标记，被软删的行仍然占着
 *       单号；若播种时用 MyBatis-Plus 默认查询（自动注入 {@code deleted = 0}），就会漏掉软删行 →
 *       播种值偏小 → 重启后继续撞键。播种实现见 {@code config/IdGenSeederConfig}，
 *       刻意用裸 {@code JdbcTemplate} 而非 MP，见 §3.2.2 同类陷阱（{@code file_hash <> ''}）。</li>
 * </ol>
 *
 * <p>播种是<b>懒执行</b>的（首次取号时才查库），不在启动时跑——避免与 Flyway 迁移的初始化顺序
 * 产生耦合（T03 的 {@code config/TimezoneGuard} 出于同样理由选 {@code ApplicationRunner} 而不是 {@code @PostConstruct}）。</p>
 *
 * @author 寇豆码
 */
@Slf4j
@Component
public class IdGen {

    /** 单号内日期格式：yyyyMMdd（UTC）。 */
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    /** 序号格式（6 位，单日单前缀上限 999999）。 */
    private static final String SEQ_FORMAT = "%06d";

    /** 单日单前缀的序号上限，超过即视为当日额度用尽（6 位定长，也是按字典序取最大值的前提）。 */
    private static final int SEQ_MAX = 999_999;

    /** 撞键重试上限。 */
    private static final int MAX_RETRY = 3;

    /**
     * 单号类型：把「前缀 + 中文名」绑定在一起，避免三个方法各写一份前缀常量。
     */
    public enum NoType {

        /** 文档单号，前缀 {@code D}。 */
        DOC("D", "文档单号"),

        /** 任务单号，前缀 {@code T}。 */
        TASK("T", "任务单号"),

        /** 执行批次单号，前缀 {@code R}。 */
        RUN("R", "执行批次单号");

        /** 单号前缀。 */
        private final String prefix;

        /** 中文名，仅用于日志与错误文案。 */
        private final String label;

        NoType(String prefix, String label) {
            this.prefix = prefix;
            this.label = label;
        }

        /**
         * 获取单号前缀。
         *
         * @return 单字符前缀
         */
        public String prefix() {
            return prefix;
        }

        /**
         * 获取中文名。
         *
         * @return 中文名
         */
        public String label() {
            return label;
        }
    }

    /**
     * 序号播种器：返回「该前缀 + 该日期」在数据库里<b>已被占用</b>的最大序号。
     *
     * <p>实现方必须<b>不加 {@code deleted} 条件</b>（原因见类注释）。返回 0 表示当日尚未用号。</p>
     */
    @FunctionalInterface
    public interface SeqSeeder {

        /**
         * 查询当日已用最大序号。
         *
         * @param prefix 单号前缀，如 {@code D}
         * @param day    日期（yyyyMMdd，UTC）
         * @return 已用最大序号，无记录返回 0
         */
        int maxUsedSeq(String prefix, String day);
    }

    /** 序号播种器，默认恒返回 0（未接线时的退化行为：等同旧的进程内计数器）。 */
    private SeqSeeder seeder = (prefix, day) -> 0;

    /** 当前计数所属日期（yyyyMMdd，UTC），跨天时清空全部计数。 */
    private String currentDay = "";

    /** key = 前缀 + 日期（如 {@code D20261107}），value = 当日已取到的最新序号。 */
    private final Map<String, Integer> seqByKey = new HashMap<>();

    /**
     * 注入序号播种器（由 {@code config/IdGenSeederConfig} 在启动时接线）。
     *
     * @param seeder 播种器，不可为 null
     */
    public void setSeqSeeder(SeqSeeder seeder) {
        this.seeder = Objects.requireNonNull(seeder, "seeder 不能为 null");
        synchronized (this) {
            // 换播种器意味着"已用量"的判定口径变了，清空缓存强制重新播种，避免沿用旧口径的偏小值。
            this.seqByKey.clear();
            this.currentDay = "";
        }
    }

    /**
     * 生成文档单号。
     *
     * @return 形如 {@code D20261107-000001}
     */
    public String docNo() {
        return next(NoType.DOC);
    }

    /**
     * 生成任务单号。
     *
     * @return 形如 {@code T20261107-000001}
     */
    public String taskNo() {
        return next(NoType.TASK);
    }

    /**
     * 生成执行批次单号。
     *
     * @return 形如 {@code R20261107-000001}
     */
    public String runNo() {
        return next(NoType.RUN);
    }

    /**
     * 在**已发生撞键**之后重新播种并取下一个号。
     *
     * <p>与连续调用 {@link #docNo()} 的区别：先丢掉缓存里的计数、重新向数据库要最大值再取号。
     * 多实例下两个进程可能同时播种到 5、同时取 6，其中一个成功、另一个若只做 {@code ++} 会得到 7，
     * 而库里可能已有 7（第三个进程取的）——<b>只有重新播种才能收敛</b>。</p>
     *
     * @param type 单号类型
     * @return 新的业务单号
     */
    public String nextAfterConflict(NoType type) {
        synchronized (this) {
            this.seqByKey.remove(keyOf(type, today()));
        }
        return next(type);
    }

    /**
     * 取号并执行一次写库动作，自动处理唯一键冲突（重播种 + 重试，最多 {@link #MAX_RETRY} 次）。
     *
     * <p><b>调用约束（重要）</b>：{@code action} 必须是「一次独立事务」的边界。
     * Spring 在事务内抛出 {@code DuplicateKeyException} 后会把该事务标记为 rollback-only，
     * 在同一事务内重试必然继续失败。因此本方法的调用点应当
     * <b>在事务之外</b>，或让 {@code action} 调用一个带 {@code @Transactional(propagation = REQUIRES_NEW)}
     * 的 bean 方法（不能是同类自调用的 lambda，那样不经过代理）。</p>
     *
     * @param type   单号类型
     * @param action 使用单号完成写库的动作
     * @param <T>    返回值类型
     * @return {@code action} 的返回值
     * @throws BizException 连续 {@link #MAX_RETRY} 次撞键（抛 40901，不落 50000）
     */
    public <T> T generateWithRetry(NoType type, Function<String, T> action) {
        DuplicateKeyException last = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            String no = attempt == 1 ? next(type) : nextAfterConflict(type);
            try {
                return action.apply(no);
            } catch (DuplicateKeyException e) {
                last = e;
                log.warn("{} 撞唯一键，第 {}/{} 次重试（no={}）", type.label(), attempt, MAX_RETRY, no);
            }
        }
        log.error("{} 连续 {} 次撞唯一键，放弃分配", type.label(), MAX_RETRY, last);
        throw new BizException(ErrorCode.E_STATE_ILLEGAL,
                "业务单号分配失败（连续撞号），请稍后重试");
    }

    /**
     * 取下一个号（按「前缀 + 日期」独立计数）。
     *
     * @param type 单号类型
     * @return 业务单号
     */
    private synchronized String next(NoType type) {
        String day = today();
        if (!day.equals(currentDay)) {
            // 跨天：所有前缀的计数器一起失效（第二天全部从 1 开始）。
            currentDay = day;
            seqByKey.clear();
        }
        String key = prefixDayKey(type.prefix, day);
        int used = seqByKey.computeIfAbsent(key, k -> seed(type, day));
        // 先判上限再自增：写成 `seed + 1 > SEQ_MAX` 在 seed 已接近 int 上界时会溢出成负数，
        // 判断恒不成立，于是吐出一个负数序号（单号格式直接崩掉）。先比后加，永不溢出。
        if (used >= SEQ_MAX) {
            throw new BizException(ErrorCode.E_STATE_ILLEGAL,
                    "当日" + type.label() + "已达上限（" + SEQ_MAX + "），请次日再试");
        }
        int seq = used + 1;
        seqByKey.put(key, seq);
        return type.prefix() + day + "-" + String.format(SEQ_FORMAT, seq);
    }

    /**
     * 播种：向数据库要当日已用最大序号。
     *
     * <p>播种失败时降级为 0 并打 WARN —— 不让"查不到"直接把业务流程打挂，
     * 若真的撞号，{@link #generateWithRetry} 的重试会再播种一次。</p>
     *
     * @param type 单号类型
     * @param day  日期（yyyyMMdd）
     * @return 已用最大序号
     */
    private int seed(NoType type, String day) {
        try {
            int used = seeder.maxUsedSeq(type.prefix(), day);
            if (used < 0) {
                log.warn("[IDGEN] 播种 {} 返回负数({})，按 0 处理（day={}）", type.label(), used, day);
                return 0;
            }
            log.debug("[IDGEN] 播种 {} day={} 已用最大序号={}", type.label(), day, used);
            return used;
        } catch (RuntimeException e) {
            log.warn("[IDGEN] 播种 {} 失败（day={}），本次从 0 起算；若撞号将由重试兜住: {}",
                    type.label(), day, e.toString());
            return 0;
        }
    }

    /**
     * 当前日期（UTC）。
     *
     * <p>必须用 UTC：库内时间口径是 UTC，若按本地日期切分，北京时间 08:00 前生成的单号会与昨日重号。</p>
     *
     * @return yyyyMMdd
     */
    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).format(DAY_FORMAT);
    }

    /**
     * 构造「前缀 + 日期」缓存键。
     *
     * @param prefix 前缀
     * @param day    日期
     * @return 缓存键
     */
    private static String prefixDayKey(String prefix, String day) {
        return prefix + day;
    }

    /**
     * 由单号类型与日期构造缓存键。
     *
     * @param type 单号类型
     * @param day  日期
     * @return 缓存键
     */
    private static String keyOf(NoType type, String day) {
        return prefixDayKey(type.prefix(), day);
    }
}
