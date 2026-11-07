package com.aittest.doc.mapper;

import com.aittest.doc.domain.ApiOperation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 接口操作 Mapper。
 *
 * <p>与 {@link DocSectionMapper} 同理，{@code api_operation} 没有 {@code deleted} 列，
 * {@code DELETE} 是物理删。</p>
 *
 * <p><b>调用顺序硬约束</b>：本表有 {@code uk_api_operation_doc_method_path (doc_id, method, path)}，
 * 重传同一文档时新旧行的三元组完全相同，**先插必撞键**。所以顺序只能是
 * "先 {@link #deleteByDocId} 再插入"，不能反过来。
 * 这也意味着一件事：重传后 {@code api_operation.id} 会变化（物理删后重建），
 * 但条数不变——验收时不要把"id 变了"当成"覆盖失败"。</p>
 *
 * @author 寇豆码
 */
@Mapper
public interface ApiOperationMapper extends BaseMapper<ApiOperation> {

    /**
     * 物理删除某文档下的全部接口操作。
     *
     * @param docId 文档 ID
     * @return 删除行数（旧版本可能是 0）
     */
    @Delete("DELETE FROM api_operation WHERE doc_id = #{docId}")
    int deleteByDocId(@Param("docId") Long docId);

    /**
     * 统计某文档的接口操作数（用于回填 {@code doc_source.operation_count}）。
     *
     * @param docId 文档 ID
     * @return 接口数
     */
    default long countByDocId(Long docId) {
        return selectCount(Wrappers.<ApiOperation>lambdaQuery().eq(ApiOperation::getDocId, docId));
    }
}
