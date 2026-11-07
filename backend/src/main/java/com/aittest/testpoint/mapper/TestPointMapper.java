package com.aittest.testpoint.mapper;

import com.aittest.testpoint.domain.TestPoint;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 测试点 Mapper。
 *
 * <p><b>只加了一个自定义查询，其余走 {@code BaseMapper}</b>：查询条件里没有"需要看见逻辑删行"
 * 这种绕过点（{@code test_point} 没有软删，只有 {@code status}），所以不必像
 * {@code DocSourceMapper#selectByFileHash} 那样手写 XML。</p>
 *
 * <p><b>为什么列出活动点要按 {@code status='ACTIVE'} 显式过滤而不是默认全查</b>：
 * 重复抽取后，一篇文档下会同时存在 {@code ACTIVE}（本轮产出/沿用）与 {@code SUPERSEDED}
 * （上一轮有、这一轮没有了）两类行。评审页只该看到前者；把两类混着返回，
 * 用户会看到"我明明删掉的点怎么又出现了"。</p>
 *
 * @author 寇豆码
 */
@Mapper
public interface TestPointMapper extends BaseMapper<TestPoint> {

    /**
     * 取某文档下当前活动的测试点（按创建顺序）。
     *
     * <p><b>不用 MyBatis-Plus 的 {@code selectList} 顺手写</b>：这里明确写了排序键。
     * AI 抽取是逐个 operation/section 调用的，落库顺序就是"哪个接口先抽"，
     * 不排一下前端列表会变成随机顺序，评审时无法按文档结构对照着看。</p>
     *
     * @param docId 文档 ID
     * @return 活动测试点列表
     */
    @Select("SELECT * FROM test_point WHERE doc_id = #{docId} AND status = 'ACTIVE' ORDER BY id")
    List<TestPoint> selectActiveByDocId(@Param("docId") Long docId);

    /**
     * 取某文档下的<b>全部</b>测试点（含 {@code SUPERSEDED} 与 {@code DISABLED}）。
     *
     * <p><b>为什么必须看得见已取代的行</b>：重复抽取的去重判定要回答"这条点以前有没有抽到过"，
     * 而上一轮有、这一轮没有的点正是 {@code SUPERSEDED}。只查 {@code ACTIVE} 的话，
     * 这类点会被当成全新点重新插入——要么撞唯一索引 {@code 1062}，
     * 要么（若先删后插）把人工修订过的描述一起冲掉。</p>
     *
     * @param docId 文档 ID
     * @return 全部测试点
     */
    @Select("SELECT * FROM test_point WHERE doc_id = #{docId} ORDER BY id")
    List<TestPoint> selectByDocId(@Param("docId") Long docId);
}
