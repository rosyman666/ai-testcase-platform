package com.aittest.aicore.provider;

import com.aittest.aicore.LlmException;
import com.aittest.aicore.LlmProvider;
import com.aittest.aicore.LlmRequest;
import com.aittest.aicore.LlmResponse;
import com.aittest.aicore.LlmUsage;
import com.aittest.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import lombok.ToString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 离线 mock 供应商：<b>不需要任何 API Key</b>，输出确定、可复现。
 *
 * <p><b>为什么必须有它</b>（不只是"没 Key 时的临时办法"）：</p>
 * <ol>
 *   <li><b>CI 可跑</b>：验收要求"测试不依赖外网、不需要 API Key"（T13 ③）。有 mock，
 *       整条链路（上传→抽点→生成→评审→导出）才能在流水线里跑；</li>
 *   <li><b>回归可复现</b>：同一个输入永远同一个输出，断言才能写成"等于"，
 *       否则只能写成"非空"，而"非空"抓不到"字段悄悄错位"这类 bug；</li>
 *   <li><b>坏路径可造</b>：真实模型不会乖乖给你吐"半个 JSON"。</li>
 * </ol>
 *
 * <p><b>确定性怎么来的</b>：把整个提示文本做 SHA-256，用摘要决定"返回 1~3 条"与选用哪套话术。
 * 不用 {@code Random} 也不用 {@code Math.random()}——它们会让同一个测试第二次跑出不同结果，
 * 而"偶发失败"在 CI 上通常被当成环境问题重跑掉，等于没测。</p>
 *
 * <p><b>输出形状怎么选</b>：看 {@link LlmRequest#getJsonSchema()} 里的关键字。
 * 这是 mock 唯一"理解业务"的地方，用关键字而不是类型判断，是因为 mock 属于 {@code aicore}，
 * <b>不能</b>依赖 {@code testpoint} / {@code case} 这些业务包（§1.3 单向依赖）。
 * 加新的结构化场景时，在这里加一个关键字分支即可。</p>
 *
 * <p><b>坏路径开关</b>（给 T07 的三档降级与 T13 的回归用）：提示词里出现
 * {@code [[MOCK:xxx]]} 标记时，按 xxx 返回对应的坏输出：</p>
 * <table border="1">
 *   <caption>mock 场景标记</caption>
 *   <tr><th>标记</th><th>行为</th></tr>
 *   <tr><td>{@code ok}（缺省）</td><td>返回合法 JSON</td></tr>
 *   <tr><td>{@code fence}</td><td>包一层 markdown 围栏</td></tr>
 *   <tr><td>{@code trailing-comma}</td><td>尾逗号</td></tr>
 *   <tr><td>{@code single-quote}</td><td>单引号 key</td></tr>
 *   <tr><td>{@code truncated}</td><td>截断到最后一个元素中间</td></tr>
 *   <tr><td>{@code schema-invalid}</td><td>JSON 合法但 {@code priority} 不是枚举值</td></tr>
 *   <tr><td>{@code upstream-error}</td><td>直接抛 {@code E_LLM_UPSTREAM}（模拟上游 5xx）</td></tr>
 * </table>
 *
 * @author 寇豆码
 */
@ToString
@Component
public class MockLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(MockLlmProvider.class);

    /** 供应商名，与 {@code ai.provider=mock} 对应。 */
    public static final String NAME = "mock";

    /** 场景标记前缀。 */
    private static final String MODE_MARKER = "[[MOCK:";

    /** 默认模型名（与 {@code model_price} 种子的 mock 行对齐，见 §8.8）。 */
    private static final String DEFAULT_MODEL = "mock-model";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(String model) {
        // 空模型名走 mock；显式写 mock-* 也认。其余一律不认（避免把 deepseek-chat 路由到 mock 上）
        return model == null || model.isBlank() || model.startsWith("mock");
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        long startNanos = System.nanoTime();
        String mode = resolveMode(request.allText());
        if ("upstream-error".equals(mode)) {
            // 模拟"上游 500"：可重试，交由 T07 的 RetryPolicy 决定退避
            throw new LlmException(ErrorCode.E_LLM_UPSTREAM, "mock 模拟的上游 5xx（标记 [[MOCK:upstream-error]]）", true);
        }

        String hash = sha256Hex(request.allText());
        String payload = buildPayload(request.getJsonSchema(), hash, mode);
        String content = applyMode(payload, mode);
        long latencyMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);

        log.debug("[MOCK] 场景={} 输出长度={} 摘要={}", mode, content.length(), hash.substring(0, 8));
        return LlmResponse.builder()
                .content(content)
                .usage(estimateUsage(request, content))
                .finishReason("truncated".equals(mode) ? "length" : "stop")
                .latencyMs(latencyMs)
                // 固定 mock-model：与 model_price 的 0/0 种子行对齐，成本才能算成 0 而不是"查不到单价"
                .model(DEFAULT_MODEL)
                .rawBody(content)
                // 只有"截断"与"schema 不合规"算 mock 自报降级：它们代表这一份输出质量打折
                .degraded("truncated".equals(mode) || "schema-invalid".equals(mode))
                .build();
    }

    /**
     * 从提示文本里识别 {@code [[MOCK:xxx]]} 标记。
     *
     * @param text 提示文本
     * @return 场景名，缺省 {@code ok}
     */
    private String resolveMode(String text) {
        if (text == null) {
            return "ok";
        }
        int start = text.indexOf(MODE_MARKER);
        if (start < 0) {
            return "ok";
        }
        int end = text.indexOf(']', start + MODE_MARKER.length());
        if (end < 0) {
            return "ok";
        }
        String mode = text.substring(start + MODE_MARKER.length(), end).replace("]", "").trim();
        // 标记里带 ']' 时上面会截断，这里再清洗一次
        return mode.isEmpty() ? "ok" : mode;
    }

    /**
     * 按 schema 关键字生成合法 JSON。
     *
     * @param jsonSchema schema 原文（可为空）
     * @param hash       输入摘要（决定条数与取值，保证确定性）
     * @param mode       场景（{@code schema-invalid} 时故意写错枚举值）
     * @return JSON 文本
     */
    private String buildPayload(String jsonSchema, String hash, String mode) {
        int count = 1 + Math.floorMod(hash.hashCode(), 3);
        String schema = jsonSchema == null ? "" : jsonSchema;
        if (schema.contains("\"testpoints\"") || schema.contains("测试点")) {
            return buildTestPoints(count, hash, mode);
        }
        if (schema.contains("\"cases\"") || schema.contains("用例")) {
            return buildCases(count, hash, mode);
        }
        if (schema.contains("\"confidence\"") || schema.contains("归因")) {
            // 归因场景：输出是**单个对象**，不是数组
            String category = "schema-invalid".equals(mode) ? "未知类别" : "ASSERTION";
            return "{\"category\":\"" + category + "\",\"confidence\":0.8,"
                    + "\"reason\":\"mock 归因，inputHash=" + hash.substring(0, 8) + "\"}";
        }
        // 没有 schema：返回一个最小对账单形状，仍保证是合法 JSON（不返回空串冒充成功）
        return "{\"items\":[{\"mock\":true,\"inputHash\":\"" + hash.substring(0, 8) + "\"}]}";
    }

    /**
     * 造测试点数组（外层包 {@code {"testpoints": [...]}}，与 {@code response_format=json_object} 兼容）。
     *
     * @param count 条数
     * @param hash  输入摘要
     * @param mode  场景
     * @return JSON 文本
     */
    private String buildTestPoints(int count, String hash, String mode) {
        String[] categories = {"FUNCTIONAL", "BOUNDARY", "EXCEPTION", "SECURITY"};
        String[] risks = {"HIGH", "MEDIUM", "LOW"};
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                items.append(',');
            }
            String category = categories[Math.floorMod(hash.hashCode() + i, categories.length)];
            String risk = risks[Math.floorMod(hash.hashCode() + i * 7, risks.length)];
            if ("schema-invalid".equals(mode) && i == count - 1) {
                // 只让最后一条非法：模拟"99 条里错 1 条"，正好用来验证错误路径带下标
                category = "未知分类";
            }
            items.append("{\"title\":\"接口 ").append(i + 1).append(" 的").append(category)
                    .append("测试点（mock）\",")
                    .append("\"category\":\"").append(category).append("\",")
                    .append("\"riskLevel\":\"").append(risk).append("\",")
                    .append("\"description\":\"验证该接口在正常与异常输入下的行为，mock 输出，hash=")
                    .append(hash, 0, 8).append("\",")
                    .append("\"sourceRef\":\"mock/operation/").append(i + 1).append("\"}");
        }
        return "{\"testpoints\":[" + items + "]}";
    }

    /**
     * 造用例数组（外层包 {@code {"cases": [...]}}）。
     *
     * @param count 条数
     * @param hash  输入摘要
     * @param mode  场景
     * @return JSON 文本
     */
    private String buildCases(int count, String hash, String mode) {
        String[] types = {"FUNCTIONAL", "BOUNDARY", "EXCEPTION"};
        String[] priorities = {"P0", "P1", "P2"};
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                items.append(',');
            }
            String type = types[Math.floorMod(hash.hashCode() + i, types.length)];
            String priority = priorities[Math.floorMod(hash.hashCode() + i * 5, priorities.length)];
            if ("schema-invalid".equals(mode) && i == count - 1) {
                // priority 写成"高"：JSON 合法、枚举非法，用来验证 E_SCHEMA_INVALID 的路径提示
                priority = "高";
            }
            items.append("{\"title\":\"用例 ").append(i + 1).append("：").append(type).append("（mock）\",")
                    .append("\"precondition\":\"服务已启动，数据已初始化\",")
                    .append("\"steps\":[\"调用接口\",\"校验响应\"],")
                    .append("\"expected\":\"返回 200 且字段符合预期\",")
                    .append("\"priority\":\"").append(priority).append("\",")
                    .append("\"caseType\":\"").append(type).append("\"}");
        }
        return "{\"cases\":[" + items + "]}";
    }

    /**
     * 按场景制造坏输出。
     *
     * @param payload 合法 JSON
     * @param mode    场景
     * @return 处理后的文本
     */
    private String applyMode(String payload, String mode) {
        return switch (mode) {
            case "fence" -> "```json\n" + payload + "\n```";
            case "trailing-comma" -> payload.replace("]}", ",]}");
            case "single-quote" -> payload.replace('"', '\'');
            case "truncated" -> {
                // 截到一半：故意留下"最后半个元素"，验证"退到最后一个完整元素"能救回来
                int cut = payload.length() * 3 / 4;
                yield payload.substring(0, cut);
            }
            default -> payload;
        };
    }

    /**
     * 估算 token 用量。
     *
     * <p>没有真实分词器的近似：中文约 1 token / 1.5 字、英文约 1 token / 4 字符。
     * 这里统一按<b>字符数 / 2</b> 估，只用来说明"量级"，<b>绝不用于成本结论</b>——
     * mock 的单价是 0/0，成本天然为 0。</p>
     *
     * @param request 请求
     * @param content 输出
     * @return 估算用量
     */
    private LlmUsage estimateUsage(LlmRequest request, String content) {
        int prompt = request == null ? 0 : request.allText().length() / 2;
        int completion = content == null ? 0 : content.length() / 2;
        return new LlmUsage(prompt, completion);
    }

    /**
     * SHA-256 十六进制摘要。
     *
     * @param text 输入
     * @return 小写十六进制
     */
    private String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必须实现的算法，真走到这里说明运行环境被裁剪过
            throw new IllegalStateException("JDK 不支持 SHA-256", ex);
        }
    }
}
