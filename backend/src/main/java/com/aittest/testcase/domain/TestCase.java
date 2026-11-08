package com.aittest.testcase.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 测试用例（{@code test_case}）：生成链路的**终点**，评审与执行的**原点**。
 *
 * <p><b>三条改代码前必须知道的约束</b>（每条都对应一次真实故障）：</p>
 * <ol>
 *   <li><b>{@code (task_item_id, seq_no)} 是唯一键</b>（{@code uk_test_case_item_seq}），
 *       而 worker 在 {@code attempts < 3} 时会<b>重跑同一个 item</b>。所以落库<b>必须</b>
 *       用 {@code INSERT ... ON DUPLICATE KEY UPDATE}（先写后删多余序号），
 *       不能 {@code INSERT IGNORE}——重试后模型可能产出不同内容，{@code INSERT IGNORE}
 *       会静默保留旧内容，表现为"重试成功但内容没更新"，日志里看不出来。
 *       详见 {@code TestCaseMapper#upsertBatch}。</li>
 *   <li><b>{@code status} 不在 upsert 的更新列里</b>。状态是<b>评审产物</b>
 *       （DRAFT → ACCEPTED/EDITED/REJECTED），重新生成把人工采纳的用例打回 DRAFT
 *       等于把评审结果丢掉；而评审是标注数据的唯一真源，丢了就再也算不出采纳率。</li>
 *   <li><b>{@code steps} 是 JSON 数组字符串</b>，元素形如
 *       {@code {"no":1,"action":"...","data":"..."}}（DDL 注释的原话）。
 *       它是<b>数组</b>不是对象：前端按 {@code JSON.parse(steps)} 直接遍历，
 *       写成 {@code {"steps":[...]}} 会让每一处读取都要多解一层。</li>
 * </ol>
 *
 * <p><b>{@code confidence} 用 {@link BigDecimal}</b>：列是 {@code DECIMAL(4,3)}，
 * 用 {@code double} 会引入二进制浮点误差，"0.500 还是 0.499"在指标里会变成口径问题。</p>
 *
 * <p><b>{@code created_at} / {@code updated_at} 由数据库写</b>（DEFAULT /
 * ON UPDATE CURRENT_TIMESTAMP(3)）：应用侧填等于给审计列引入 JVM 时钟这第二个来源。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString(exclude = {"steps", "precondition", "expected"})
@TableName("test_case")
public class TestCase {

    /** 主键（雪花 ID）。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属任务，关联 {@code gen_task.id}（{@code ON DELETE CASCADE}）。 */
    private Long taskId;

    /**
     * 所属任务子项，关联 {@code gen_task_item.id}（{@code ON DELETE CASCADE}）。
     *
     * <p>与 {@link #seqNo} 一起构成唯一键：同一个 item 重跑时靠它做幂等覆盖。</p>
     */
    private Long taskItemId;

    /** 关联测试点，关联 {@code test_point.id}（{@code ON DELETE SET NULL}：点被删了用例仍在）。 */
    private Long testPointId;

    /** 关联文档，关联 {@code doc_source.id}（{@code ON DELETE SET NULL}）。 */
    private Long docId;

    /** 同一 item 内的用例序号（从 1 开始，与 {@code taskItemId} 组成唯一键）。 */
    private Integer seqNo;

    /** 用例标题（动作 + 对象 + 预期）。 */
    private String title;

    /** 前置条件（纯文本；无则"无"）。 */
    private String precondition;

    /** 测试步骤（JSON **数组**字符串：{@code [{"no":1,"action":"...","data":"..."}]}）。 */
    private String steps;

    /** 预期结果（必须含可观察量：状态码 / 字段值 / 副作用）。 */
    private String expected;

    /** 优先级：P0/P1/P2/P3，取值见 {@code common.enums.CasePriority}。 */
    private String priority;

    /** 用例类型，取值见 {@code common.enums.CaseType}（与测试点共用同一个枚举）。 */
    private String caseType;

    /** 评审状态，取值见 {@code common.enums.CaseStatus}（DRAFT/ACCEPTED/EDITED/REJECTED）。 */
    private String status;

    /** 模型自评置信度 0.000~1.000（降级解析固定 0.500）。 */
    private BigDecimal confidence;

    /**
     * 生成时使用的提示词版本（如 {@code case_generate.v1}）。
     *
     * <p><b>记的是"当时用哪版"，不是"现在用哪版"</b>：模板改版后老用例这个字段不能跟着变，
     * 否则"这批用例质量差是哪版模板的问题"就无从回溯（与 {@code TestPoint.promptVersion} 同口径）。</p>
     */
    private String promptVersion;

    /** 生成时使用的模型名（台账口径，便于按模型归集质量）。 */
    private String model;

    /** 内容哈希 {@code sha256(title \n steps \n expected)}，用于同一 item 内查重（{@code idx_test_case_content_hash}）。 */
    private String contentHash;

    /** 创建时间（UTC，由数据库默认值写入，应用侧<b>不填</b>）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC，{@code ON UPDATE CURRENT_TIMESTAMP(3)}，应用侧<b>不填</b>）。 */
    private LocalDateTime updatedAt;
}
