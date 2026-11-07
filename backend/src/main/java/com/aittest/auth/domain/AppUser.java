package com.aittest.auth.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 应用用户（{@code app_user}）。
 *
 * <p>{@code passwordHash} 用 {@code @ToString.Exclude} 排除，避免某处误打日志把哈希写进文件。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString(exclude = "passwordHash")
@TableName("app_user")
public class AppUser {

    /** 主键（雪花 ID，由全局 id-type: assign_id 生成）。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录名（唯一）。 */
    private String username;

    /** BCrypt 密码哈希。 */
    private String passwordHash;

    /** 显示名。 */
    private String displayName;

    /** 是否启用：1 启用 0 禁用。 */
    private Boolean enabled;

    /** 创建时间（由 MetaObjectHandler 填充）。 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间（由 MetaObjectHandler 填充）。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
