package com.aittest.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 驳回原因字典（{@code reject_reason}）。
 *
 * <p>主键是业务码 {@code code}（VARCHAR），不是雪花 ID：字典码要在
 * {@code case_review.reject_reason_code} 里被引用、要进 {@code GROUP BY}，
 * 用数值代理键反而每次统计都要 join 一次。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString
@TableName("reject_reason")
public class RejectReason {

    /** 字典码（主键，如 {@code EXPECT_UNCLEAR}）。 */
    @TableId(type = IdType.INPUT)
    private String code;

    /** 中文文案（仅展示，可运营修改）。 */
    private String label;

    /** 归并大类：CONTENT/ALIGNMENT/COVERAGE/DATA/FORMAT/OTHER。 */
    private String category;

    /** 排序号。 */
    private Integer sortNo;

    /** 是否启用：1 启用 0 停用。 */
    private Boolean enabled;
}
