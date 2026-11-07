package com.aittest.common.enums;

/**
 * 可编码枚举的公共契约：所有对外暴露的枚举都必须同时提供 {@code code}（存库、传输、统计口径）
 * 与 {@code label}（中文展示），**永远不要用 {@code ordinal()} 存库**（枚举顺序变更会让历史数据错乱）。
 *
 * @author 寇豆码
 */
public interface CodedEnum {

    /**
     * 枚举编码（大写英文/下划线，存 VARCHAR）。
     *
     * @return 编码
     */
    String code();

    /**
     * 中文文案（仅用于展示）。
     *
     * @return 文案
     */
    String label();

    /**
     * 展示排序号（升序）。
     *
     * @return 排序号
     */
    default int sortNo() {
        return 0;
    }
}
