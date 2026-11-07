package com.aittest.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
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

    /** 总记录数。 */
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
}
