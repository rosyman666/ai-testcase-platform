package com.aittest.doc.controller;

import com.aittest.common.ApiResponse;
import com.aittest.common.PageQuery;
import com.aittest.common.PageResult;
import com.aittest.doc.dto.ApiOperationVO;
import com.aittest.doc.dto.DocDetailVO;
import com.aittest.doc.dto.DocSectionVO;
import com.aittest.doc.dto.DocSourceVO;
import com.aittest.doc.service.DocService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档接口（§4.2 第 7~13 号端点）。
 *
 * <p><b>本控制器里两种失败形态并存，不是不一致</b>（Q3 裁决，别"顺手统一"）：</p>
 * <ul>
 *   <li>{@code POST /api/docs} 解析失败仍返回 {@code code=0}，失败信息在
 *       {@code data.parseStatus=FAILED} + {@code data.parseError} 里。理由：这个请求的目的是
 *       "接收并保存文件"，文件已落盘、记录已建、解析也尝试过了，**请求本身是成功的**，
 *       解析失败是这份文档的状态。前端据此显示红标，而不是弹错误 toast。</li>
 *   <li>{@code POST /api/docs/{id}/reparse} 解析失败返回 {@code code=42001}。理由：这个请求的
 *       目的**就是**"再解析一次"，失败即请求失败。{@code 42001} 的唯一归属端点就是它——
 *       在 T03 之前这个码是个悬空码。</li>
 *   <li>{@code POST /api/docs} 遇到非白名单扩展名返回 {@code code=42002}：那种情况连记录都不建，
 *       没有 {@code data} 可回。</li>
 * </ul>
 *
 * @author 寇豆码
 */
@RestController
@RequestMapping("/api/docs")
@Tag(name = "文档", description = "需求文档上传、解析与查询")
public class DocController {

    private final DocService docService;

    public DocController(DocService docService) {
        this.docService = docService;
    }

    /**
     * 上传文档（multipart）。
     *
     * @param file 文件（{@code md/txt/docx/json}，≤20MB，由 {@code spring.servlet.multipart} 限制）
     * @return 文档 ID、单号、解析状态与统计
     */
    @PostMapping(consumes = "multipart/form-data")
    @Operation(summary = "上传并解析文档",
            description = "扩展名不在白名单返回 42002；解析失败返回 code=0 且 parseStatus=FAILED")
    public ApiResponse<DocService.UploadResult> upload(@RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(docService.upload(file));
    }

    /**
     * 文档列表。
     *
     * @param query       分页与排序（{@code page/size/sort}）
     * @param keyword     文件名或单号模糊搜索
     * @param docType     文档类型过滤
     * @param parseStatus 解析状态过滤
     * @return 分页列表
     */
    @GetMapping
    @Operation(summary = "文档列表")
    public ApiResponse<PageResult<DocSourceVO>> list(
            PageQuery query,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "docType", required = false) String docType,
            @RequestParam(name = "parseStatus", required = false) String parseStatus) {
        return ApiResponse.ok(docService.list(query, keyword, docType, parseStatus));
    }

    /**
     * 文档详情（内嵌章节树与接口列表，各取前 200 条）。
     *
     * @param id 文档 ID
     * @return 详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "文档详情")
    public ApiResponse<DocDetailVO> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(docService.detail(id));
    }

    /**
     * 章节分页（正文全文）。
     *
     * @param id    文档 ID
     * @param query 分页
     * @return 分页章节
     */
    @GetMapping("/{id}/sections")
    @Operation(summary = "章节列表")
    public ApiResponse<PageResult<DocSectionVO>> sections(@PathVariable("id") Long id, PageQuery query) {
        return ApiResponse.ok(docService.sections(id, query));
    }

    /**
     * 接口操作分页（**不含** schema 三兄弟，见 §4.4）。
     *
     * @param id    文档 ID
     * @param query 分页
     * @return 分页接口列表
     */
    @GetMapping("/{id}/operations")
    @Operation(summary = "接口列表")
    public ApiResponse<PageResult<ApiOperationVO>> operations(@PathVariable("id") Long id, PageQuery query) {
        return ApiResponse.ok(docService.operations(id, query));
    }

    /**
     * 重新解析（失败返回 42001）。
     *
     * @param id 文档 ID
     * @return 解析结果
     */
    @PostMapping("/{id}/reparse")
    @Operation(summary = "重新解析", description = "用户主动重解析失败即请求失败，返回 42001")
    public ApiResponse<DocService.UploadResult> reparse(@PathVariable("id") Long id) {
        return ApiResponse.ok(docService.reparse(id));
    }

    /**
     * 删除文档（默认软删，可被"重传同一文件"恢复）。
     *
     * @param id    文档 ID
     * @param force 是否物理删除（级联清理章节与接口，且会丢单号）
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除文档", description = "默认软删；force=true 物理删除且单号可能被复用")
    public ApiResponse<Void> delete(@PathVariable("id") Long id,
            @RequestParam(name = "force", required = false, defaultValue = "false") boolean force) {
        docService.delete(id, force);
        return ApiResponse.ok();
    }
}
