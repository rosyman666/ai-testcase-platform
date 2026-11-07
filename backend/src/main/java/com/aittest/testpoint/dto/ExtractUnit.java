package com.aittest.testpoint.dto;

/**
 * 一次抽取的输入：**一个**待分析单元（单个接口操作，或单个章节）。
 *
 * <p><b>为什么是"一个单元"而不是"一整篇文档"</b>（验收 ①②）：把整篇文档一次性塞给模型，
 * 有三个无法回避的问题——① 上下文被切成两半后模型会漏抽后半部分；
 * ② 一个接口抽挂了整篇全废（验收 ⑤ 明确不允许）；③ 无法断言调用次数等于接口数，
 * 而"调用次数"正是成本估算的直接依据。按单元切分后，调用量 = 单元数，可预测、可计费、可隔离失败。</p>
 *
 * <p><b>{@code content} 是"已经拼好的文本"，不是实体</b>：把 {@code api_operation} / {@code doc_section}
 * 拼成模型输入是**编排层**的职责（T05 第 3 步），抽取内核只关心"给我一段文字、给我它来自哪"。
 * 这样内核可以被单测直接喂字符串，不必先造一堆实体。</p>
 *
 * @param docId     所属文档 ID
 * @param operationId 来源接口 ID（纯文本文档为 {@code null}）
 * @param sectionId 来源章节 ID（OpenAPI 文档为 {@code null}）
 * @param sourceRef <b>权威</b>来源定位，如 {@code operation:createUser} / {@code section:3}。
 *                  由服务端生成并落库，模型输出的 {@code sourceRef} 不采信（理由见 {@code TestPointExtractService}）
 * @param docTitle  文档标题（提示词的 {@code {{doc_title}}}，帮助模型判断领域）
 * @param content   待分析内容（提示词的 {@code {{operation_json}}}）
 * @param maxPoints 本次最多抽取条数（提示词的 {@code {{max_points}}}，同时也是 schema 的 {@code maxItems} 上限）
 * @author 寇豆码
 */
public record ExtractUnit(Long docId,
                          Long operationId,
                          Long sectionId,
                          String sourceRef,
                          String docTitle,
                          String content,
                          int maxPoints) {
}
