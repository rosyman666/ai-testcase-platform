package com.aittest.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aittest.config.IdGenSeederConfig;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@link IdGen} 分配机制的回归测试（需要真实 MySQL，默认跳过）。
 *
 * <p><b>为什么要连真库</b>：被验证的核心恰恰是「播种 SQL 里<b>没有</b> {@code deleted = 0}」，
 * 这条只能靠真库验证——H2 对 {@code SUBSTRING}/{@code CAST}/{@code LIKE} 的语义与 MySQL 不完全一致，
 * 用 H2 测出来的"通过"不能证明生产行为。所以本测试<b>不</b>接 {@code @SpringBootTest}，
 * 而是自己造 {@link JdbcTemplate} 直连，只在环境变量提供密码时执行。</p>
 *
 * <p><b>运行方式</b>（<b>没设密码就自动跳过</b>，CI 不会因缺库失败）：
 * <pre>{@code
 * IDGEN_DB_PASSWORD=your_password mvn test -Dtest=IdGenSeedingTest
 * }</pre>
 * 可选覆盖 {@code IDGEN_DB_URL} / {@code IDGEN_DB_USERNAME}，默认连本机 {@code ai_testcase}。</p>
 *
 * <p><b>数据安全</b>：所有插入行都带 {@code name = 'idgen-verify'}，
 * {@link #cleanUp()} 只删自己造的行，不触碰任何真实数据。</p>
 *
 * @author 寇豆码
 */
@EnabledIfEnvironmentVariable(named = "IDGEN_DB_PASSWORD", matches = ".+",
        disabledReason = "未提供 IDGEN_DB_PASSWORD，跳过依赖真实 MySQL 的 IdGen 回归测试")
class IdGenSeedingTest {

    /** 测试行标记，清理时按它删。 */
    private static final String MARK = "idgen-verify";

    /** 造主键用；doc_source.id 不是自增列。 */
    private static final AtomicLong ID_SEQ = new AtomicLong(900000000000000000L);

    /** 裸 JDBC 句柄。 */
    private static JdbcTemplate jdbc;

    /** 单号里的日期部分（UTC），与被测类同口径。 */
    private static String day;

    @BeforeAll
    static void setUp() {
        String url = System.getenv().getOrDefault("IDGEN_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/ai_testcase?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=UTC&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
                        + "&useSSL=false&allowPublicKeyRetrieval=true");
        String user = System.getenv().getOrDefault("IDGEN_DB_USERNAME", "root");
        DriverManagerDataSource ds = new DriverManagerDataSource(
                url, user, System.getenv("IDGEN_DB_PASSWORD"));
        jdbc = new JdbcTemplate(ds);
        day = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        cleanUp();
    }

    @AfterEach
    void cleanUpTestRows() {
        cleanUp();
    }

    private static void cleanUp() {
        jdbc.update("DELETE FROM doc_source WHERE name = ?", MARK);
    }

    /** 造一个接了真实播种器的 IdGen，等价于 Spring 启动后的产物。 */
    private static IdGen newGen() {
        IdGen gen = new IdGen();
        new IdGenSeederConfig(jdbc, gen).wireSeqSeeder();
        return gen;
    }

    private static void insertDoc(String docNo, int deleted) {
        jdbc.update("INSERT INTO doc_source (id, doc_no, name, doc_type, deleted) VALUES (?,?,?,?,?)",
                ID_SEQ.incrementAndGet(), docNo, MARK, "TXT", deleted);
    }

    /** 与被测 SQL 同口径的裸查：**不带 deleted 条件**。 */
    private static long dbMax(String table, String column, String prefix) {
        Long v = jdbc.queryForObject(
                "SELECT COALESCE(MAX(CAST(SUBSTRING(" + column + ", ?) AS SIGNED)), 0) FROM " + table
                        + " WHERE " + column + " LIKE ?",
                Long.class, prefix.length() + 10, prefix + day + "-%");
        return v == null ? 0L : v;
    }

    /** 把 6 位序号格式成字符串，便于与完整单号比对。 */
    private static String fm(long seq) {
        return String.format("%06d", seq);
    }

    /** 从完整单号里取序号。 */
    private static long seqOf(String no) {
        return Long.parseLong(no.substring(no.length() - 6));
    }

    @Test
    @DisplayName("三个前缀的计数器互相隔离，不跳号")
    void prefixesAreIndependent() {
        long d0 = dbMax("doc_source", "doc_no", "D");
        long t0 = dbMax("gen_task", "task_no", "T");
        long r0 = dbMax("execution_run", "run_no", "R");

        IdGen gen = newGen();
        String doc = gen.docNo();
        String task = gen.taskNo();
        String run = gen.runNo();

        // 旧实现共用 currentSeq：取完 doc 后 task 会从 d0+2 起，测试立刻失败
        assertThat(seqOf(doc)).isEqualTo(d0 + 1);
        assertThat(seqOf(task)).isEqualTo(t0 + 1);
        assertThat(seqOf(run)).isEqualTo(r0 + 1);
        assertThat(seqOf(gen.docNo())).isEqualTo(d0 + 2);
    }

    @Test
    @DisplayName("进程重启后从库内最大值续号，不撞唯一键")
    void survivesRestart() {
        String first = newGen().docNo();
        insertDoc(first, 0);
        long used = dbMax("doc_source", "doc_no", "D");

        // 新实例 = 重启后的新进程：内存计数从零开始
        String next = newGen().docNo();

        assertThat(seqOf(next)).isEqualTo(used + 1);
        assertThat(fm(seqOf(next))).isNotEqualTo("000001");
        insertDoc(next, 0);
    }

    @Test
    @DisplayName("播种把软删行算进来（SQL 不得带 deleted = 0）")
    void seedsOverSoftDeletedRows() {
        long used = dbMax("doc_source", "doc_no", "D");
        String softDeleted = "D" + day + "-" + fm(used + 3);
        insertDoc(softDeleted, 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM doc_source WHERE doc_no = ? AND deleted = 1",
                Long.class, softDeleted)).isEqualTo(1L);

        // 若播种用了 MP 的默认查询（自动注入 deleted = 0），会漏掉这行，取到 used+1 而撞键
        assertThat(seqOf(newGen().docNo())).isEqualTo(used + 3 + 1);
    }

    @Test
    @DisplayName("多实例撞键后重新播种，一次收敛")
    void reseedsAfterConflict() {
        IdGen losing = newGen();
        String seeded = losing.docNo();
        insertDoc(seeded, 0);
        long m = seqOf(seeded);

        // 模拟第三个进程抢先占走 m+1、m+2 —— 此时 losing 的下一个号 m+1 必然撞键
        String taken1 = "D" + day + "-" + fm(m + 1);
        insertDoc(taken1, 0);
        insertDoc("D" + day + "-" + fm(m + 2), 0);

        String[] attempts = new String[5];
        int[] rounds = {0};
        String result = losing.generateWithRetry(IdGen.NoType.DOC, no -> {
            attempts[rounds[0]++] = no;
            insertDoc(no, 0);
            return no;
        });

        assertThat(attempts[0]).isEqualTo(taken1);
        // 只 ++ 不重播种的实现会拿到 m+2（仍撞），重播种才能一步到 m+3
        assertThat(seqOf(result)).isEqualTo(m + 3);
        assertThat(rounds[0]).isEqualTo(2);
    }

    @Test
    @DisplayName("当日额度耗尽抛业务异常，不落 50000、不吐负数序号")
    void rejectsWhenQuotaExhausted() {
        IdGen gen = new IdGen();
        gen.setSeqSeeder((prefix, d) -> Integer.MAX_VALUE);

        // 旧实现：MAX_VALUE + 1 溢出成负数，`seq > SEQ_MAX` 恒不成立，返回 D20261107--2147483648
        assertThatThrownBy(gen::docNo)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已达上限");
    }

    @Test
    @DisplayName("撞键 3 次仍不成功时抛业务异常，不落 50000")
    void givesUpAfterThreeConflicts() {
        IdGen gen = newGen();
        int[] calls = {0};
        // 直接抛冲突，不插占号行：插 999999 会把播种推到当日上限，
        // 重播种时先撞"额度耗尽"，测不到"重试耗尽"这条路径（我第一版就这么写错了）
        assertThatThrownBy(() -> gen.generateWithRetry(IdGen.NoType.DOC, no -> {
            calls[0]++;
            throw new DuplicateKeyException("simulated uk_doc_source_doc_no conflict: " + no);
        }))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("业务单号分配失败");
        assertThat(calls[0]).isEqualTo(3);
    }

    @Test
    @DisplayName("撞键一次但第二次成功时正常返回，不抛异常")
    void recoversAfterSingleConflict() {
        IdGen gen = newGen();
        int[] calls = {0};
        String result = gen.generateWithRetry(IdGen.NoType.DOC, no -> {
            if (calls[0]++ == 0) {
                throw new DuplicateKeyException("simulated first-attempt conflict: " + no);
            }
            return no;
        });
        assertThat(calls[0]).isEqualTo(2);
        assertThat(result).startsWith("D" + day + "-");
    }
}
