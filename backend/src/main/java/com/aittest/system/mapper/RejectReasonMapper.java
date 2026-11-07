package com.aittest.system.mapper;

import com.aittest.system.domain.RejectReason;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 驳回原因字典 Mapper。
 *
 * @author 寇豆码
 */
@Mapper
public interface RejectReasonMapper extends BaseMapper<RejectReason> {
}
