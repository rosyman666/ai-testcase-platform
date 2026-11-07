package com.aittest.system;

import com.aittest.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统级接口：健康检查（T01 唯一对外接口，**不鉴权**）。
 *
 * <p>健康检查必须同时反映"进程活着"和"数据库连得上"：Flyway 迁移失败时应用会启动失败，
 * 但运行中 MySQL 掉线的场景只能靠 {@code dbOk} 暴露。{@code provider} 用于一眼确认
 * 当前接的是 mock 还是真实模型网关（避免演示时误以为在花钱）。</p>
 *
 * @author 寇豆码
 */
@RestController
@RequestMapping("/api")
@Tag(name = "系统", description = "健康检查与公共配置")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    /** 数据库连通性探测超时（秒）。 */
    private static final int DB_VALID_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    private final String provider;

    private final String model;

    public HealthController(DataSource dataSource,
                            @Value("${ai.provider:mock}") String provider,
                            @Value("${ai.model:mock-model}") String model) {
        this.dataSource = dataSource;
        this.provider = provider;
        this.model = model;
    }

    /**
     * 健康检查。
     *
     * @return 统一响应体，{@code data} 形如 {@code {"status":"UP","dbOk":true,"provider":"mock"}}
     */
    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "返回应用与数据库连通状态，不鉴权")
    public ApiResponse<Map<String, Object>> health() {
        boolean dbOk = isDbReachable();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", dbOk ? "UP" : "DEGRADED");
        data.put("dbOk", dbOk);
        data.put("provider", provider);
        data.put("model", model);
        data.put("time", Instant.now().toString());
        return ApiResponse.ok(data);
    }

    /**
     * 探测数据库连通性，异常一律降级为 false 并只记 warn（健康检查本身不能抛 500）。
     *
     * @return 连接可用返回 true
     */
    private boolean isDbReachable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(DB_VALID_TIMEOUT_SECONDS);
        } catch (SQLException ex) {
            log.warn("健康检查：数据库连接不可用，原因={}", ex.getMessage());
            return false;
        }
    }
}
