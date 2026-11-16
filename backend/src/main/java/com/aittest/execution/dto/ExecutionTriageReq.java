package com.aittest.execution.dto;

/**
 * 人工纠正归因的请求体（{@code PATCH /api/executions/results/{id}}）。
 *
 * <p><b>只有 {@code failureCategory} 一个字段</b>：{@code triage_source} 由服务端强制写成
 * {@code MANUAL}，{@code failure_message} / {@code stackTrace} 不允许被这个端点改写——
 * 它们是从结果文件里解析出来的<b>事实</b>，人工可以改判性质，但不能改证据。
 * 请求体里出现多余字段会被 Jackson 忽略（Spring Boot 默认
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false}），旧前端多传 {@code reasonText} 也不会 400。</p>
 *
 * @param failureCategory 失败分类字典码（必填；不存在或已停用 → 40001）
 * @author 寇豆码
 */
public record ExecutionTriageReq(String failureCategory) {
}
