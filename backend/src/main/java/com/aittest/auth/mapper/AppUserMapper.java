package com.aittest.auth.mapper;

import com.aittest.auth.domain.AppUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper。
 *
 * <p>只需 {@link BaseMapper} 的内置能力（按用户名查一条），不写 XML：
 * 单表等值查询用 Wrapper 已经足够，过度使用 XML 反而降低可读性。</p>
 *
 * @author 寇豆码
 */
@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {
}
