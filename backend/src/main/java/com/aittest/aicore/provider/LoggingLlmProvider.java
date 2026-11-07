package com.aittest.aicore.provider;

import com.aittest.aicore.LlmException;
import com.aittest.aicore.LlmProvider;
import com.aittest.aicore.LlmRequest;
import com.aittest.aicore.LlmResponse;
import com.aittest.aicore.LlmUsage;
import com.aittest.aicore.log.LlmCallLog;
import com.aittest.aicore.log.LlmCallLogMapper;
import com.aittest.aicore.log.ModelPriceCache;
import com.aittest.common.ErrorCode;
import com.aittest.config.AiProperties;
import com.aittest.logging.TraceFilter;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 记账装饰器：<b>所有</b>模型调用都必须穿过它。
 *
 * <p>它自己不调模型，只做四件事：计时 → 转发 → 落 1 条 {@code llm_call_log} → 原样返回/抛出。</p>
 *
 * <p><b>为什么是装饰器，而不是让每个调用点自己写日志</b>（§11.1 D-10）：调用点有四个以上
 * （抽点、生成、修复、归因），每条路径又分成功/失败两种，一共 8 条分支。
 * 只要有一条忘了写，成本就少一块、质量指标就偏一格，而且<b>没有任何测试会红</b>——
 * 少一行账不会让功能坏掉，只会让看板上的数字慢慢变小。装饰器把这个乘法变成加法：
 * 只要调用点走的是它，就不可能漏。</p>
 *
 * <p><b>记账三条纪律</b>：</p>
 * <ol>
 *   <li><b>失败也记</b>：上游 5xx 时 token 往往已经消耗（网关先扣费再转发），
 *       只记成功会让成本系统性偏低，而偏低的那部分恰好是最贵的失败重试（§8.7 第 5 条）；</li>
 *   <li><b>追加不覆盖</b>：同一 item 的 3 次重试落 3 行，{@code attempt_no}=1/2/3。
 *       覆盖式记账会让"首次通过率"永远 100%（T04 ⑧）；</li>
 *   <li><b>记账失败不能影响业务</b>：写台账抛异常只打 error 日志，<b>不改变</b>本次调用的结果。
 *       反过来（让记账失败把成功的调用变成失败）等于"账本坏了业务就不能用"，代价不对等。</li>
 * </ol>
 *
 * <p><b>不实现 {@link LlmProvider} 是刻意的</b>：它不是一个"可选供应商"，而是记账旁路。
 * 若它也实现该接口，别人就可能把它当供应商 resolve 出来，形成"装饰器包装饰器"，
 * 一次调用落 2 条台账——而这种重复记账在指标上表现为"成本翻倍但调用次数正常"，
 * 极难被发现。</p>
 *
 * @author 寇豆码
 */
@Component
public class LoggingLlmProvider {

    private static final Logger log = LoggerFactory.getLogger(LoggingLlmProvider.class);

    /** 台账列宽上限（与 {@code V1__schema.sql} 一致）：超出就是 1406，而记账绝不能因超长失败。 */
    private static final int TRACE_ID_MAX = 64;

    private static final int SCENE_MAX = 32;

    private static final int PROVIDER_MAX = 32;

    private static final int MODEL_MAX = 64;

    private static final int ERROR_CODE_MAX = 32;

    /** 供应商选择。 */
    private final LlmProviderRouter router;

    /** 台账写入。 */
    private final LlmCallLogMapper llmCallLogMapper;

    /** 单价缓存（算成本）。 */
    private final ModelPriceCache modelPriceCache;

    /** 模型接入配置（失败时台账里要记配置的模型名）。 */
    private final AiProperties aiProperties;

    public LoggingLlmProvider(LlmProviderRouter router,
                              LlmCallLogMapper llmCallLogMapper,
                              ModelPriceCache modelPriceCache,
                              AiProperties aiProperties) {
        this.router = router;
        this.llmCallLogMapper = llmCallLogMapper;
        this.modelPriceCache = modelPriceCache;
        this.aiProperties = aiProperties;
    }

    /**
     * 发起一次带记账的调用。
     *
     * @param request 调用入参
     * @return 模型响应
     * @throws LlmException 由底层 provider 抛出，原样透传（<b>不包装</b>：
     *                      包装会丢掉 {@code retryable} 标记，编排层就无法决定要不要重试）
     */
    public LlmResponse chat(LlmRequest request) {
        String provider = resolveProviderName();
        long startNanos = System.nanoTime();
        try {
            LlmResponse response = router.resolve(provider).chat(request);
            long latencyMs = elapsedMs(startNanos);
            record(request, provider, response, null, latencyMs);
            return response;
        } catch (RuntimeException ex) {
            long latencyMs = elapsedMs(startNanos);
            record(request, provider, null, ex, latencyMs);
            throw ex;
        } catch (Error error) {
            // OOM / StackOverflow：仍然记一行，否则"崩掉的那次调用"在账本里完全不存在
            long latencyMs = elapsedMs(startNanos);
            record(request, provider, null, error, latencyMs);
            throw error;
        }
    }

