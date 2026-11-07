package com.aittest.testpoint.controller;

import com.aittest.common.ApiResponse;
import com.aittest.common.PageQuery;
import com.aittest.common.PageResult;
import com.aittest.testpoint.dto.BatchStatusReq;
import com.aittest.testpoint.dto.TestPointCreateReq;
import com.aittest.testpoint.dto.TestPointExtractReport;
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

/**
 * 测试点接口（§4.2 第 14~18 号端点）。
 *
 * <p><b>本控制器有一处刻意的临时契约</b>（§11.1 D-26）：{@code POST /api/testpoints/extract}
 * 设计上应立即返回 {@code {taskId}} 并把抽取交给异步引擎（§9 决策 5，与用例生成共用），
 * 但那套引擎是 <b>T06</b> 的交付物。T05 阶段本端点<b>同步返回抽取报告</b>，
 * 换来的是"改动可立刻看到结果"的开发反馈速度。T06 落地后改回 {@code {taskId}} + SSE。</p>
 *
 * <p><b>为什么不先造个临时任务表撑住 {@code taskId} 契约</b>：假的 taskId 只能取悦接口文档。
 * 它要么指向一张空表（前端转圈转到超时），要么指向一个临时实现（T06 要拆掉重写）。
 * 同步返回报告是"事实如此"，伪造 taskId 是"文档如此"——我们选前者。</p>
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

    public TestPointController(TestPointService testPointService,
                               TestPointExtractService testPointExtractService) {
        this.testPointService = testPointService;
        this.testPointExtractService = testPointExtractService;
    }

    /**
     * 触发抽取（T05 同步实现，T06 改异步）。
     *
     * @param docId 文档 ID
     * @return 抽取汇总报告
     */
    @PostMapping("/extract")
    @Operation(summary = "触发测试点抽取",
            description = "T05 阶段同步返回抽取报告；T06 起改为返回 taskId 并走 SSE 进度（§11.1 D-26）")
    public ApiResponse<TestPointExtractReport> extract(@RequestParam("docId") Long docId) {
        return ApiResponse.ok(testPointExtractService.extractDocument(docId));
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
