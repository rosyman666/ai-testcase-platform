package com.aittest.system;

import com.aittest.common.ApiResponse;
import com.aittest.system.dto.PublicConfigVO;
import com.aittest.system.service.ConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开配置接口（**鉴权白名单内**）。
 *
 * <p>登录页需要它渲染枚举下拉框与页头"当前模型模式"标识；因此它必须匿名可访问，
 * 且只返回非敏感信息（见 {@link PublicConfigVO} 的安全边界说明）。</p>
 *
 * @author 寇豆码
 */
@RestController
@RequestMapping("/api/config")
@Tag(name = "系统", description = "字典与公开配置")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * 公开配置：枚举字典 + 应用信息 + 限制值。
     *
     * @return 公开配置
     */
    @GetMapping("/public")
    @Operation(summary = "公开配置", description = "枚举字典、应用信息与只读限制值，不含任何密钥")
    public ApiResponse<PublicConfigVO> publicConfig() {
        return ApiResponse.ok(configService.publicConfig());
    }
}
