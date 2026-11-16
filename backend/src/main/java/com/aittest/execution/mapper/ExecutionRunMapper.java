package com.aittest.execution.mapper;

import com.aittest.execution.domain.ExecutionRun;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code execution_run} 的数据访问：全部走 {@link BaseMapper}，无需手写 XML。
 *
 * <p>列表/详情都是简单的条件查询 + 排序，{@code LambdaQueryWrapper} 足够表达；
 * 手写 XML 只会多一份要同步维护的列清单（本项目手写 XML 只用在 BaseMapper 拼不出来的地方：
 * {@code ON DUPLICATE KEY UPDATE}、{@code FOR UPDATE SKIP LOCKED}、批量 CASE 更新）。</p>
 *
 * @author 寇豆码
 */
@Mapper
public interface ExecutionRunMapper extends BaseMapper<ExecutionRun> {
}
