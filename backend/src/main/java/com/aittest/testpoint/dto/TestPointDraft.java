package com.aittest.testpoint.dto;

/**
 * 模型抽出的**一条**测试点（解析产物，还没落库）。
 *
 * <p>字段与 {@code schema/testpoint.schema.json} 的 {@code items.properties} 严格一一对应，
 * 多一个少一个都会让 Jackson 反序列化后"静默丢字段"，而 schema 校验是在反序列化**之前**做的
 * ——所以两边不一致时，校验会绿、落库会缺字段。改 schema 必须同步改这个 record。</p>
 *
 * <p><b>{@code sourceRef} 在这里存在、但不落库</b>：落库值由服务端按上下文权威生成
 * （见 {@code TestPointExtractService}）。保留这个字段是为了让 Jackson 能吃下模型输出
 * （schema 里它是 required，缺了直接 {@code E_SCHEMA_INVALID}），以及便于诊断时看模型自己写了什么。</p>
 *
 * @param title       测试点标题（4~200 字）
 * @param category    分类编码，见 {@code common.enums.CaseType}
 * @param riskLevel   风险等级编码，见 {@code common.enums.RiskLevel}
 * @param description 测试意图与判定要点（4~1000 字，<b>不含步骤</b>）
 * @param sourceRef   模型自己写的来源定位（不作为落库值）
 * @author 寇豆码
 */
public record TestPointDraft(String title,
                             String category,
                             String riskLevel,
                             String description,
                             String sourceRef) {
}
