package com.aittest.aicore.log;

import com.aittest.aicore.LlmUsage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 单价缓存 + 成本计算：把 {@code token 数} 换算成 {@code cost_micro}。
 *
 * <p><b>为什么缓存</b>：记账是<b>每次</b>模型调用都要做的事，而单价表一天可能只变一次。
 * 每次调用都查一次库，等于把"AI 调用"这条链路的 QPS 直接加在单价表上，并且会在
 * 同一个事务/线程里多占一次连接。TTL 5 分钟（与 §2.2 给 `ModelPriceService` 定的口径一致）
 * 是"调价后最迟 5 分钟生效"与"几乎不查库"之间的折中。</p>
 *
 * <p><b>三条失败方向都是"不阻断"</b>（记账必须成功，否则成本会静默丢失）：</p>
 * <ol>
 *   <li>查库失败（库挂了/超时）→ 成本记 0 并 WARN，<b>不抛异常</b>。台账那一行仍要写进去——
 *       它至少还有 token 数与耗时，事后可以用历史单价重算；</li>
 *   <li>模型没有单价行 → 成本记 0，并对**每个模型只 WARN 一次**。若每次调用都 WARN，
 *       日志会被刷满，而真正要看的那条反而被埋掉；</li>
 *   <li>单价为 0（如 {@code mock-model} 的 0/0）→ 成本 0，<b>不 WARN</b>。这是正常配置：
 *       mock 不该产生成本，若它也告警，告警就没有意义了。</li>
 * </ol>
 *
 * @author 寇豆码
 */
@Component
public class ModelPriceCache {

    private static final Logger log = LoggerFactory.getLogger(ModelPriceCache.class);

    /** 缓存有效期（毫秒）。 */
    private static final long TTL_MS = 5 * 60 * 1000L;

    /** 单价的分母：每 1M token 的微元数（§8.3）。 */
    private static final long PRICE_PER_UNIT_TOKENS = 1_000_000L;

    /** 单价读取（只读）。 */
    private final ModelPriceMapper modelPriceMapper;

    /** 当前缓存：model → 最新单价。 */
    private volatile Map<String, ModelPrice> cache = Map.of();

    /** 上次加载时间戳（毫秒）。{@code 0} 表示尚未加载。 */
    private volatile long loadedAt = 0L;

    /** 已经告警过"无单价"的模型集合，保证每个模型只 WARN 一次。 */
    private final Set<String> warnedModels = ConcurrentHashMap.newKeySet();

    public ModelPriceCache(ModelPriceMapper modelPriceMapper) {
        this.modelPriceMapper = modelPriceMapper;
    }

    /**
     * 计算一次调用的成本（微元）。
     *
     * @param model 模型名（空则直接返回 0）
     * @param usage token 用量（null 或全零则返回 0）
     * @return 成本（微元，四舍五入到整数）
     */
    public long costMicro(String model, LlmUsage usage) {
        if (model == null || model.isBlank() || usage == null || usage.totalTokens() == 0) {
            return 0L;
        }
        ModelPrice price = priceOf(model);
        if (price == null) {
            return 0L;
        }
        long input = price.getInputPriceMicro() == null ? 0L : price.getInputPriceMicro();
        long output = price.getOutputPriceMicro() == null ? 0L : price.getOutputPriceMicro();
        long numerator = (long) usage.promptTokens() * input + (long) usage.completionTokens() * output;
        // 四舍五入而不是直接截断：小额调用（几百 token）截断后会恒为 0，成本看着像"没花钱"
        return Math.round((double) numerator / PRICE_PER_UNIT_TOKENS);
    }

    /**
     * 取某个模型的最新单价（带 TTL 缓存）。
     *
     * @param model 模型名
     * @return 单价；查不到（或查库失败）返回 {@code null}。**单价为 0/0 时正常返回对象**——
     *         那是合法配置（mock），不是缺失
     */
    public ModelPrice priceOf(String model) {
        Map<String, ModelPrice> snapshot = snapshot();
        ModelPrice price = snapshot.get(model);
        if (price == null) {
            if (warnedModels.add(model)) {
                log.warn("[LLM-COST] model_price 中没有模型 {} 的单价，成本将记 0；"
                        + "请在 model_price 补一行（同一模型只告警一次）", model);
            }
            return null;
        }
        return price;
    }

    /**
     * 清空缓存（调价后立即生效、测试隔离用）。
     */
    public void invalidate() {
        loadedAt = 0L;
        cache = Map.of();
    }

    /**
     * 取单价快照，必要时重载。
     *
     * @return 不可变映射
     */
    private Map<String, ModelPrice> snapshot() {
        long now = System.currentTimeMillis();
        if (loadedAt != 0L && now - loadedAt < TTL_MS) {
            return cache;
        }
        synchronized (this) {
            if (loadedAt != 0L && System.currentTimeMillis() - loadedAt < TTL_MS) {
                return cache;
            }
            try {
                List<ModelPrice> rows = modelPriceMapper.selectEnabledOrderByEffectiveFromDesc();
                // 已按 model, effective_from DESC 排好序：每个 model 第一次出现的就是最新价
                Map<String, ModelPrice> loaded = new HashMap<>(rows.size());
                for (ModelPrice row : rows) {
                    if (row.getModel() != null) {
                        loaded.putIfAbsent(row.getModel(), row);
                    }
                }
                cache = Map.copyOf(loaded);
            } catch (RuntimeException ex) {
                // 查库失败不能阻断记账：保留旧缓存（可能是空），本次成本按 0 记
                log.warn("[LLM-COST] 读取 model_price 失败，本次成本按 0 记（台账照写）：{}", ex.getMessage());
            }
            // 无论成功失败都刷新时间戳：失败时避免每次调用都去撞一次已经挂掉的库
            loadedAt = System.currentTimeMillis();
            return cache;
        }
    }
}
