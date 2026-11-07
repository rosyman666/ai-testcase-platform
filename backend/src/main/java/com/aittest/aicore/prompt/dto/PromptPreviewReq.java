package com.aittest.aicore.prompt.dto;

import java.util.Map;

/**
 * 模板试跑入参（{@code POST /api/prompt-templates/{id}/preview}）。
 *
 * <p><b>为什么要有这个端点</b>（T04 落地时新增，见 §11.1 D-24）：§4.2 原本只给了模板的
 * 增查改四个管理端点，但那样"记账装饰器是否真的落了一条台账"就<b>无法从接口层验证</b>——
 * 而它恰恰是 T04 ⑦⑧ 两条验收的核心。有了试跑：改完措辞 → 立即看模型输出 → 同时验证
 * 渲染、调用、记账、解析、schema 校验五件事，这在做提示词迭代时也是真正需要的功能。</p>
 *
 * @param variables      模板变量表（key 必须与 {@code &#123;&#123;var&#125;&#125;} 完全一致，缺一个就报错）
 * @param attemptNo      第几次尝试（默认 1；传 2/3 可验证台账 {@code attempt_no} 递增而不是覆盖）
 * @param validateSchema 是否按模板的 {@code output_schema} 校验并解析（默认 {@code true}）
 * @author 寇豆码
 */
public record PromptPreviewReq(Map<String, String> variables, Integer attemptNo, Boolean validateSchema) {
}
