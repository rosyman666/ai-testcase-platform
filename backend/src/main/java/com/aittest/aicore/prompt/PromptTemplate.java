package com.aittest.aicore.prompt;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 提示词模板（{@code prompt_template}）：<b>版本化、可回溯</b>。
 *
 * <p><b>为什么不直接把模板写死在代码里</b>：模板是 AI 项目里改得最频繁的东西——
 * 换个措辞、加条 few-shot、调一下输出约束，效果能差 10 个百分点。写死在代码里意味着
 * 每次调模板都要发版，而发版就意味着"调模板"这件事被推到最后。更关键的是
 * <b>没法回溯</b>：{@code test_case.prompt_version} 记的是版本号，如果版本只存在于 git 历史里，
 * "这批用例是哪版模板生成的"就永远查不清，A/B 对比也就做不了（§8.6）。</p>
 *
 * <p><b>三个字段的语义必须分清</b>：{@link #code} 是"哪套模板"（稳定不变），
 * {@link #version} 是"第几版"（同 code 自增），{@link #isActive} 是"现在用哪版"
 * （同 code 唯一为 1，由 service 层保证）。把 code 和 version 混起来用，
 * 结果就是"升版本"变成"新建模板"，历史版本再也对不上。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString(exclude = "content")
@TableName("prompt_template")
public class PromptTemplate {

    /** 主键（雪花 ID）。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 模板编码：{@code testpoint_extract} / {@code case_generate} / {@code case_repair} / {@code failure_triage}。 */
    private String code;

    /** 版本号（同 code 自增，从 1 开始）。 */
    private Integer version;

    /** 场景：{@code TESTPOINT_EXTRACT} / {@code CASE_GENERATE} / {@code CASE_REPAIR} / {@code FAILURE_TRIAGE}。 */
    private String scene;

    /** 模板名称（人读）。 */
    private String name;

    /** 模板正文（含 {@code &#123;&#123;var&#125;&#125;} 占位符）。**不进 toString**：一条正文几千字，打日志会刷屏。 */
    private String content;

    /** 输出 schema 路径：{@code classpath:schema/xxx.schema.json}。 */
    private String outputSchema;

    /** 推荐模型（<b>仅供参考</b>，不参与路由；见 §11.1 D-05）。 */
    private String modelHint;

    /** 是否生效：{@code 1} 生效（同 code 唯一），{@code 0} 历史版本。 */
    private Integer isActive;

    /** 创建时间（UTC），由 {@code AuditMetaObjectHandler} 填充。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC），由 {@code AuditMetaObjectHandler} 填充。 */
    private LocalDateTime updatedAt;
}
