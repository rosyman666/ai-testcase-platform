package com.aittest.config;

import com.aittest.common.IdGen;
import jakarta.annotation.PostConstruct;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 为 {@link IdGen} 接线「序号播种器」：把进程内计数器接上数据库里的「当日已用最大序号」。
 *
 * <p><b>要解决的问题</b>：{@code IdGen} 的计数只活在进程内存里，进程一重启就从 1 重来。
 * 而 {@code uk_doc_source_doc_no} / {@code uk_gen_task_task_no} / {@code uk_execution_run_run_no}
 * 是<b>真唯一索引</b>——被逻辑删掉的行照样占着单号。于是「重启后第一次上传文档」必然撞键，
 * 冒到全局异常处理器就是 {@code 50000 系统内部错误}，用户看到「服务器开小差了」而根本不知道
 * 是自己重传了同一个文件。播种把内存计数的起点从「0」改成「库里当日最大值」，重启即自愈。</p>
 *
 * <p><b>为什么用裸 {@code JdbcTemplate} 而不是 MyBatis-Plus</b>：这是本项目的<b>第三个同类陷阱</b>。
 * MP 会给 {@code BaseMapper} 的查询自动注入 {@code deleted = 0}，而唯一索引根本不管逻辑删标记。
 * 用 MP 查最大值会漏掉所有软删行 → 播种值偏小 → 重启后继续撞键 → 热修等于没修。
 * 同理见 §3.2.2（{@code file_hash <> ''} 不能省）与 §8.2（手写 XML 必须自己加 {@code deleted = 0}）。
 * 这里刻意绕开 ORM，让"唯一索引口径"和"业务查询口径"彻底分开。</p>
 *
 * <p><b>为什么 SQL 里的表名/列名是拼进去的</b>：拼接源是私有枚举 {@link Target}，
 * 取值只可能是 {@code doc_source.doc_no} / {@code gen_task.task_no} / {@code execution_run.run_no}
 * 三个常量，不含任何外部输入，所以不存在注入面；表名无法用 JDBC 占位符绑定，只能拼。
 * <b>前缀、日期走 {@code ?} 参数绑定</b>。</p>
 *
 * <p><b>为什么用 {@code @PostConstruct} 而不在启动时查库</b>：这里只做一次「把方法引用塞进字段」，
 * 不碰数据库。真正的查询是<b>懒执行</b>的（第一次取号时才跑），因此不会与 Flyway 的迁移顺序耦合，
 * 也不会因为启动期数据库还没就绪而失败。见 {@link IdGen} 类注释。</p>
 *
 * <p><b>多实例可接受性</b>：本方法是「分配起点」而非「分配保证」。两个实例可能同时播种到同一个最大值，
 * 靠 {@code IdGen.generateWithRetry} 的撞键重播种收敛。真正强一致的方案是数据库序列表或号段服务，
 * 对「单机单体、每天几百条」的规模是过度设计（见 §8.2）。</p>
 *
 * @author 寇豆码
 */
@Slf4j
@Configuration
public class IdGenSeederConfig {

    /** 单号里序号部分的 1 基起始位置偏移：{@code 前缀 + yyyyMMdd + '-'} 之后才是序号。 */
    private static final int SEQ_POS_OFFSET = 10;

    /** 单号业务表与列名映射。**封闭枚举，是拼接 SQL 标识符的唯一来源**。 */
    private enum Target {

        /** 文档来源表。 */
        DOC("doc_source", "doc_no"),

        /** 生成任务表。 */
        TASK("gen_task", "task_no"),

        /** 执行批次表。 */
        RUN("execution_run", "run_no");

        /** 表名。 */
        private final String table;

        /** 单号列名。 */
        private final String column;

        Target(String table, String column) {
            this.table = table;
            this.column = column;
        }

        /**
         * 按单号前缀解析目标表。
         *
         * @param prefix 单号前缀，D/T/R
         * @return 目标表
         * @throws IllegalArgumentException 前缀未知（说明 {@code IdGen.NoType} 加了新成员但这里没跟上）
         */
        static Target of(String prefix) {
            return switch (prefix) {
                case "D" -> DOC;
                case "T" -> TASK;
                case "R" -> RUN;
                default -> throw new IllegalArgumentException("未知单号前缀: " + prefix);
            };
        }
    }

    /** 裸 JDBC 句柄，绕开 MP 的逻辑删除自动条件。 */
    private final JdbcTemplate jdbcTemplate;

    /** 单号生成器，等待接线。 */
    private final IdGen idGen;

    public IdGenSeederConfig(JdbcTemplate jdbcTemplate, IdGen idGen) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate 不能为 null");
        this.idGen = Objects.requireNonNull(idGen, "idGen 不能为 null");
    }

    /**
     * 启动时把播种器接到 {@link IdGen} 上。
     */
    @PostConstruct
    public void wireSeqSeeder() {
        idGen.setSeqSeeder(this::maxUsedSeq);
        log.info("[IDGEN] 序号播种器已接线（按「前缀+日期」查库播种，播种 SQL 不含 deleted 条件）");
    }

    /**
     * 查询「该前缀 + 该日期」在库中<b>已被占用</b>的最大序号。
     *
     * <p>SQL 语义拆解（以 {@code D} + {@code 20261107} 为例）：
     * <ol>
     *   <li>{@code doc_no LIKE 'D20261107-%'} —— 只看当天的行；{@code -} 在 LIKE 里是普通字符，无需转义。</li>
     *   <li>{@code SUBSTRING(doc_no, 11)} —— 单号格式固定 16 位（1 + 8 + 1 + 6），从第 11 位起就是 6 位序号。</li>
     *   <li>{@code CAST(... AS SIGNED)} —— 变整数才能比大小；按字典序比较会把 {@code 999999} 排在
     *       {@code 1000000} 前面，六位定长下虽不出错，但语义脆弱，不依赖它。</li>
     *   <li>{@code COALESCE(..., 0)} —— 当天一条都没有时 {@code MAX} 返回 NULL，转成 0 表示"尚未用号"。</li>
     * </ol>
     * <b>没有 {@code WHERE deleted = 0}</b>，这是本方法的全部要点，删掉它热修就失效。</p>
     *
     * @param prefix 单号前缀
     * @param day    日期（yyyyMMdd，UTC）
     * @return 已用最大序号；当日无记录返回 0
     */
    int maxUsedSeq(String prefix, String day) {
        Target target = Target.of(prefix);
        String sql = "SELECT COALESCE(MAX(CAST(SUBSTRING(" + target.column + ", ?) AS SIGNED)), 0)"
                + " FROM " + target.table
                + " WHERE " + target.column + " LIKE ?";
        Long used = jdbcTemplate.queryForObject(
                sql, Long.class, prefix.length() + SEQ_POS_OFFSET, prefix + day + "-%");
        if (used == null || used <= 0L) {
            return 0;
        }
        // 超过 int 上界时保留上界值：交给 IdGen.next 的「当日额度用尽」分支抛业务错误，
        // 而不是在这里静默截断成一个能用的号（截断会造出重复单号）。
        return (int) Math.min(used, Integer.MAX_VALUE);
    }
}
