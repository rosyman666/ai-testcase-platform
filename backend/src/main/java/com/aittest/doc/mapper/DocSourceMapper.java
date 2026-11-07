package com.aittest.doc.mapper;

import com.aittest.doc.domain.DocSource;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 文档主表 Mapper。
 *
 * <p><b>为什么这里需要手写 SQL</b>：本表有逻辑删除（{@code deleted}），{@link BaseMapper}
 * 的内置方法会自动追加 {@code deleted = 0}。但"按 {@code file_hash} 查重"这个场景**必须
 * 看见已删行**——否则"删了再传同一文件"永远命中不了旧记录，会新建一份，
 * 导致测试点重复抽取、成本重复计数、{@code doc_no} 追溯链断裂（§3.2.2 三条硬理由）。
 * 所以查重必须走自定义 SQL（不带 {@code deleted} 条件）。
 *
 * <p>三个自定义 SQL 的"必备条件"见 §3.2.2，少一个就出事故：</p>
 * <ul>
 *   <li>① {@code file_hash <> ''}——{@code file_hash} 的 DDL 默认值是空串，
 *       不加这个条件，所有 hash 为空的历史行会互相命中，把 A 文档的内容覆盖到 B 上且不报错；</li>
 *   <li>② {@code ORDER BY deleted ASC, updated_at DESC}——{@code file_hash} 索引**非唯一**，
 *       同一 hash 可能有多条已删行，不排序等于随机捞一条恢复；</li>
 *   <li>③ {@code LIMIT 1}——不加会取到多行。</li>
 * </ul>
 *
 * @author 寇豆码
 */
@Mapper
public interface DocSourceMapper extends BaseMapper<DocSource> {

    /**
     * 按内容 hash 查重（**绕过逻辑删除**，能看见 {@code deleted = 1} 的行）。
     *
     * <p>三个必备条件见类注释。返回 null 表示从未上传过这份内容。</p>
     *
     * @param fileHash 内容 SHA-256 前 32 位
     * @return 命中的记录（可能是软删行），无命中返回 null
     */
    @Select("""
            SELECT * FROM doc_source
             WHERE file_hash = #{fileHash}
               AND file_hash <> ''
             ORDER BY deleted ASC, updated_at DESC
             LIMIT 1
            """)
    DocSource selectByFileHash(@Param("fileHash") String fileHash);

    /**
     * 恢复被软删的记录（§3.2.2 步骤 3）：置 {@code deleted = 0}、重置解析状态、**保持 {@code doc_no} 不变**。
     *
     * <p>为什么不用 {@code updateById}：那是逻辑删除感知的更新路径，条件里可能带
     * {@code deleted = 0}，对一条已删行会更新 0 行且不报错——典型的"静默失败"。
     * 这里用显式 SQL，影响行数可以直接断言。</p>
     *
     * <p>{@code updated_at = NOW(3)} 是 §3.2.2 明确要求的写法：{@code NOW(3)} 与
     * {@code CURRENT_TIMESTAMP(3)} 同属会话时区，与会话已锁 UTC（§3.2.1）同轨，**不要改成
     * {@code UTC_TIMESTAMP(3)}**（那是双轨的起手式）。</p>
     *
     * @param id 文档 ID
     * @return 影响行数（正常为 1）
     */
    @Update("""
            UPDATE doc_source
               SET deleted = 0,
                   parse_status = 'PENDING',
                   parse_error = '',
                   updated_at = NOW(3)
             WHERE id = #{id}
            """)
    int restoreById(@Param("id") Long id);

    /**
     * 物理删除（仅 {@code force=true} 的级联硬删使用；常规删除走逻辑删除）。
     *
     * @param id 文档 ID
     * @return 影响行数
     */
    @Delete("DELETE FROM doc_source WHERE id = #{id}")
    int physicalDeleteById(@Param("id") Long id);

    /**
     * 更新落盘路径（复用分支发现原始文件丢失、按本次上传字节重新落盘后调用）。
     *
     * <p>{@code updated_at = updated_at} 是**故意**显式赋自身：本表有
     * {@code ON UPDATE CURRENT_TIMESTAMP(3)}，不写这一句会把审计时间改成"补文件的时刻"，
     * 而文档内容并没有变。"把丢失的原始文件放回去"不该算作一次内容更新——
     * 这也是 §3.2.1.2 那条纪律的自动化版本。</p>
     *
     * @param id          文档 ID
     * @param storagePath 新的相对路径
     * @return 影响行数
     */
    @Update("""
            UPDATE doc_source
               SET storage_path = #{storagePath},
                   updated_at = updated_at
             WHERE id = #{id}
            """)
    int updateStoragePath(@Param("id") Long id, @Param("storagePath") String storagePath);

    /**
     * 按文档 ID 查已删行（用于区分"不存在"与"已删除"给前端更准确的提示）。
     *
     * @param id 文档 ID
     * @return 记录（可能是软删行），不存在返回 null
     */
    @Select("SELECT * FROM doc_source WHERE id = #{id}")
    DocSource selectByIdIgnoreDeleted(@Param("id") Long id);

    /**
     * 列出某文档下的全部章节 ID（硬删时用于清理子表，避免外键阻塞）。
     *
     * @param docId 文档 ID
     * @return 章节 ID 列表
     */
    @Select("SELECT id FROM doc_section WHERE doc_id = #{docId}")
    List<Long> selectSectionIds(@Param("docId") Long docId);
}
