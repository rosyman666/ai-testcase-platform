package com.aittest.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.ToString;

/**
 * 统一分页出参（见 §4.1）。
 *
 * <p>字段名固定 {@code records/total/page/size/totalPages/hasNext}，前端 {@code DataTable}
 * 依赖这套命名；不要改成 {@code rows/count}，那会让所有列表页各写一套适配。</p>
 *
 * @param <T> 记录类型
 * @author 寇豆码
 */
@Getter
@ToString
public class PageResult<T> {

    /** 当前页记录。 */
    private final List<T> records;

    /**
     * 总记录数。
     *
     * <p><b>计数不是标识，序列化为 JSON number</b>（字段级 {@code @JsonSerialize} 覆盖
     * {@code JacksonConfig} 里 {@code Long → String} 的全局规则）；同理 {@code totalPages} /
     * {@code page} / {@code size} 都是 number，只有真正的 ID 才是 String。</p>
     *
     * <p>为什么必须显式覆盖：全局 {@code ToStringSerializer} 防的是<b>雪花 ID 超过
     * JS {@code Number.MAX_SAFE_INTEGER} 后在浏览器里被静默改写</b>；行数永远到不了 2^53，
     * 不属于该规则的保护对象。放任不管的后果是同一个分页体里
     * {@code "total":"1"} 与 {@code "totalPages":1} 自相矛盾，而前端 TS 又声明
     * {@code total: number}——类型在说谎、运行靠隐式转换（D-58 同族）。</p>
     *
     * <p>字段类型保持 {@code long} 而不是 {@code int}：{@link #of(IPage)} 那边要
     * {@code (int) page.getTotal()} 强转，计数超过 21 亿时会静默截断。为一个不存在的问题
     * 引入一个真实的截断点不值得。</p>
     */
    @JsonSerialize(using = LongNumberSerializer.class)
    private final long total;

    /** 当前页码（从 1 开始）。 */
    private final int page;

    /** 每页条数。 */
    private final int size;

    private PageResult(List<T> records, long total, int page, int size) {
        this.records = records == null ? Collections.emptyList() : records;
        this.total = Math.max(total, 0L);
        this.page = Math.max(page, 1);
        this.size = size < 1 ? PageQuery.DEFAULT_SIZE : size;
    }

    /**
     * 构造分页结果。
     *
     * @param records 当前页记录
     * @param total   总记录数
     * @param page    当前页码
     * @param size    每页条数
     * @param <T>     记录类型
     * @return 分页结果
     */
    public static <T> PageResult<T> of(List<T> records, long total, int page, int size) {
        return new PageResult<>(records, total, page, size);
    }

    /**
     * 由 MyBatis-Plus 的 {@link IPage} 转换（分页插件已执行 count）。
     *
     * @param page IPage 结果
     * @param <T>  记录类型
     * @return 分页结果
     */
    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getRecords(), page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    /**
     * 空分页结果（查询无数据时返回，前端不必写判空分支）。
     *
     * @param query 入参（用于回填 page/size）
     * @param <T>   记录类型
     * @return 空结果
     */
    public static <T> PageResult<T> empty(PageQuery query) {
        return new PageResult<>(Collections.emptyList(), 0L, query.pageOrDefault(), query.sizeOrDefault());
    }

    /**
     * 总页数。
     *
     * @return 总页数（total=0 时为 0）
     */
    public int getTotalPages() {
        if (size <= 0) {
            return 0;
        }
        return (int) ((total + size - 1) / size);
    }

    /**
     * 是否还有下一页。
     *
     * @return 有下一页返回 true
     */
    public boolean isHasNext() {
        return page < getTotalPages();
    }

    /**
     * 把 {@code long} 写成 JSON <b>number</b> 的序列化器（字段级覆盖全局 {@code Long → String}）。
     *
     * <p><b>为什么不是 {@code NumberSerializers.LongSerializer}</b>：那个类<b>没有无参构造</b>
     * （只有 {@code LongSerializer(Class<?>)} 等），而 {@code @JsonSerialize(using = ...)} 由
     * Jackson 通过 {@code ClassUtil.createInstance} 实例化，只认无参构造。用它会在第一次
     * 序列化时直接抛 {@code InvalidDefinition: LongSerializer has no default (no arg) constructor}
     * ——而且这条错只在真正走到分页出参时才炸，编译期与普通单测都发现不了
     * （{@code PageResultTest} 就是为这件事加的）。所以这里自己实现一个语义完全等价的：
     * {@code generator.writeNumber(value.longValue())}。</p>
     */
    public static final class LongNumberSerializer extends JsonSerializer<Number> {

        /** Jackson 要求序列化器可由无参构造实例化。 */
        public LongNumberSerializer() {
            // 无状态，无需初始化
        }

        @Override
        public void serialize(Number value, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            if (value == null) {
                generator.writeNull();
                return;
            }
            generator.writeNumber(value.longValue());
        }
    }
}
