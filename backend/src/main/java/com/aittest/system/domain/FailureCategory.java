package com.aittest.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 失败分类字典（{@code failure_category}）。
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString
@TableName("failure_category")
public class FailureCategory {

    /** 字典码（主键，如 {@code TIMEOUT}）。 */
    @TableId(type = IdType.INPUT)
    private String code;

    /** 中文文案。 */
    private String label;

    /** 严重度：HIGH/MEDIUM/LOW。 */
    private String severity;

    /** 排序号。 */
    private Integer sortNo;

    /** 是否启用：1 启用 0 停用。 */
    private Boolean enabled;
}
