package com.aittest.doc.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 需求文档主表（{@code doc_source}）。
 *
 * <p>这是全库**唯一带软删**的表（见 §3.2.2），删除只改 {@link #deleted} 标记，行不会消失。
 * 三条约束改代码前必须知道：</p>
 * <ol>
 *   <li>{@code deleted} 由 MyBatis-Plus 逻辑删除接管，{@code BaseMapper} 的内置方法会自动带
 *       {@code deleted = 0}；但<b>手写 SQL 必须自己加</b>，反过来像"按 hash 查重"这种需要
 *       看见已删行的场景必须写自定义 SQL 绕过（见 {@code DocSourceMapper#selectByFileHash}）。</li>
 *   <li>{@link #createdAt} / {@link #updatedAt} 由 {@code AuditMetaObjectHandler} 自动填充，
 *       业务代码禁止手写（§3.2.1）。</li>
 *   <li>{@link #storagePath} 是原始文件的落盘相对路径，**禁止出现在任何响应里**（§4.4），
 *       所以这里标了 {@link JsonIgnore} 做第二道保险——第一道是 VO 拆分。</li>
 * </ol>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString
@TableName("doc_source")
public class DocSource {

    /** 主键（雪花 ID，Jackson 全局序列化成字符串）。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务单号：{@code D + yyyyMMdd + '-' + 6 位序号}，一经生成不再变更（删了再传也要复用）。 */
    private String docNo;

    /** 原始文件名（用户上传时的名字，用于展示与导出追溯）。 */
    private String name;

    /** 文档类型编码，取值见 {@code common.enums.DocType}（MD/TXT/DOCX/OPENAPI_JSON）。 */
    private String docType;

    /** 文件字节数。 */
    private Long fileSize;

    /** 内容 SHA-256 的**前 32 位**（小写十六进制），用于重复上传秒回。 */
    private String fileHash;

    /** 原始文件落盘路径（相对工作目录），仅服务端使用。 */
    @JsonIgnore
    private String storagePath;

    /** 解析状态编码，取值见 {@code common.enums.ParseStatus}（PENDING/PARSED/FAILED）。 */
    private String parseStatus;

    /** 解析失败原因（面向用户的中文，**不是堆栈**；空串表示无错误）。 */
    private String parseError;

    /** 切分出的章节数（冗余字段，列表页不聚合子表）。 */
    private Integer sectionCount;

    /** 切分出的接口操作数（冗余字段，理由同上）。 */
    private Integer operationCount;

    /** 上传人，关联 {@code app_user.id}。 */
    @JsonIgnore
    private Long createdBy;

    /** 软删标记：0 正常、1 已删除。 */
    @JsonIgnore
    @TableLogic(value = "0", delval = "1")
    private Integer deleted;

    /** 创建时间（UTC）。 */
    private java.time.LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private java.time.LocalDateTime updatedAt;
}
