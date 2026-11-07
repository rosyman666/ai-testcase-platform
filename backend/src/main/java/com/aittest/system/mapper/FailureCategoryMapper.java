package com.aittest.system.mapper;

import com.aittest.system.domain.FailureCategory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 失败分类字典 Mapper。
 *
 * @author 寇豆码
 */
@Mapper
public interface FailureCategoryMapper extends BaseMapper<FailureCategory> {
}
