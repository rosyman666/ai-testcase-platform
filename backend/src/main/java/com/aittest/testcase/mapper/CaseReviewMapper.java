package com.aittest.testcase.mapper;

import com.aittest.testcase.domain.CaseReview;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code case_review} 的数据访问：<b>只有 INSERT 与 SELECT，没有 UPDATE/DELETE</b>。
 *
 * <p>台账只追加（见 {@link CaseReview} 类注释），所以这里<b>故意不提供</b>任何改历史的入口。
 * 若某天确实需要"改一条评审"，正确的做法是再插一行新动作，而不是 update 旧行——
 * 那时也不该在这个 Mapper 上开口子，而应该先想清楚"历史被改写后指标怎么算"。</p>
 *
 * <p>查询一律走 {@code BaseMapper} + lambda wrapper（按 {@code case_id} 取历史、按
 * {@code created_at} 升序），不写 XML：只有一条 order by，没有多表 join，
 * 手写 SQL 只会多一处要同步的列名。</p>
 *
 * @author 寇豆码
 */
@Mapper
public interface CaseReviewMapper extends BaseMapper<CaseReview> {
}
