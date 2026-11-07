package com.aittest.aicore;

import static org.assertj.core.api.Assertions.assertThat;

import com.aittest.common.ApiResponse;
import com.aittest.common.ErrorCode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 模型调用异常 → 响应码的回归测试（T04 ⑦ 的上游失败分支）。
 *
 * <p><b>这个测试是为了钉住一个真实回归</b>：{@code LlmException} 继承 {@code RuntimeException}
 * 而不是 {@code BizException}，在没有 {@link LlmExceptionHandler} 之前它一路冒到
 * {@code GlobalExceptionHandler} 的兜底分支，响应体是 {@code 50000}。
 * 诡异之处在于<b>台账完全正常</b>（记账装饰器直接读异常对象，{@code error_code=E_LLM_UPSTREAM}），
 * 于是"账本是 50001、用户看到 50000"，两套观测对不上，而任何只看台账的检查都不会变红。</p>
 *
 * @author 寇豆码
 */
class LlmExceptionHandlerTest {

    /** 被测对象。 */
    private final LlmExceptionHandler handler = new LlmExceptionHandler();

    /** 请求上下文（只用到 URI）。 */
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/prompt-templates/1/preview");

    /**
     * 取响应体里的 data（断言前先确认它确实是 Map）。
     *
     * @param body 响应体
     * @return data 字段
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ApiResponse<Object> body) {
        assertThat(body).isNotNull();
        assertThat(body.getData()).isInstanceOf(Map.class);
        return (Map<String, Object>) body.getData();
    }

    @Test
    @DisplayName("上游 5xx → 50001，retryable=true（前端该显示重试按钮）")
    void mapsUpstreamRetryable() {
        ResponseEntity<ApiResponse<Object>> response = handler.handleLlmException(
                new LlmException(ErrorCode.E_LLM_UPSTREAM, "模型服务异常（HTTP 502），请稍后重试", true), request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ApiResponse<Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(50001);
        assertThat(dataOf(body)).containsEntry("retryable", true);
        // 具体原因比错误码默认文案有用：用户/运维能据此判断是不是网关的问题
        assertThat(body.getMessage()).contains("502");
    }

    @Test
    @DisplayName("未配 Key（misconfigured）→ 50001 且 retryable=false（重试一万次也不会好）")
    void mapsMisconfiguredNotRetryable() {
        ResponseEntity<ApiResponse<Object>> response = handler.handleLlmException(
                LlmException.misconfigured("未配置模型 API Key，请设置 AI_API_KEY"), request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(50001);
        assertThat(dataOf(response.getBody())).containsEntry("retryable", false);
        assertThat(response.getBody().getMessage()).contains("API Key");
    }

    @Test
    @DisplayName("超时 50002 / 限流 50003 各自映射，不互相串码")
    void keepsDistinctCodes() {
        assertThat(handler.handleLlmException(
                new LlmException(ErrorCode.E_LLM_TIMEOUT, "模型调用超时（30s），建议稍后重试", true), request)
                .getBody().getCode()).isEqualTo(50002);
        assertThat(handler.handleLlmException(
                new LlmException(ErrorCode.E_LLM_RATE_LIMIT, "模型服务限流，请稍后重试", true), request)
                .getBody().getCode()).isEqualTo(50003);
        assertThat(handler.handleLlmException(
                new LlmException(ErrorCode.E_LLM_PARSE_EXHAUSTED, "模型输出无法解析为有效结构", true), request)
                .getBody().getCode()).isEqualTo(50004);
    }

    @Test
    @DisplayName("message 为空时退到错误码默认文案，不吐 null")
    void fallsBackToDefaultMessage() {
        ApiResponse<Object> body = handler.handleLlmException(
                new LlmException(ErrorCode.E_LLM_UPSTREAM, "  ", true), request).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getMessage()).isEqualTo(ErrorCode.E_LLM_UPSTREAM.getMessage());
    }

    @Test
    @DisplayName("schema 校验失败 50005 原样透出字段路径（试跑要能看懂哪里不合规）")
    void keepsSchemaFieldPath() {
        ApiResponse<Object> body = handler.handleLlmException(
                new LlmException(ErrorCode.E_SCHEMA_INVALID, "模型输出未通过结构校验：[1].category 枚举中没有值", true),
                request).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(50005);
        assertThat(body.getMessage()).contains("[1].category");
    }
}
