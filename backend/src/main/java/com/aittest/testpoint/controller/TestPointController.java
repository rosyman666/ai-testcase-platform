package com.aittest.testpoint.controller;

import com.aittest.common.ApiResponse;
import com.aittest.common.PageQuery;
import com.aittest.common.PageResult;
import com.aittest.testpoint.dto.BatchStatusReq;
import com.aittest.testpoint.dto.TestPointCreateReq;
import com.aittest.common.enums.TaskScene;
import com.aittest.generation.service.GenTaskService;
import com.aittest.testpoint.dto.TestPointUpdateReq;
import com.aittest.testpoint.dto.TestPointVO;
import com.aittest.testpoint.service.TestPointExtractService;
import com.aittest.testpoint.service.TestPointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试点接口（§4.2 第 14~18 号端点）。
 *
 * <p><b>T06 起 {@code POST /api/testpoints/extract} 立即返回 {@code {taskId}}</b>（验收⑦），
 * 抽取交给异步引擎（§9 决策 5，与用例生成共用同一套调度/退避/SSE）。
 * 进度经 {@code GET /api/tasks/{taskId}/stream} 订阅。T05 阶段这里曾同步返回抽取报告，
 * 那是"引擎还没有"时的临时契约（§11.1 D-26），现已随 T06 落地而收口。</p>
 *
 * <p><b>切分规则仍由 {@code ExtractPlanner} 说了算</b>：本方法只消费它给出的单元清单来建任务，
 * 不重新实现切分——否则同一个 docId 在"建任务"与"执行"两个时刻可能切出不同的单元。</p>
 *
 * @author 寇豆码
 */
@RestController
@RequestMapping("/api/testpoints")
@Tag(name = "测试点", description = "测试点抽取、查询、编辑与启停")
public class TestPointController {

    /** 测试点服务。 */
    private final TestPointService testPointService;

    /** 抽取服务。 */
    private final TestPointExtractService testPointExtractService;

    /** 任务服务（抽取走异步引擎，验收⑦）。 */
    private final GenTaskService genTaskService;

    public TestPointController(TestPointService testPointService,
                               TestPointExtractService testPointExtractService,
                               GenTaskService genTaskService) {
        this.testPointService = testPointService;
        this.testPointExtractService = testPointExtractService;
        this.genTaskService = genTaskService;
    }

    /**
     * 触发抽取：<b>立即返回 {@code taskId}</b>，不等执行（验收①⑦）。
     *
     * @param docId 文档 ID
     * @return {@code {taskId, taskNo}}
     */
    @PostMapping("/extract")
    @Operation(summary = "触发测试点抽取",
            description = "立即返回 taskId，抽取由异步引擎执行；进度经 GET /api/tasks/{taskId}/stream 订阅（验收⑦）")
    public ApiResponse<Map<String, String>> extract(@RequestParam("docId") Long docId) {
        List<String> unitRefs = testPointExtractService.unitRefsOf(docId);
        Long taskId = genTaskService.create(TaskScene.TESTPOINT_EXTRACT, docId, null, null,
                unitRefs, null);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("taskId", String.valueOf(taskId));
        data.put("taskNo", genTaskService.require(taskId).getTaskNo());
        return ApiResponse.ok(data);
    }

    /**
     * 测试点分页列表。
     *
     * @param docId    文档（必填）
     * @param status   状态（空则 ACTIVE）
     * @param category 分类
     * @param query    分页
     * @return 分页列表
     */
    @GetMapping
    @Operation(summary = "测试点列表", description = "默认只返回 ACTIVE；SUPERSEDED 需显式筛选")
    public ApiResponse<PageResult<TestPointVO>> list(
            @RequestParam("docId") Long docId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "category", required = false) String category,
            PageQuery query) {
        return ApiResponse.ok(testPointService.page(docId, status, category, query));
    }

    /**
     * 测试点详情。
     *
     * @param id 主键
     * @return 详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "测试点详情")
    public ApiResponse<TestPointVO> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(testPointService.get(id));
    }

    /**
     * 手动补点。
     *
     * @param req 入参
     * @return 新建的测试点
     */
    @PostMapping
    @Operation(summary = "手动补点", description = "与 AI 抽的点共用同一张表；重复点会返回 40902")
    public ApiResponse<TestPointVO> create(@Valid @RequestBody TestPointCreateReq req) {
        return ApiResponse.ok(testPointService.createManual(req));
    }

    /**
     * 更新测试点。
     *
     * @param id  主键
     * @param req 可选字段更新
     * @return 更新后的测试点
     */
    @PatchMapping("/{id}")
    @Operation(summary = "更新测试点", description = "改标题或分类会重算去重键，下次重抽视为换了一个点")
    public ApiResponse<TestPointVO> update(@PathVariable("id") Long id,
            @Valid @RequestBody TestPointUpdateReq req) {
        return ApiResponse.ok(testPointService.update(id, req));
    }

    /**
     * 批量启停。
     *
     * @param req 入参
     * @return 实际变更条数
     */
    @PostMapping("/batch-status")
    @Operation(summary = "批量启停")
    public ApiResponse<Integer> batchStatus(@Valid @RequestBody BatchStatusReq req) {
        return ApiResponse.ok(testPointService.batchStatus(req));
    }
}
