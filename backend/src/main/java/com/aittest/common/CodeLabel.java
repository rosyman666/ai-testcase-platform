package com.aittest.common;

import com.aittest.common.enums.CodedEnum;

/**
 * 字典项统一出参：{@code {code, label, sortNo}}。
 *
 * <p>前端所有下拉框/筛选器都消费这个结构，所以字典的**唯一形状**就是它——
 * 数据库字典表（驳回原因、失败分类）与代码枚举（类型、优先级、状态）都在 service 层
 * 归一化成 {@code List<CodeLabel>} 再返回，前端不需要区分"这个字典来自表还是来自枚举"。</p>
 *
 * @param code   编码（存库值）
 * @param label  中文文案
 * @param sortNo 展示排序号（升序）
 * @author 寇豆码
 */
public record CodeLabel(String code, String label, int sortNo) {

    /**
     * 由可编码枚举构造。
     *
     * @param codedEnum 枚举
     * @return 字典项
     */
    public static CodeLabel of(CodedEnum codedEnum) {
        return new CodeLabel(codedEnum.code(), codedEnum.label(), codedEnum.sortNo());
    }
}
