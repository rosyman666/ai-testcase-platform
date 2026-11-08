package com.aittest.testcase.controller;

import com.aittest.common.ApiResponse;
import com.aittest.common.PageQuery;
import com.aittest.common.PageResult;
import com.aittest.testcase.dto.BatchReviewReq;
import com.aittest.testcase.dto.BatchReviewResult;
import com.aittest.testcase.dto.CaseUpdateReq;
import com.aittest.testcase.dto.ReviewReq;
import com.aittest.testcase.dto.TestCaseVO;
import com.aittest.testcase.service.CaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用例评审接口（§6 T08）。
 *
 * <p><b>五个端点，对应评审闭环的五个动作</b>：看列表 → 看详情 → 改内容 → 单条评 → 批量评。</p>
 *
 * <pre>
 * GET  /api/cases?taskId=&amp;status=&amp;keyword=&amp;page=&amp;size=  分页列表（{@code PageResult<TestCaseVO>}）
 * GET  /api/cases/{id}                                    详情（含 {@code reviews} 历史）
 * PUT  /api/cases/{id}                                    编辑后采纳（写前后快照）
 * POST /api/cases/{id}/review                             单条采纳 / 驳回
 * POST /api/cases/batch-review                            批量采纳 / 驳回
 * </pre>
 *
 * <p><b>为什么编辑不是 {@code POST /{id}/review} 的一个分支</b>：评审请求里没有内容字段，
 * 凭空写一条"编辑"只会得到 {@code old_content == new_content} 的快照。编辑必须走 PUT，
 * 由它带内容、带快照、带状态变更（见 {@code ReviewAction} 类注释）。</p>
 *
 * <p><b>为什么批量评审是独立端点而不是循环调单条</b>：单条接口"写一行台账 + 改一次状态"
 * 各自一个事务，20 条就是 20 个事务；中途失败会留下"前 12 条已评、后 8 条没评"的半成品，
 * 而用户看到的只是一个报错。批量端点是一个事务 + 一份 {@code skipped} 明细，
 * 用户能明确知道哪些没成功、为什么。</p>
 *
 * <p><b>异常风格与 {@code TestPointController} 一致</b>：不自己造 HTTP 400/404，
 * 一律抛 {@code BizException}，由 {@code GlobalExceptionHandler} 映射成
 * HTTP 200 + 业务码（{@code 40001/40002/40401}）。前端只按 {@code code} 分支。</p>
 *
 * @author 寇豆码
 */
@RestController
@RequestMapping("/api/cases")
@Tag(name = "用例评审", description = "用例查询、编辑、采纳/驳回与批量评审（标注数据入口）")
public class CaseController {

    /** 用例服务（查询 + 写操作转发）。 */
    private final CaseService caseService;

    public CaseController(CaseService caseService) {
        this.caseService = caseService;
    }

    /**
     * 用例分页列表。
     *
     * @param taskId  所属任务（可空：不按任务过滤）
     * @param status  状态（可空：全部；脏值返回 40001）
     * @param keyword 关键词（匹配标题或预期结果）
     * @param query   分页与排序（{@code page/size/sort}）
     * @return 分页列表（{@code steps} 为对象数组）
     */
    @GetMapping
    @Operation(summary = "用例列表",
            description = "按任务/状态/关键词分页查询；steps 已解析成 [{no,action,data}]，列表不返回评审历史")
    public ApiResponse<PageResult<TestCaseVO>> list(
            @RequestParam(name = "taskId", required = false) Long taskId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "keyword", required = false) String keyword,
            PageQuery query) {
        return ApiResponse.ok(caseService.page(taskId, status, keyword, query));
    }

    /**
     * 用例详情（含评审历史）。
     *
     * @param id 主键
     * @return 详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "用例详情", description = "含 reviews 评审历史，按 created_at 升序")
    public ApiResponse<TestCaseVO> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(caseService.get(id));
    }

    /**
     * 编辑后采纳。
     *
     * @param id  主键
     * @param req 可编辑字段（{@code null} = 不改）
     * @return 更新后的详情
     */
    @PutMapping("/{id}")
    @Operation(summary = "编辑用例",
            description = "写 old_content/new_content 快照并把状态置 EDITED；不可改 id/seq_no/status 等字段")
    public ApiResponse<TestCaseVO> update(@PathVariable("id") Long id,
            @Valid @RequestBody CaseUpdateReq req) {
        return ApiResponse.ok(caseService.update(id, req));
    }

    /**
     * 单条评审（采纳 / 驳回）。
     *
     * @param id  主键
     * @param req 评审入参
     * @return 评审后的详情
     */
    @PostMapping("/{id}/review")
    @Operation(summary = "单条评审",
            description = "只接受 ACCEPT / REJECT；驳回必须带 rejectReasonCode；允许改主意重复评审")
    public ApiResponse<TestCaseVO> review(@PathVariable("id") Long id,
            @Valid @RequestBody ReviewReq req) {
        return ApiResponse.ok(caseService.review(id, req));
    }

    /**
     * 批量评审。
     *
     * @param req 批量入参
     * @return {@code {updated, skipped:[{id, reason}]}}
     */
    @PostMapping("/batch-review")
    @Operation(summary = "批量评审",
            description = "只处理 DRAFT 用例；非 DRAFT 计入 skipped 并带原因，返回 {updated, skipped}")
    public ApiResponse<BatchReviewResult> batchReview(@Valid @RequestBody BatchReviewReq req) {
        return ApiResponse.ok(caseService.batchReview(req));
    }
}
