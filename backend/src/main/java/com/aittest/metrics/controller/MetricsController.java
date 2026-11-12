package com.aittest.metrics.controller;

import com.aittest.common.ApiResponse;
import com.aittest.metrics.dto.AcceptancePointVO;
import com.aittest.metrics.dto.CostVO;
import com.aittest.metrics.dto.MetricsQuery;
import com.aittest.metrics.dto.OverviewVO;
import com.aittest.metrics.dto.QualityVO;
import com.aittest.metrics.dto.RejectReasonPointVO;
import com.aittest.metrics.service.CostService;
import com.aittest.metrics.service.MetricsService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * T11 指标成本看板 5 个端点（docs/07 §4.3–§4.7）。
 *
 * <p><b>统一 {@code from/to} 解析</b>：用显式 {@code @RequestParam} + {@link MetricsQuery#parse}
 * 而不是 {@code @ModelAttribute} 绑定 {@code LocalDate}，这样「格式错 / {@code to < from}」
 * 能稳定映射到 {@code 40001} 并给出可展示的中文，不会掉进 {@code BindException} 分支。</p>
 *
 * <p><b>groupBy 白名单</b>：本批 #44 = {@code day}、#46 = {@code task/day/model}、
 * #47 = {@code day}；{@code template} / {@code category} 尚未定义映射，不放进白名单，
 * 前端下拉也不得出现不可用选项。</p>
 *
 * <p>响应一律 {@code ApiResponse.ok(...)}，{@code data} 恒存在；业务错误统一
 * HTTP 200 + {@code code != 0}。鉴权沿用 {@code /api/**} 默认保护。</p>
 *
 * @author 寇豆码
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MetricsService metricsService;
    private final CostService costService;

    public MetricsController(MetricsService metricsService, CostService costService) {
        this.metricsService = metricsService;
        this.costService = costService;
    }

    /** #43 GET /api/metrics/overview：首屏 6 卡片 + p50/p95。 */
    @GetMapping("/overview")
    public ApiResponse<OverviewVO> overview(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "docId", required = false) Long docId,
            @RequestParam(name = "taskId", required = false) Long taskId) {
        return ApiResponse.ok(metricsService.overview(MetricsQuery.parse(from, to, docId, taskId, null)));
    }

    /** #44 GET /api/metrics/acceptance：按天采纳率趋势（分母 0 的点不渲染「0%」）。 */
    @GetMapping("/acceptance")
    public ApiResponse<List<AcceptancePointVO>> acceptance(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "docId", required = false) Long docId,
            @RequestParam(name = "taskId", required = false) Long taskId,
            @RequestParam(name = "groupBy", required = false) String groupBy) {
        return ApiResponse.ok(metricsService.acceptance(
                MetricsQuery.parse(from, to, docId, taskId, groupBy)));
    }

    /** #45 GET /api/metrics/reject-reasons：0 行返回 {@code []}（不是 null）。 */
    @GetMapping("/reject-reasons")
    public ApiResponse<List<RejectReasonPointVO>> rejectReasons(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "docId", required = false) Long docId,
            @RequestParam(name = "taskId", required = false) Long taskId) {
        return ApiResponse.ok(metricsService.rejectReasons(
                MetricsQuery.parse(from, to, docId, taskId, null)));
    }

    /** #46 GET /api/metrics/cost：task/day/model 三视角 + 单用例成本 + 成本 0 成因。 */
    @GetMapping("/cost")
    public ApiResponse<CostVO> cost(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "docId", required = false) Long docId,
            @RequestParam(name = "taskId", required = false) Long taskId,
            @RequestParam(name = "groupBy", required = false) String groupBy) {
        return ApiResponse.ok(costService.cost(
                MetricsQuery.parse(from, to, docId, taskId, groupBy)));
    }

    /** #47 GET /api/metrics/quality：首次通过率 / 解析失败率 / p50·p95（不用 avg）。 */
    @GetMapping("/quality")
    public ApiResponse<QualityVO> quality(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "docId", required = false) Long docId,
            @RequestParam(name = "taskId", required = false) Long taskId,
            @RequestParam(name = "groupBy", required = false) String groupBy) {
        return ApiResponse.ok(metricsService.quality(
                MetricsQuery.parse(from, to, docId, taskId, groupBy)));
    }
}
