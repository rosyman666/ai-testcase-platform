package com.aittest.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时区自检（T03 验收⑨）：只负责"漂移不静默"，**不修时区、不阻断开机**。
 *
 * <p>为什么用 {@link ApplicationRunner} 而不是 {@code @PostConstruct}：后者在 bean 初始化阶段执行，
 * 那时 Flyway 未必迁移完、连接池也未必就绪；更要紧的是 {@code @PostConstruct} 抛异常会打挂
 * Spring 上下文，与"只告警"的目标直接冲突。</p>
 *
 * <p>为什么必须用注入的 {@link DataSource} 而不是 {@code DriverManager.getConnection(url)}：
 * 后者是**另一条连接、另一个会话**，断言它等于没断言——要测的正是"应用实际用的连接有没有锁住时区"。</p>
 *
 * <p>WARN 里的 {@code [TZ-GUARD]} 与字段名是可 grep 的契约（T13 断言、T14 运行手册都依赖它），
 * 改文案前请先确认没有测试/文档在匹配它。</p>
 *
 * @author 寇豆码
 */
@Component
public class TimezoneGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TimezoneGuard.class);

    /** 期望的会话时区（由 JDBC URL 的 {@code forceConnectionTimeZoneToSession=true} 保证）。 */
    private static final String EXPECTED = "+00:00";

    private final DataSource dataSource;

    public TimezoneGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT @@session.time_zone")) {
            String actual = rs.next() ? rs.getString(1) : "";
            if (!EXPECTED.equals(actual)) {
                log.warn("[TZ-GUARD] session.time_zone={} expected={} -> 时间字段可能整体偏移，"
                        + "请检查 connectionTimeZone/forceConnectionTimeZoneToSession", actual, EXPECTED);
            }
        } catch (SQLException e) {
            // 连不上库属于启动环境问题，健康检查会暴露；这里只提示，不影响开机
            log.warn("[TZ-GUARD] 时区自检未能执行（数据库不可用）：{}", e.getMessage());
        }
    }
}
