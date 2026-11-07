package com.aittest.aicore.prompt;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 提示词模板 Mapper。
 *
 * <p>除 {@link BaseMapper} 的通用能力外，只有四个动作：取某 code 的生效版本、取最大版本号、
 * 把某 code 的旧版本全部置为失效、按 code 列历史版本。</p>
 *
 * <p><b>两处 {@code updated_at = updated_at} 是刻意的</b>（与 §11.1 D-22 同一个坑）：
 * {@code prompt_template.updated_at} 上有 {@code ON UPDATE CURRENT_TIMESTAMP(3)}，
 * 任何 {@code UPDATE} 都会把它抹成"执行时刻"。而"把旧版本置为失效"是<b>版本切换</b>，
 * 不是内容修改——抹掉 {@code updated_at} 之后，"这版模板最后改于什么时候"就永久丢失了，
 * 而这正是判断"某批用例用的模板是不是最新版"的依据。</p>
 *
 * @author 寇豆码
 */
@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplate> {

    /**
     * 取某个 code 当前生效的版本。
     *
     * @param code 模板编码
     * @return 生效版本；没有返回 {@code null}
     */
    @Select("""
            SELECT id, code, version, scene, name, content, output_schema, model_hint,
                   is_active, created_at, updated_at
              FROM prompt_template
             WHERE code = #{code}
               AND is_active = 1
             LIMIT 1
            """)
    PromptTemplate selectActiveByCode(@Param("code") String code);

    /**
     * 取某个 code 的最大版本号（新版本 = 最大值 + 1）。
     *
     * @param code 模板编码
     * @return 最大版本号；一条都没有时返回 {@code null}
     */
    @Select("SELECT MAX(version) FROM prompt_template WHERE code = #{code}")
    Integer selectMaxVersion(@Param("code") String code);

    /**
     * 把某个 code 的全部版本置为失效。
     *
     * <p>{@code updated_at = updated_at}：抑制 {@code ON UPDATE}，理由见类注释。</p>
     *
     * @param code 模板编码
     * @return 影响行数
     */
    @Update("""
            UPDATE prompt_template
               SET is_active = 0,
                   updated_at = updated_at
             WHERE code = #{code}
               AND is_active = 1
            """)
    int deactivateByCode(@Param("code") String code);

    /**
     * 按主键把某版本置为生效。
     *
     * <p><b>调用前必须先 {@link #deactivateByCode(String)}</b>：本语句只负责"把这一行点亮"，
     * 不负责"把别人熄灭"。两件事分开写是有意的——合成一条 {@code CASE WHEN} 的 UPDATE
     * 在 MySQL 里会因为唯一约束/行锁顺序不同出现死锁（两个事务分别先锁不同行）。</p>
     *
     * <p>{@code updated_at = updated_at}：{@code is_active} 属于"版本管理状态"，
     * 不是"内容修改"，不应改写"内容最后修改时间"。</p>
     *
     * @param id 主键
     * @return 影响行数（0 说明该 id 不存在）
     */
    @Update("""
            UPDATE prompt_template
               SET is_active = 1,
                   updated_at = updated_at
             WHERE id = #{id}
            """)
    int activateById(@Param("id") Long id);

    /**
     * 按 code 列全部版本（新→旧），供前端版本列表。
     *
     * @param code 模板编码
     * @return 版本列表
     */
    @Select("""
            SELECT id, code, version, scene, name, output_schema, model_hint,
                   is_active, created_at, updated_at
              FROM prompt_template
             WHERE code = #{code}
             ORDER BY version DESC
            """)
    List<PromptTemplate> selectVersionsByCode(@Param("code") String code);
}
