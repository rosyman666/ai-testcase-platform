package com.aittest.aicore.prompt;

import com.aittest.aicore.prompt.dto.PromptPreviewReq;
import com.aittest.aicore.prompt.dto.PromptPreviewVO;
import com.aittest.aicore.prompt.dto.PromptTemplateCreateReq;
import com.aittest.aicore.prompt.dto.PromptTemplateDetailVO;
import com.aittest.aicore.prompt.dto.PromptTemplateVO;
import com.aittest.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提示词模板接口（§4.2 第 19~22 号端点 + 试跑端点，见 §11.1 D-24）。
 *
 * <p><b>为什么 §6 的 T04 文件清单里没有这个控制器</b>：那是设计阶段的漏项——
 * §4.2 明确了 4 个模板端点和前端页面，却没把控制器写进任何一个任务的交付清单。
 * 由 T04 补齐（它有模板 service、也有前端页面，缺控制器页面根本调不通），
 * 并登记在 D-24。</p>
 *
 * <p><b>口径</b>：{@code PUT .../activate} 用 PUT 而不是 POST，因为它是<b>幂等</b>的——
 * 同一个版本激活两次，结果一样。用 POST 会让前端在"重复点击/重试"时反复触发事务。</p>
 *
 * @author 寇豆码
 */
@RestController
@RequestMapping("/api/prompt-templates")
@Tag(name = "提示词模板", description = "模板版本管理与试跑")
public class PromptTemplateController {

    private final PromptTemplateService promptTemplateService;

    public PromptTemplateController(PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
    }

    /**
     * 模板列表（不含正文）。
     *
     * @param scene      场景过滤
     * @param activeOnly 是否只看生效版本
     * @return 模板列表
     */
    @GetMapping
    @Operation(summary = "模板列表", description = "按 code/version 排序，不含模板正文")
    public ApiResponse<List<PromptTemplateVO>> list(
            @RequestParam(required = false) String scene,
            @RequestParam(defaultValue = "false") boolean activeOnly) {
        return ApiResponse.ok(promptTemplateService.list(scene, activeOnly));
    }

    /**
     * 模板详情（含正文）。
     *
     * @param id 主键
     * @return 详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "模板详情")
    public ApiResponse<PromptTemplateDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(promptTemplateService.detail(id));
    }

    /**
     * 新建版本（同 code 版本号自增并立即生效）。
     *
     * @param req 入参
     * @return 新版本详情
     */
    @PostMapping
    @Operation(summary = "新建模板版本",
            description = "同 code 自动 version+1 并生效；旧版本自动失效")
    public ApiResponse<PromptTemplateDetailVO> create(@Valid @RequestBody PromptTemplateCreateReq req) {
        return ApiResponse.ok(promptTemplateService.createVersion(req));
    }

    /**
     * 激活指定版本。
     *
     * @param id 主键
     * @return 激活后的详情
     */
    @PutMapping("/{id}/activate")
    @Operation(summary = "激活模板版本", description = "同 code 其它版本自动失效（幂等）")
    public ApiResponse<PromptTemplateDetailVO> activate(@PathVariable Long id) {
        return ApiResponse.ok(promptTemplateService.activate(id));
    }

    /**
     * 试跑：渲染模板并真实调用模型（走记账装饰器，落 1 条 {@code llm_call_log}）。
     *
     * @param id  主键
     * @param req 入参（变量表、尝试序号、是否校验 schema）
     * @return 输出与质量信号
     */
    @PostMapping("/{id}/preview")
    @Operation(summary = "模板试跑",
            description = "真实调用模型并记账；返回原始输出、token、耗时、是否被修复、是否通过 schema 校验")
    public ApiResponse<PromptPreviewVO> preview(@PathVariable Long id,
                                                @RequestBody(required = false) PromptPreviewReq req) {
        PromptPreviewReq safe = req == null ? new PromptPreviewReq(null, 1, true) : req;
        return ApiResponse.ok(promptTemplateService.preview(id, safe));
    }
}
