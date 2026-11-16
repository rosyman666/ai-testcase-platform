package com.aittest.execution.controller;

import com.aittest.common.ApiResponse;
import com.aittest.common.PageQuery;
import com.aittest.common.PageResult;
import com.aittest.execution.dto.ExecutionImportResult;
import com.aittest.execution.dto.ExecutionRemapResult;
import com.aittest.execution.dto.ExecutionResultVO;
import com.aittest.execution.dto.ExecutionRunDetailVO;
import com.aittest.execution.dto.ExecutionRunVO;
import com.aittest.execution.dto.ExecutionTriageReq;
import com.aittest.execution.service.ExecutionImportService;
import com.aittest.execution.service.ExecutionQueryService;
import com.aittest.execution.service.ExecutionTriageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * T10 执行结果导入、查询、归因纠正与重跑映射的六个端点（docs/01 §4.2 #37–#42）。
 *
 * <pre>
 * POST  /api/executions/import            导入结果文件（multipart：file + taskId + name?）
 * GET   /api/executions?sourceType=&amp;keyword=  批次分页列表
 * GET   /api/executions/{id}              批次详情（三值状态分布 + 失败分类分布）
 * GET   /api/executions/{id}/results?status=&amp;category=  结果明细（堆栈只含前 500 字）
 * PATCH /api/executions/results/{id}      人工纠正一条失败结果的归因分类
 * POST  /api/executions/{id}/remap        重跑用例映射
 * </pre>
 *
 * <p><b>#41 的 id 是明细主键，不是批次主键</b>：路径写成 {@code /results/{id}} 而不是
 * {@code /{id}/results/{resultId}}，因为结果 id 全局唯一（雪花）。两者混用是这一族接口
 * 最容易出的事故——传错一个 id 只会得到 40401，排查时却要翻两层表。</p>
 *
 * <p><b>异常风格与既有模块一致</b>：不自己造 HTTP 400/500，一律抛 {@code BizException}，
 * 由 {@code GlobalExceptionHandler} 映射成业务码（{@code 40401} 的 HTTP 状态是 404，
 * 其余为 200 + {@code code != 0}，前端只按 {@code code} 分支）。</p>
 *
 * <p>鉴权沿用 {@code /api/**} 默认保护（token 走 header），本类不涉及 {@code AuthContext}。</p>
 *
 * @author 寇豆码
 */
@RestController
@RequestMapping("/api/executions")
@Tag(name = "执行结果", description = "JUnit XML / CSV 结果导入、批次列表、明细、失败归因与重跑映射")
public class ExecutionController {

    /** 导入编排服务。 */
    private final ExecutionImportService executionImportService;

    /** 只读查询服务。 */
    private final ExecutionQueryService executionQueryService;

    /** 归因纠正与重跑映射（写侧）。 */
    private final ExecutionTriageService executionTriageService;

    public ExecutionController(ExecutionImportService executionImportService,
                               ExecutionQueryService executionQueryService,
                               ExecutionTriageService executionTriageService) {
        this.executionImportService = executionImportService;
        this.executionQueryService = executionQueryService;
        this.executionTriageService = executionTriageService;
    }

    /**
     * #37 POST /api/executions/import —— 导入执行结果文件。
     *
     * <p>{@code consumes} 必须显式声明 {@code multipart/form-data}：不声明时若客户端
     * 用 {@code application/json} 传 {@code file}，会自动落到难以理解的
     * "Content type not supported" 500，而不是 40001。</p>
     *
     * @param file   结果文件（{@code .xml} / {@code .csv}；必填）
     * @param taskId 结果对应的生成任务（必填，映射只在本任务用例范围内进行）
     * @param name   批次名称（可空；空时用文件名兜底）
     * @return {@code {runId, runNo, total, passed, failed, skipped, mappedCount}}
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "导入执行结果",
            description = "支持 JUnit XML 与 CSV；映射不到用例不阻断导入，mappedCount 如实返回")
    public ApiResponse<ExecutionImportResult> importResult(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "taskId") Long taskId,
            @RequestParam(name = "name", required = false) String name) {
        return ApiResponse.ok(executionImportService.importResult(taskId, name, file));
    }

    /**
     * #38 GET /api/executions —— 批次分页列表。
     *
     * @param sourceType 来源格式（{@code JUNIT_XML} / {@code CSV}；可空，脏值 40001）
     * @param keyword    关键词（匹配批次号 / 名称 / 文件名 / 关联任务号；可空）
     * @param query      分页参数（{@code page/size}）
     * @return 分页列表（按 {@code created_at} 倒序）
     */
    @GetMapping
    @Operation(summary = "执行批次列表",
            description = "按来源格式/关键词分页查询；返回 mappedRate（0~1 的比例）")
    public ApiResponse<PageResult<ExecutionRunVO>> list(
            @RequestParam(name = "sourceType", required = false) String sourceType,
            @RequestParam(name = "keyword", required = false) String keyword,
            PageQuery query) {
        return ApiResponse.ok(executionQueryService.page(sourceType, keyword, query));
    }

    /**
     * #39 GET /api/executions/{id} —— 批次详情。
     *
     * @param id 批次主键
     * @return 详情（{@code statusCounts} 三值恒出现；{@code categoryDistribution} 来自真实聚合）
     */
    @GetMapping("/{id}")
    @Operation(summary = "执行批次详情",
            description = "三值状态分布恒返回 PASSED/FAILED/SKIPPED（0 也保留）；分类分布按条数倒序")
    public ApiResponse<ExecutionRunDetailVO> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(executionQueryService.detail(id));
    }

    /**
     * #40 GET /api/executions/{id}/results —— 结果明细分页。
     *
     * @param id       批次主键
     * @param status   状态过滤（可空；脏值 40001 并列出可选值）
     * @param category 失败分类过滤（可空；不在字典里的码 40001）
     * @param query    分页参数
     * @return 分页明细（{@code stackTrace} 只含前 500 字，被截断时以省略号结尾）
     */
    @GetMapping("/{id}/results")
    @Operation(summary = "执行结果明细",
            description = "按 id 升序稳定排序；stackTrace 只返回前 500 字，完整堆栈留在库里")
    public ApiResponse<PageResult<ExecutionResultVO>> results(
            @PathVariable("id") Long id,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "category", required = false) String category,
            PageQuery query) {
        return ApiResponse.ok(executionQueryService.results(id, status, category, query));
    }

    /**
     * #41 PATCH /api/executions/results/{id} —— 人工纠正一条失败结果的归因分类。
     *
     * <p>返回<b>更新后的整行</b>（含新的 {@code failureLabel} 与 {@code triageSource=MANUAL}）：
     * 前端拿它就地替换表格那一行，不必再拉一次明细。返回 {@code 204} 或
     * {@code {updated:1}} 会让前端为了刷新一行而多一次分页请求，且中间会闪一次旧分类。</p>
     *
     * <p>{@code @RequestBody(required = false)}：完全空 body 时代码里给出 40001 的中文提示，
     * 而不是让 Spring 抛 {@code HttpMessageNotReadableException}（那条会被兜底成
     * 50000「系统内部错误」，用户看不懂）——虽然实际调用方总会带 body。</p>
     *
     * @param id   明细主键（{@code ExecutionResultVO.id}）
     * @param body {@code {failureCategory}}
     * @return 更新后的整行
     */
    @PatchMapping("/results/{id}")
    @Operation(summary = "人工纠正失败归因",
            description = "只有 FAILED 行可纠正；triageSource 强制为 MANUAL；返回更新后的整行")
    public ApiResponse<ExecutionResultVO> patchFailureCategory(
            @PathVariable("id") Long id,
            @RequestBody(required = false) ExecutionTriageReq body) {
        return ApiResponse.ok(executionTriageService.updateFailureCategory(
                id, body == null ? null : body.failureCategory()));
    }

    /**
     * #42 POST /api/executions/{id}/remap —— 重跑用例映射。
     *
     * <p>按批次所属任务的<b>当前</b> {@code junit_ref} 重算全部明细的 {@code case_id}，
     * 并同步 {@code execution_run.mapped_count}。<b>同步执行</b>（不是后台任务）：
     * 5000 条明细只有几条批量 UPDATE，前端需要在返回后立刻刷新明细列表，
     * 异步化只会让"点完没反应、过一会儿突然变了"。</p>
     *
     * @param id 批次主键
     * @return {@code {mappedCount, totalCount, mappedRate}}
     */
    @PostMapping("/{id}/remap")
    @Operation(summary = "重跑用例映射",
            description = "按任务当前 junit_ref 重算 case_id（会清掉失效的旧映射），不改动归因")
    public ApiResponse<ExecutionRemapResult> remap(@PathVariable("id") Long id) {
        return ApiResponse.ok(executionTriageService.remap(id));
    }
}
