package com.aittest.aicore.provider;

import com.aittest.aicore.LlmException;
import com.aittest.aicore.LlmMessage;
import com.aittest.aicore.LlmProvider;
import com.aittest.aicore.LlmRequest;
import com.aittest.aicore.LlmResponse;
import com.aittest.aicore.LlmUsage;
import com.aittest.common.ErrorCode;
import com.aittest.common.JsonUtils;
import com.aittest.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容供应商：{@code POST {base_url}/chat/completions}。
 *
 * <p><b>"代码零写死"的边界在哪</b>（T04 ②）：{@code base_url} / {@code api_key} / {@code model}
 * 三样必须全部来自配置，代码里<b>不出现任何默认值</b>。理由不是洁癖——{@code api_key} 一旦有默认值，
 * 它就会跟着代码进仓库、进镜像、进日志；而 {@code base_url} 写死会让"换一家兼容网关"变成改代码。
 * 注意 {@code api_key} 的取法也要支持 {@code ${AI_API_KEY:}} 占位符（§8.8），
 * 也就是"环境变量 → .env → yml 默认值"这条链路由 Spring 负责，本类只读 {@link AiProperties#getApiKey()}。</p>
 *
 * <p><b>错误分类为什么这么细</b>：{@code 401} 重试一万次也不会好，而 {@code 429}/{@code 5xx}/超时
 * 退避一下大概率能过。分类错了的代价是双向的——把 401 当可重试，会把配额浪费在注定失败的请求上；
 * 把 429 当不可重试，一次普通的限流就会让整条 item 直接 FAILED（本可以救回来）。
 * 所以每个分支都显式给出 {@code retryable}，而不是让上层"按错误码猜"。</p>
 *
 * <p><b>日志纪律</b>：<b>绝不打印 Authorization 头与完整提示词</b>。提示词里可能含用户上传的
 * 接口文档原文（等于把客户资料写进日志），而 Key 打一次就得轮换。</p>
 *
 * @author 寇豆码
 */
@Component
public class OpenAiCompatibleProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);

    /** 供应商名，与 {@code ai.provider=openai-compatible} 对应。 */
    public static final String NAME = "openai-compatible";

    /** 请求体媒体类型。 */
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** 上游错误体在日志里最多保留多少字符（HTML 错误页轻松几十 KB）。 */
    private static final int MAX_RAW_LOG = 800;

    /** 连接超时：与读写超时分开，DNS/握手挂死时不要等满 60 秒。 */
    private static final long CONNECT_TIMEOUT_SECONDS = 10L;

    /** 模型接入配置（Key / baseUrl / model / 超时）。 */
    private final AiProperties aiProperties;

    /** HTTP 客户端：**单例复用**，OkHttp 的连接池与线程池都挂在实例上，每次 new 等于每次重建。 */
    private final OkHttpClient httpClient;

    public OpenAiCompatibleProvider(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(aiProperties.getTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // 不自动重试：重试策略由编排层 RetryPolicy 统一决定（要退避、要记 attempt_no）
                .retryOnConnectionFailure(false)
                .build();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(String model) {
        // 兼容网关的模型名由运营侧随时新增，代码里不能有白名单
        return true;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        String baseUrl = requireConfigured(aiProperties.getBaseUrl(),
                "未配置 AI_BASE_URL，无法调用 openai-compatible 服务");
        String apiKey = requireConfigured(aiProperties.getApiKey(),
                "未配置 AI_API_KEY，无法调用 openai-compatible 服务（mock 模式不需要 Key）");
        String model = requireConfigured(aiProperties.getModel(),
                "未配置 AI_MODEL，无法调用 openai-compatible 服务");

        String url = buildUrl(baseUrl);
        String payload = buildPayload(request, model);
        long startNanos = System.nanoTime();

        Request httpRequest = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload, JSON))
                .build();

        try {
            okhttp3.Call call = httpClient.newCall(httpRequest);
            // 单次调用超时覆盖：请求上写的 timeoutMs 优先（编排层给"快速试一次"的场景用）
            if (request.getTimeoutMs() > 0) {
                call.timeout().timeout(request.getTimeoutMs(), TimeUnit.MILLISECONDS);
            }
            try (Response response = call.execute()) {
                long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
                return handleResponse(response, model, latencyMs);
            }
        } catch (java.net.SocketTimeoutException ex) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.warn("[LLM] 调用超时 url={} 耗时={}ms（上限 {}s）", url, latencyMs, aiProperties.getTimeoutSeconds());
            throw new LlmException(ErrorCode.E_LLM_TIMEOUT,
                    "模型调用超时（" + aiProperties.getTimeoutSeconds() + " 秒），建议稍后重试", true, ex);
        } catch (IOException ex) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.warn("[LLM] 网络异常 url={} 耗时={}ms：{}", url, latencyMs, ex.toString());
            throw new LlmException(ErrorCode.E_LLM_UPSTREAM,
                    "无法连接模型服务，请检查网络与 AI_BASE_URL", true, ex);
        }
    }

    /**
     * 拼请求 URL。
     *
     * <p>只做"末尾斜杠"归一，<b>不猜路径</b>：配 {@code https://api.deepseek.com/v1} 就得到
     * {@code .../v1/chat/completions}，配到根路径就得到 {@code .../chat/completions}
     * （DeepSeek 两种都认）。若代码里自动补 {@code /v1}，反而会让"已经带 /v1 的网关"变成
     * {@code /v1/v1/chat/completions}——这类错误返回 404，但日志里看着像"网关不兼容"。</p>
     *
     * @param baseUrl 配置的基地址
     * @return 完整 URL
     */
    private String buildUrl(String baseUrl) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return trimmed + "/chat/completions";
    }

    /**
     * 拼请求体。
     *
     * @param request 调用入参
     * @param model   模型名
     * @return JSON 文本
     */
    private String buildPayload(LlmRequest request, String model) {
        ObjectNode body = JsonUtils.mapper().createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            messages.addObject().put("role", LlmMessage.ROLE_SYSTEM).put("content", request.getSystemPrompt());
        }
        for (LlmMessage message : request.getMessages()) {
            messages.addObject().put("role", message.role()).put("content", message.content());
        }
        body.put("temperature", request.getTemperature());
        if (request.getMaxTokens() > 0) {
            body.put("max_tokens", request.getMaxTokens());
        }
        if (request.getJsonSchema() != null && !request.getJsonSchema().isBlank()) {
            // json_object：要求上游"只输出 JSON 对象"。注意它不保证符合我们的 schema，
            // 所以 schema 校验仍然必须在本侧做（见 JsonSchemaValidator）
            body.putObject("response_format").put("type", "json_object");
        }
        body.put("stream", false);
        return body.toString();
    }

    /**
     * 处理响应：分状态码映射错误，成功则解析 content / usage。
     *
     * @param response  HTTP 响应
     * @param model     模型名
     * @param latencyMs 耗时
     * @return 统一响应
     * @throws IOException 读响应体失败
     */
    private LlmResponse handleResponse(Response response, String model, long latencyMs) throws IOException {
        int code = response.code();
        ResponseBody responseBody = response.body();
        String text = responseBody == null ? "" : responseBody.string();

        if (code == 200) {
            return parseSuccess(text, model, latencyMs);
        }
        String brief = abbreviate(text);
        if (code == 429) {
            log.warn("[LLM] 上游限流（429）latency={}ms body={}", latencyMs, brief);
            throw new LlmException(ErrorCode.E_LLM_RATE_LIMIT, "模型服务限流，请稍后重试", true);
        }
        if (code == 401 || code == 403) {
            // Key 错/过期：不可重试，且**不要**把上游返回体打给用户（可能回显 Key 前缀）
            log.error("[LLM] 鉴权失败（{}）请检查 AI_API_KEY，body={}", code, brief);
            throw new LlmException(ErrorCode.E_LLM_UPSTREAM,
                    "模型服务鉴权失败（HTTP " + code + "），请检查 AI_API_KEY 是否正确", false);
        }
        if (code >= 500) {
            log.warn("[LLM] 上游 5xx（{}）latency={}ms body={}", code, latencyMs, brief);
            throw new LlmException(ErrorCode.E_LLM_UPSTREAM, "模型服务异常（HTTP " + code + "），请稍后重试", true);
        }
        // 其余 4xx：多半是参数/模型名写错，重试无用
        log.error("[LLM] 上游 4xx（{}）latency={}ms body={}", code, latencyMs, brief);
        throw new LlmException(ErrorCode.E_LLM_UPSTREAM,
                "模型服务拒绝了请求（HTTP " + code + "），请检查模型名与参数", false);
    }

    /**
     * 解析 200 响应体。
     *
     * @param text      响应体
     * @param model     模型名
     * @param latencyMs 耗时
     * @return 统一响应
     */
    private LlmResponse parseSuccess(String text, String model, long latencyMs) {
        JsonNode root;
        try {
            root = JsonUtils.mapper().readTree(text);
        } catch (IOException ex) {
            log.warn("[LLM] 上游 200 但响应不是 JSON：{}", abbreviate(text));
            throw new LlmException(ErrorCode.E_LLM_UPSTREAM, "模型服务返回了无法解析的响应", true, ex);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            log.warn("[LLM] 上游 200 但 choices 为空：{}", abbreviate(text));
            throw new LlmException(ErrorCode.E_LLM_UPSTREAM, "模型服务未返回任何结果", true);
        }
        JsonNode first = choices.get(0);
        String content = first.path("message").path("content").asText("");
        if (content.isBlank()) {
            // 空内容是失败，不是"成功但空"：否则会被下游当成"解析失败"，错误码从上游漂成解析
            log.warn("[LLM] 上游 200 但 content 为空，finish_reason={}", first.path("finish_reason").asText(""));
            throw new LlmException(ErrorCode.E_LLM_UPSTREAM, "模型服务返回了空内容", true);
        }
        JsonNode usageNode = root.path("usage");
        LlmUsage usage = new LlmUsage(
                usageNode.path("prompt_tokens").asInt(0),
                usageNode.path("completion_tokens").asInt(0));
        String finishReason = first.path("finish_reason").asText("stop");
        log.debug("[LLM] 调用成功 model={} latency={}ms tokens={}+{} finish={}", model, latencyMs,
                usage.promptTokens(), usage.completionTokens(), finishReason);
        return LlmResponse.builder()
                .content(content)
                .usage(usage)
                .finishReason(finishReason)
                .latencyMs(latencyMs)
                .model(root.path("model").asText(model))
                .rawBody(abbreviate(text))
                .build();
    }

    /**
     * 必填配置校验。
     *
     * @param value   配置值
     * @param message 缺失时的中文说明
     * @return 去空白后的配置值
     */
    private String requireConfigured(String value, String message) {
        if (value == null || value.isBlank()) {
            throw LlmException.misconfigured(message);
        }
        return value.trim();
    }

    /**
     * 日志/报错用的短文本。
     *
     * @param text 原文
     * @return 截断后的文本
     */
    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_RAW_LOG ? text : text.substring(0, MAX_RAW_LOG) + "…";
    }
}
