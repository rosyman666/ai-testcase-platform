package com.aittest.aicore.provider;

import com.aittest.aicore.LlmException;
import com.aittest.aicore.LlmProvider;
import com.aittest.config.AiProperties;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 供应商路由：按 {@code ai.provider} 选出唯一实现。
 *
 * <p><b>它是"选择"，不是"降级"</b>。这一点必须写在最显眼的位置，因为把两者混起来是
 * AI 项目最常见的静默故障源：真实 provider 挂了 → 自动切 mock → 页面全部正常 →
 * 演示很成功 → 但评估数据、成本台账、采纳率全是假的，而且<b>没有任何日志会红</b>。
 * 所以（§8.7 第 6 条）：{@code provider=mock} 只能<b>显式</b>指定，
 * 真实 provider 失败一律抛异常，绝不自动切 mock。</p>
 *
 * <p>{@code ai.fallback.enabled} 这个配置项因此<b>不参与路由</b>。留它的唯一作用是让
 * "想开降级的人"能被明确告知"本项目不支持"，而不是改完配置发现毫无变化、
 * 然后以为是自己改错了地方（见构造后的 {@code @PostConstruct} 告警）。</p>
 *
 * @author 寇豆码
 */
@Component
public class LlmProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderRouter.class);

    /** mock 实现。 */
    private final MockLlmProvider mockLlmProvider;

    /** openai-compatible 实现。 */
    private final OpenAiCompatibleProvider openAiCompatibleProvider;

    /** 配置（只读 provider 名）。 */
    private final AiProperties aiProperties;

    /** 别名 → 规范名：容忍写法差异，但**不猜**语义。 */
    private final Map<String, String> aliases = new LinkedHashMap<>();

    public LlmProviderRouter(MockLlmProvider mockLlmProvider,
                             OpenAiCompatibleProvider openAiCompatibleProvider,
                             AiProperties aiProperties) {
        this.mockLlmProvider = mockLlmProvider;
        this.openAiCompatibleProvider = openAiCompatibleProvider;
        this.aiProperties = aiProperties;
        aliases.put("mock", MockLlmProvider.NAME);
        aliases.put("openai-compatible", OpenAiCompatibleProvider.NAME);
        aliases.put("openai_compatible", OpenAiCompatibleProvider.NAME);
        aliases.put("openai", OpenAiCompatibleProvider.NAME);
        aliases.put("deepseek", OpenAiCompatibleProvider.NAME);
    }

    /**
     * 启动时把"降级开关被打开"这件事说出来。
     *
     * <p>不抛异常、不阻断启动：它不影响正确性（路由压根不看这个开关），
     * 但必须让人知道"改了也没用"，否则下次会有人以为是配置没生效。</p>
     */
    @PostConstruct
    public void warnOnUnsupportedFallback() {
        if (aiProperties.getFallback() != null && aiProperties.getFallback().isEnabled()) {
            log.warn("[LLM] ai.fallback.enabled=true 已被忽略：本项目禁止真实 provider 失败后降级到 mock"
                    + "（§8.7 第 6 条，会静默污染评估与成本数据）。当前生效供应商={}", providerName());
        }
    }

    /**
     * 当前生效的供应商规范名。
     *
     * @return {@code mock} 或 {@code openai-compatible}
     */
    public String providerName() {
        String configured = aiProperties.getProvider();
        String normalized = configured == null ? "" : configured.trim().toLowerCase();
        String resolved = aliases.get(normalized);
        if (resolved == null) {
            throw LlmException.misconfigured("未知的 ai.provider=" + configured
                    + "，可选值：mock、openai-compatible");
        }
        return resolved;
    }

    /**
     * 按配置解析出实现。
     *
     * @return 供应商实现
     */
    public LlmProvider resolve() {
        return resolve(providerName());
    }

    /**
     * 按名字解析实现（显式指定供应商时用）。
     *
     * @param providerName 供应商名（支持别名）
     * @return 供应商实现
     */
    public LlmProvider resolve(String providerName) {
        String key = providerName == null ? "" : providerName.trim().toLowerCase();
        String canonical = aliases.get(key);
        if (canonical == null) {
            throw LlmException.misconfigured("未知的模型供应商：" + providerName
                    + "，可选值：mock、openai-compatible");
        }
        return switch (canonical) {
            case MockLlmProvider.NAME -> mockLlmProvider;
            case OpenAiCompatibleProvider.NAME -> openAiCompatibleProvider;
            // 别名表与实现一一对应，走到这里说明加了别名却没加分支
            default -> throw new IllegalStateException("供应商别名未映射到实现：" + canonical);
        };
    }
}
