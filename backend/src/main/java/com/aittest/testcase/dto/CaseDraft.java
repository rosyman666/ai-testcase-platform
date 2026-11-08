package com.aittest.testcase.dto;

import java.util.List;

/**
 * 模型生成的**一条**测试用例（解析产物，还没落库）。
 *
 * <p><b>字段与 {@code schema/testcase.schema.json} 的 {@code items.properties} 严格一一对应</b>：
 * 六个字段、不多不少。schema 根节点是 {@code type: "array"} 且 {@code additionalProperties: false}，
 * 校验发生在反序列化<b>之前</b>——所以两边一旦漂移，症状是"校验绿了、落库少了字段"，
 * 不会报错。改 schema 必须同步改这个 record。</p>
 *
 * <p><b>刻意没有 {@code confidence}</b>：schema 里根本没有这个字段，且
 * {@code additionalProperties: false} 意味着模型<b>写不出</b>置信度（写了直接
 * {@code E_SCHEMA_INVALID}，报错路径形如 {@code [1].confidence}）。
 * 因此落库置信度只能由服务端决定（见 {@code CaseGenerateHandler}），
 * 不要在 DTO 里加一个永远为 {@code null} 的字段骗自己"模型会给"。</p>
 *
 * <p><b>{@code caseType} 是 camelCase</b>（不是 {@code case_type}）：<b>JSON 里的键名以 schema 为准</b>，
 * 而数据库列名是另一回事，两者由 MyBatis 的下划线转换负责对齐。写成 {@code case_type} 的后果是
 * 反序列化拿不到值（{@code null}），落库时被兜底成 {@code FUNCTIONAL}——
 * 整批用例的类型会静默变成同一个值，且不报任何错。</p>
 *
 * @param title        用例标题（4~200 字，动作 + 对象 + 预期）
 * @param precondition 前置条件（≤500 字，无则"无"；空串也合法）
 * @param steps        可执行步骤（1~30 条，一条一个动作）
 * @param expected     可判定的预期结果（1~1000 字，含可观察量）
 * @param priority     优先级编码，取值见 {@code common.enums.CasePriority}（P0/P1/P2/P3）
 * @param caseType     用例类型编码，取值见 {@code common.enums.CaseType}
 * @author 寇豆码
 */
public record CaseDraft(String title,
                        String precondition,
                        List<String> steps,
                        String expected,
                        String priority,
                        String caseType) {
}
