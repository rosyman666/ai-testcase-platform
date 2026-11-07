package com.aittest.system;

import com.aittest.common.ApiResponse;
import com.aittest.common.CodeLabel;
import com.aittest.system.service.DictService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 字典接口（**鉴权白名单内**，见 {@code WebMvcConfig#AUTH_WHITELIST}）。
 *
 * <p>为什么必须白名单：登录页就要渲染"驳回原因/失败分类"以外的部分字典，
 * 如果字典接口需要 token，会形成 401 → 跳登录页 → 登录页再取字典 → 又 401 的死锁。</p>
 *
 * <p>三个端点：</p>
 * <ul>
 *   <li>{@code GET /api/dict/reject-reasons}——驳回原因（数据库字典）；</li>
 *   <li>{@code GET /api/dict/failure-categories}——失败分类（数据库字典）；</li>
 *   <li>{@code GET /api/dict/{type}}——通用入口，覆盖上面两个 + 全部代码枚举
 *       （{@code doc-types}/{@code case-types}/{@code priorities}/{@code task-statuses} 等）。</li>
 * </ul>
 *
 * @author 寇豆码
 */
@RestController
@RequestMapping("/api/dict")
@Tag(name = "系统", description = "字典与公开配置")
public class DictController {

    /** 默认只返回启用项。 */
    private static final boolean DEFAULT_ENABLED_ONLY = true;

    private final DictService dictService;

    public DictController(DictService dictService) {
        this.dictService = dictService;
    }

    /**
     * 驳回原因字典。
     *
     * @param enabledOnly 是否只返回启用项，默认 true
     * @return 字典项
     */
    @GetMapping("/reject-reasons")
    @Operation(summary = "驳回原因字典", description = "统计口径按 code 聚合，label 仅展示")
    public ApiResponse<List<CodeLabel>> rejectReasons(
            @RequestParam(name = "enabledOnly", required = false, defaultValue = "true") boolean enabledOnly) {
        return ApiResponse.ok(dictService.rejectReasons(enabledOnly));
    }

    /**
     * 失败分类字典。
     *
     * @param enabledOnly 是否只返回启用项，默认 true
     * @return 字典项
     */
    @GetMapping("/failure-categories")
    @Operation(summary = "失败分类字典")
    public ApiResponse<List<CodeLabel>> failureCategories(
            @RequestParam(name = "enabledOnly", required = false, defaultValue = "true") boolean enabledOnly) {
        return ApiResponse.ok(dictService.failureCategories(enabledOnly));
    }

    /**
     * 通用字典入口（数据库字典 + 代码枚举）。
     *
     * @param type        字典类型，如 {@code case-types}
     * @param enabledOnly 是否只返回启用项（仅对数据库字典生效）
     * @return 字典项
     */
    @GetMapping("/{type}")
    @Operation(summary = "通用字典", description = "type 支持中划线/下划线/大写三种写法")
    public ApiResponse<List<CodeLabel>> byType(
            @PathVariable("type") String type,
            @RequestParam(name = "enabledOnly", required = false, defaultValue = "" + DEFAULT_ENABLED_ONLY)
            boolean enabledOnly) {
        return ApiResponse.ok(dictService.byType(type, enabledOnly));
    }
}
