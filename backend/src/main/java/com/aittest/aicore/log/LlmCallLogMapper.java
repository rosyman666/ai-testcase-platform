package com.aittest.aicore.log;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 模型调用台账 Mapper。
 *
 * <p>写只有一条路：{@link #append}。读能力继承 {@link BaseMapper}，给 {@code metrics} 做聚合只读
 * （见 §11.1 D-10：台账归 {@code aicore}，`metrics` 跨包只读，不反向依赖）。</p>
 *
 * <p><b>刻意不提供 {@code update} 用法</b>：{@code llm_call_log} 是只追加表，改一行账
 * 就等于让"已发生的成本"变成可以重写的变量。要纠错就追加一行冲账，不要改历史。</p>
 *
 * @author 寇豆码
 */
@Mapper
public interface LlmCallLogMapper extends BaseMapper<LlmCallLog> {

    /**
     * 追加一条台账。
     *
     * <p>{@code created_at} <b>不在列表里</b>：由 DDL 默认值 {@code CURRENT_TIMESTAMP(3)} 求值。
     * 理由见 {@code resources/mapper/LlmCallLogMapper.xml} 顶部注释。</p>
     *
     * @param log 台账实体（{@code id} 由调用方用雪花生成器填好）
     * @return 影响行数（应为 1）
     */
    int append(@Param("log") LlmCallLog log);
}
