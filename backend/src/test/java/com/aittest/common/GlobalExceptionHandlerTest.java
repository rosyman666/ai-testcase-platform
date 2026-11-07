package com.aittest.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 数据库约束异常分流的回归测试（T04 ⑨⑩）。
 *
 * <p><b>为什么不用"往库里插一条脏数据"来测</b>：这两条映射的输入是
 * <b>MySQL 的错误码与错误消息</b>，不是表里的数据。造真数据要挑表、挑字段、还要清理，
 * 而且 CI 上没有库就全跳过——等于没测。直接构造 {@link SQLException} 更贴近被测逻辑：
 * 消息文本与错误码就是真实 MySQL 8 的原文（下面每处都注明了出处）。</p>
 *
 * <p><b>⑨ 与 ⑩ 必须同时存在</b>：只验"该分流的都分流了"，会漏掉"兜底桶是否还兜得住"；
 * 只验兜底，就不知道分流有没有把没想到的情况也误吞进去。两边都断言，才能证明这是
 * <b>分流</b>，而不是把 {@code 50000} 换了个地方摆。</p>
 *
 * @author 寇豆码
 */
class GlobalExceptionHandlerTest {

    /** 被测对象。 */
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** 请求上下文（只用到 URI）。 */
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");

    /**
     * 构造带 MySQL 错误码的异常。
     *
     * @param vendorCode MySQL 错误码
     * @param message    MySQL 原始消息
     * @return 数据完整性异常
     */
    private DataIntegrityViolationException dataIntegrity(int vendorCode, String message) {
        SQLException sqlException = new SQLException(message, "HY000", vendorCode);
        return new DataIntegrityViolationException("### Error updating database", sqlException);
    }

    @Test
    @DisplayName("⑨ 1406 超长 → 40004，回显白名单字段名与上限，不回显 SQL 原文")
    void mapsDataTooLong() {
        // 消息取自 MySQL 8.0.45 实际输出
        DataIntegrityViolationException ex = dataIntegrity(1406, "Data too long for column 'title' at row 1");
        ResponseEntity<ApiResponse<Object>> response = handler.handleDataIntegrity(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ApiResponse<Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(40004);
        // 中文标签 + 上限，用户知道该改哪里、还能写多少
        assertThat(body.getMessage()).contains("标题").contains("500");
        // **绝不**把 SQL 原文吐给前端（会泄露表结构）
        assertThat(body.getMessage()).doesNotContain("Data too long").doesNotContain("row 1");
    }

    @Test
    @DisplayName("⑨ 1406 命中未登记列：只给通用文案，一个字母都不回显")
    void mapsDataTooLongWithUnknownColumn() {
        DataIntegrityViolationException ex = dataIntegrity(1406, "Data too long for column 'internal_secret' at row 1");
        ApiResponse<Object> body = handler.handleDataIntegrity(ex, request).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(40004);
        assertThat(body.getMessage()).doesNotContain("internal_secret");
        assertThat(body.getData()).isNull();
    }

    @Test
    @DisplayName("⑨ 1062 重复 doc_no → 40902 + conflictOn=doc_no，且不返回键值")
    void mapsDuplicateDocNo() {
        DataIntegrityViolationException ex = dataIntegrity(1062,
                "Duplicate entry 'D20261107-000123' for key 'doc_source.uk_doc_source_doc_no'");
        ApiResponse<Object> body = handler.handleDataIntegrity(ex, request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(40902);
        assertThat(body.getData()).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> data = (java.util.Map<String, Object>) body.getData();
        assertThat(data).containsEntry("conflictOn", "doc_no");
        // 键值（单号）属于用户数据，不能回显
        assertThat(body.getMessage()).doesNotContain("D20261107-000123");
    }

    @Test
    @DisplayName("⑨ 1062 重复 file_hash → 40902 + conflictOn=file_hash")
    void mapsDuplicateFileHash() {
        DataIntegrityViolationException ex = dataIntegrity(1062,
                "Duplicate entry 'abc123' for key 'doc_source.uk_doc_source_file_hash'");
        ApiResponse<Object> body = handler.handleDataIntegrity(ex, request).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(40902);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> data = (java.util.Map<String, Object>) body.getData();
        assertThat(data).containsEntry("conflictOn", "file_hash");
    }

    @Test
    @DisplayName("⑨ 1062 其它唯一键 → 40902 + conflictOn=other（不猜语义）")
    void mapsDuplicateOther() {
        DataIntegrityViolationException ex = dataIntegrity(1062,
                "Duplicate entry '1' for key 'prompt_template.uk_prompt_template_code_version'");
        ApiResponse<Object> body = handler.handleDataIntegrity(ex, request).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(40902);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> data = (java.util.Map<String, Object>) body.getData();
        assertThat(data).containsEntry("conflictOn", "other");
    }

    @Test
    @DisplayName("边界：外键 1452 没有业务码语义 → 仍走 50000（不硬塞进 40001/40902）")
    void keepsForeignKeyAsInternal() {
        DataIntegrityViolationException ex = dataIntegrity(1452,
                "Cannot add or update a child row: a foreign key constraint fails");
        ApiResponse<Object> body = handler.handleDataIntegrity(ex, request).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(50000);
        assertThat(body.getMessage()).doesNotContain("foreign key");
    }

    @Test
    @DisplayName("⑩ 该兜底的仍兜底：NullPointerException → 50000（信噪比不能被分流搞坏）")
    void unexpectedStaysInternal() {
        ApiResponse<Object> body = handler.handleUnexpected(new NullPointerException("boom"), request).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(50000);
        // 堆栈只进日志：message 必须是可直接 toast 的中文
        assertThat(body.getMessage()).doesNotContain("NullPointerException").doesNotContain("boom");
    }

    @Test
    @DisplayName("T02 既有分支未被破坏：路由不存在仍是 40401 / 404")
    void keepsNoHandlerFound() {
        ResponseEntity<ApiResponse<Object>> response = handler.handleNoHandlerFound(
                new org.springframework.web.servlet.NoHandlerFoundException("GET", "/api/nope", null));
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(40401);
    }
}
