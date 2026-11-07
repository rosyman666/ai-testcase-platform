package com.aittest.aicore.log;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 模型单价读取（{@code model_price}）——<b>只读</b>。
 *
 * <p>放在 {@code aicore/log} 而不是 {@code metrics}，理由与 §11.1 D-10 同源：
 * <b>记账要算成本，就得能读到单价</b>。而记账发生在 {@code aicore}（装饰器里），
 * 若单价读取放在 {@code metrics}，就变成 `aicore → metrics` 的反向依赖。
 * T11 的 {@code ModelPriceService} 因此改为<b>复用</b>本接口（它只额外承担"维护单价"的写入口），
 * 不另建一份，否则"两处查单价"迟早有一处忘了调价。</p>
 *
 * <p><b>为什么按 {@code effective_from DESC} 全取、在内存里挑</b>：同一个 model 会有多条历史价
 * （调价不覆盖历史，否则历史成本会跟着新价一起变）。SQL 里想"每个 model 只取最新一条"
 * 需要窗口函数或自连接；而这张表只有几十行，全量拉回按序取第一条更简单，也更不容易写错。
 * 关键是<b>避免</b> {@code GROUP BY model} 却 {@code SELECT input_price_micro} 这种依赖
 * MySQL 宽容写法的语句（§6 T11 ⑦ 明令禁止）。</p>
 *
 * @author 寇豆码
 */
@Mapper
public interface ModelPriceMapper {

    /**
     * 取全部启用中的单价行，按 {@code model, effective_from DESC} 排序。
     *
     * @return 单价行（可能为空列表）
     */
    @Select("""
            SELECT model,
                   input_price_micro,
                   output_price_micro,
                   effective_from
              FROM model_price
             WHERE enabled = 1
             ORDER BY model, effective_from DESC
            """)
    List<ModelPrice> selectEnabledOrderByEffectiveFromDesc();
}
