package com.aittest.testcase.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 用例评审台账（{@code case_review}）：<b>只追加，永不 UPDATE / DELETE</b>。
 *
 * <p><b>为什么是 append-only</b>：这张表是"采纳率 / 驳回原因分布 / 编辑幅度"三个指标的
 * <b>唯一真源</b>。一旦允许 UPDATE，历史就被改写——某次评审把 10 条驳回改成采纳，
 * 事后查"这个提示词版本为什么质量差"就永远查不出来（数据已经被洗白了）。
 * 所以"改主意"不是 UPDATE，而是<b>再插一行</b>：{@code test_case.status} 取最后一次动作，
 * 历史完整保留在本表里（验收⑤）。</p>
 *
 * <p><b>快照只对 EDIT 有值</b>：{@code old_content}/{@code new_content} 各是一份完整的用例
 * JSON（同一个 {@code TestCaseVO} 序列化，字段顺序一致，否则 diff 全是噪声）。
 * ACCEPT/REJECT 不产生内容变化，两列为 {@code NULL}——把未变化的快照也写进去，
 * "有快照 = 有编辑" 这条判据就废了，统计编辑幅度时会把纯采纳也算成一次编辑。</p>
 *
 * <p><b>{@code reject_reason_code} 只有 REJECT 有值</b>，且必须命中 {@code reject_reason} 字典
 * （FK）。ACCEPT/EDIT 传了原因码一律落 {@code NULL}：前端复用同一张表单，
 * 残留值不该污染"驳回原因分布"——那张饼图的分母就是"REJECT 且 code 非空"的行数。</p>
 *
 * <p><b>{@code reason_text} 是自由文本，永不被聚合</b>（V1 注释的原话）：人可以写
 * "预期写得太笼统"，但它无法归并，进不了任何 group by。这也是它和字典码必须共存的原因——
 * 一个是机器的口径，一个是人的解释。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString(exclude = {"oldContent", "newContent"})
@TableName("case_review")
public class CaseReview {

    /** 主键（雪花 ID）。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 被评审的用例，关联 {@code test_case.id}（{@code ON DELETE CASCADE}）。 */
    private Long caseId;

    /** 动作编码：{@code ACCEPT} / {@code EDIT} / {@code REJECT}，取值见 {@link ReviewAction}。 */
    private String action;

    /** 驳回原因字典码（关联 {@code reject_reason.code}）；只有 REJECT 有值。 */
    private String rejectReasonCode;

    /** 自由文本说明（选填，任何动作都可带；永不参与聚合）。 */
    private String reasonText;

    /** 编辑前快照（JSON，只有 EDIT 有值）。 */
    private String oldContent;

    /** 编辑后快照（JSON，只有 EDIT 有值）。 */
    private String newContent;

    /** 评审人，关联 {@code app_user.id}（{@code ON DELETE SET NULL}：人删了台账仍在）。 */
    private Long reviewerId;

    /** 创建时间（UTC，由数据库默认值写入，应用侧<b>不填</b>）。 */
    private LocalDateTime createdAt;
}
