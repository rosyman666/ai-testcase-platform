package com.aittest.generation.orchestrator;

/**
 * 一次用例生成的<b>纯输入</b>：handler 把 I/O 做完之后交给编排层的全部上下文。
 *
 * <p><b>为什么用 record 而不是把 handler 的成员传进去</b>：编排层要能做到"零 DB 依赖"
 * （{@code CaseGenerateOrchestrator} 的构造里没有任何 mapper），它就不该看到
 * {@code GenTask} / {@code GenTaskItem} / {@code TestPoint} 这些持久化实体——
 * 那些对象带着 {@code status}、{@code attempts}、{@code updatedAt} 等与"调模型"无关的字段，
 * 一旦能拿到，就会有人顺手在里面读一个字段再补一次库查询，分层立刻烂掉。</p>
 *
 * <p><b>字符串在这里已经渲染好还是原始值</b>：这里是<b>原始值</b>（测试点文本、接口上下文、
 * 分类清单……），渲染由编排层完成——因为"用哪个模板、模板要不要换版本"属于编排决策，
 * 只有放它那边，模板改版才不需要动 handler。</p>
 *
 * <p><b>没有 schemaPath</b>：schema 由模板的 {@code output_schema} 决定，编排层自己查模板时一并拿到。
 * 让 handler 传 schema 会出现"handler 用 A 模板取 schema、编排层用 B 模板渲染"的错配。</p>
 *
 * @param taskId        所属任务 ID（落 {@code llm_call_log.task_id}）
 * @param taskItemId    子项 ID（落 {@code llm_call_log.task_item_id}）
 * @param docId         文档 ID（few-shot 素材按文档取，同时是排障时的定位键）
 * @param testPointId   测试点 ID（落 {@code test_case.test_point_id}）
 * @param attemptNo     本次是第几次尝试（{@code item.attempts + 1}，落 {@code attempt_no}）
 * @param promptVersion 本次模板版本（如 {@code case_generate.v1}，落 {@code test_case.prompt_version}）
 * @param testPointText 提示词 {@code {{test_point}}} 的取值
 * @param apiSpec       提示词 {@code {{api_spec}}} 的取值（拿不到就是空串，绝不编造）
 * @param caseTypes     提示词 {@code {{case_types}}} 的取值（由 {@code CaseType} 枚举现算）
 * @param priorityRules 提示词 {@code {{priority_rules}}} 的取值
 * @param fewShot       提示词 {@code {{few_shot}}} 的取值（无素材为空串）
 * @author 寇豆码
 */
public record GenerationContext(Long taskId,
                                Long taskItemId,
                                Long docId,
                                Long testPointId,
                                int attemptNo,
                                String promptVersion,
                                String testPointText,
                                String apiSpec,
                                String caseTypes,
                                String priorityRules,
                                String fewShot) {
}
