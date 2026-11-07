package com.aittest.doc.mapper;

import com.aittest.doc.domain.DocSection;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 章节 Mapper。
 *
 * <p>{@code doc_section} **没有 {@code deleted} 列**（只追加表，§3.2.1），所以 MP 的
 * 逻辑删除对这张表不生效，{@code DELETE} 就是真物理删。这里额外提供一个显式方法，
 * 是为了让"重传时必须先清空旧章节"这个动作在代码里有名字、能被 grep 到，
 * 而不是散落在 service 里的 {@code delete(wrapper)}。</p>
 *
 * @author 寇豆码
 */
@Mapper
public interface DocSectionMapper extends BaseMapper<DocSection> {

    /**
     * 物理删除某文档下的全部章节。
     *
     * <p><b>必须在插入新章节之前调用</b>：否则重传同一文档后章节条数会翻倍
     * （§3.2.2 实现约束 2）。</p>
     *
     * @param docId 文档 ID
     * @return 删除行数（旧版本可能是 0）
     */
    @Delete("DELETE FROM doc_section WHERE doc_id = #{docId}")
    int deleteByDocId(@Param("docId") Long docId);

    /**
     * 统计某文档的章节数（用于解析后回填 {@code doc_source.section_count}）。
     *
     * @param docId 文档 ID
     * @return 章节数
     */
    default long countByDocId(Long docId) {
        return selectCount(Wrappers.<DocSection>lambdaQuery().eq(DocSection::getDocId, docId));
    }
}