    /**
     * 解析本次调用走哪个供应商。
     *
     * <p>取不到（配置写错）时<b>不抛</b>，返回 {@code unknown}：这样"配置错了"也能落一条
     * FAILED 台账，而不是连账都没有——配置错误恰恰是最需要留痕的一类故障。</p>
     *
     * @return 供应商名
     */
    private String resolveProviderName() {
        try {
            return router.providerName();
        } catch (RuntimeException ex) {
            return "unknown";
        }
    }

    /**
     * 落一条台账。
     *
     * @param request   调用入参
     * @param provider  供应商名
     * @param response  成功响应（失败时为 {@code null}）
     * @param error     异常（成功时为 {@code null}）
     * @param latencyMs 耗时
     */
    private void record(LlmRequest request, String provider, LlmResponse response, Throwable error, long latencyMs) {
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                // 事务内记账 = 事务回滚时账也回滚，而模型调用**已经发生并计费**了。
                // §8.4 明确禁止在事务内调模型；这里把它变成日志里能看见的告警。
                log.warn("[LLM-LOG] 检测到模型调用发生在事务内：回滚会导致台账丢失，"
                        + "请把 LLM 调用移出 @Transactional（§8.4）");
            }
            LlmUsage usage = response == null ? LlmUsage.EMPTY : response.getUsage();
            String model = response == null ? configuredModel() : response.getModel();

            LlmCallLog row = new LlmCallLog();
            // 主键必须在这里显式生成：写入走的是手写 XML（不用 BaseMapper.insert 的雪花填充）
            row.setId(IdWorker.getId());
            row.setTaskId(request.getTaskId());
            row.setTaskItemId(request.getTaskItemId());
            row.setTraceId(truncate(resolveTraceId(request), TRACE_ID_MAX));
            row.setScene(truncate(request.getScene(), SCENE_MAX));
            row.setProvider(truncate(provider, PROVIDER_MAX));
            row.setModel(truncate(model, MODEL_MAX));
            row.setPromptTokens(usage.promptTokens());
            row.setCompletionTokens(usage.completionTokens());
            row.setCostMicro(modelPriceCache.costMicro(model, usage));
            row.setLatencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE));
            // 至少为 1：0 会让"平均重试次数"这类指标的除法出现 0 分母
            row.setAttemptNo(Math.max(1, request.getAttemptNo()));
            row.setStatus(error == null ? "SUCCESS" : "FAILED");
            row.setErrorCode(error == null ? "" : truncate(errorCodeOf(error), ERROR_CODE_MAX));
            row.setDegraded(request.isDegraded() || (response != null && response.isDegraded()) ? 1 : 0);

            llmCallLogMapper.append(row);
        } catch (RuntimeException | Error recordFailure) {
            // 记账失败绝不能改变业务结果：原异常继续往上抛，这里只留痕
            log.error("[LLM-LOG] 写台账失败（不影响本次调用结果），provider={} error={}",
                    provider, error == null ? "" : error.toString(), recordFailure);
        }
    }

    /**
     * 取 traceId：优先用请求上带的，其次从 MDC 取（同一请求内二者应当相同）。
     *
     * @param request 调用入参
     * @return 非空 traceId（取不到时为空串，与 DDL 默认值一致）
     */
    private String resolveTraceId(LlmRequest request) {
        if (request.getTraceId() != null && !request.getTraceId().isBlank()) {
            return request.getTraceId();
        }
        String fromMdc = MDC.get(TraceFilter.MDC_TRACE_ID);
        return fromMdc == null ? "" : fromMdc;
    }

    /**
     * 异常 → 台账错误码。
     *
     * <p>存的是枚举 <b>name</b>（如 {@code E_LLM_TIMEOUT}）而不是数字码：台账是给人查的，
     * 而 {@code 50002} 离开 §8.1 的表格就看不懂；指标里 {@code error_code like 'E_LLM_PARSE%'}
     * 也是按名字匹配（§8.3 解析失败率）。</p>
     *
     * @param error 异常
     * @return 错误码名
     */
    private String errorCodeOf(Throwable error) {
        if (error instanceof LlmException llmException) {
            return llmException.getErrorCode().name();
        }
        if (error instanceof Error) {
            return "E_INTERNAL";
        }
        return ErrorCode.E_INTERNAL.name();
    }

    /**
     * 当前配置的模型名（失败时没有响应可读，只能取配置值）。
     *
     * <p>读配置而不是读 {@code LlmResponse}：失败时根本没有响应对象。
     * 也不要在这里"猜"（比如看 provider 名硬编码 mock-model）——猜出来的模型名会让
     * 成本归集把失败调用算到别的模型头上。</p>
     *
     * @return 模型名（配置为空时返回 {@code unknown}，避免 {@code ''} 与单价表关联不上）
     */
    private String configuredModel() {
        String configured = aiProperties.getModel();
        return configured == null || configured.isBlank() ? "unknown" : configured.trim();
    }

    /**
     * 计算耗时（毫秒）。
     *
     * @param startNanos 起始纳秒
     * @return 毫秒数（至少 0）
     */
    private long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    /**
     * 按列宽截断（null 记为同列默认值所需空串）。
     *
     * @param value     原值
     * @param maxLength 列宽（字符数）
     * @return 截断后的值
     */
    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
