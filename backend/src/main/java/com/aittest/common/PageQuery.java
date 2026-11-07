package com.aittest.common;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 统一分页入参（见 §4.1：{@code page} 从 1 开始，{@code size} 默认 20、上限 100，{@code sort} 形如 {@code createdAt,desc}）。
 *
 * <p>上限截断在 {@link #sizeOrDefault()} 里做而不是用校验注解：超限属于"友好降级"而非"参数错误"。
 * 前端分页组件一次误传 {@code size=500} 就整页 40001 失败，体验很差；静默截到 100
 * 既保护数据库又不会让用户看到报错。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString
public class PageQuery {

    /** 默认每页条数。 */
    public static final int DEFAULT_SIZE = 20;

    /** 每页条数硬上限（防止 {@code size=999999} 拖垮数据库）。 */
    public static final int MAX_SIZE = 100;

    /** 页码，从 1 开始。 */
    private Integer page = 1;

    /** 每页条数（读取时截断到 [1, 100]）。 */
    private Integer size = DEFAULT_SIZE;

    /** 排序表达式，如 {@code createdAt,desc}；为空时由各 service 决定默认排序。 */
    private String sort;

    /**
     * 有效页码（至少 1）。
     *
     * @return 页码
     */
    public int pageOrDefault() {
        return page == null || page < 1 ? 1 : page;
    }

    /**
     * 有效每页条数（截断到 [1, 100]）。**Bean 绑定后一切读取都必须走这个方法。**
     *
     * @return 条数
     */
    public int sizeOrDefault() {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    /**
     * SQL OFFSET。
     *
     * @return 偏移量
     */
    public long offset() {
        return (long) (pageOrDefault() - 1) * sizeOrDefault();
    }

    /**
     * 提取安全的排序列名（去掉方向标识与非法字符，防注入）。
     *
     * @param defaultColumn 未指定排序时的默认列（下划线命名）
     * @return 安全的下划线列名
     */
    public String sortColumnOrDefault(String defaultColumn) {
        String column = firstSortSegment();
        if (column == null || column.isBlank()) {
            return defaultColumn;
        }
        // 只允许字母/数字/下划线，防止 "createdAt; drop table" 之类注入
        String safe = column.replaceAll("[^A-Za-z0-9_]", "");
        if (safe.isEmpty()) {
            return defaultColumn;
        }
        return camelToUnderline(safe);
    }

    /**
     * 是否降序（未指定时默认降序，符合"最新在前"的列表习惯）。
     *
     * @return 降序返回 true
     */
    public boolean isDesc() {
        String raw = sort == null ? "" : sort.trim();
        int commaIndex = raw.indexOf(',');
        if (commaIndex < 0) {
            return true;
        }
        return !"asc".equalsIgnoreCase(raw.substring(commaIndex + 1).trim());
    }

    /**
     * 取排序表达式第一段（字段名）。
     *
     * @return 字段名，可能为 null
     */
    private String firstSortSegment() {
        if (sort == null || sort.isBlank()) {
            return null;
        }
        String raw = sort.trim();
        int commaIndex = raw.indexOf(',');
        return commaIndex < 0 ? raw : raw.substring(0, commaIndex);
    }

    /**
     * 驼峰转下划线（前端传 {@code createdAt}，数据库列是 {@code created_at}）。
     *
     * @param camel 驼峰名
     * @return 下划线名
     */
    private String camelToUnderline(String camel) {
        StringBuilder builder = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char current = camel.charAt(i);
            if (Character.isUpperCase(current)) {
                if (i > 0) {
                    builder.append('_');
                }
                builder.append(Character.toLowerCase(current));
            } else {
                builder.append(current);
            }
        }
        return builder.toString();
    }
}
