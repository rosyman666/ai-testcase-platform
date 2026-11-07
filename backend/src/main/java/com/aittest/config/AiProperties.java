package com.aittest.config;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模型接入配置（{@code ai.*}，见 §8.7）。
 *
 * <p>密钥纪律：{@code apiKey} 只从环境变量 / {@code .env} 注入，yml 里写的是
 * {@code ${AI_API_KEY:}}（缺省空串，绝不写默认 Key）；{@code toString} 已排除该字段，
 * 防止某处误打日志把 Key 写进文件。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString(exclude = "apiKey")
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** 供应商：{@code mock} | {@code openai-compatible}。 */
    private String provider = "mock";

    /** OpenAI 兼容网关地址（provider=openai-compatible 时必填）。 */
    private String baseUrl = "";

    /** API Key（**禁止入库、禁止打印**）。 */
    private String apiKey = "";

    /** 模型名；默认 {@code mock-model}，与 {@code model_price} 种子行对齐才能算出成本。 */
    private String model = "mock-model";

    /** 单次调用超时（秒）。 */
    private int timeoutSeconds = 60;

    /** 结构化输出失败后的最大修复轮次（3 = 首次 + 2 次修复，见 §8.7）。 */
    private int maxRetry = 3;

    /** 各场景温度（生成要稳、修复要 0）。 */
    private Temperature temperature = new Temperature();

    /** 降级开关。 */
    private Fallback fallback = new Fallback();

    /**
     * 场景温度配置。
     */
    @Getter
    @Setter
    public static class Temperature {

        /** 测试点抽取温度（偏低：要稳定、可复现）。 */
        private double testpoint = 0.2;

        /** 用例生成温度（略高：要多样性）。 */
        private double generate = 0.4;

        /** 修复重试温度（0：只要正确 JSON）。 */
        private double repair = 0.0;
    }

    /**
     * 降级配置：**禁止**真实 provider 失败后自动降级到 mock（会污染评估数据）。
     */
    @Getter
    @Setter
    public static class Fallback {

        /** 是否允许降级到 mock，默认 false。 */
        private boolean enabled = false;
    }
}
