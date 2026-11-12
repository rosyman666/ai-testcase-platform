package com.aittest.export.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Excel 双 sheet 的表头与统计项定义（docs/07 §4.1）。
 *
 * <p>中文列头由 EasyExcel 直接写进 xlsx（xlsx 内部本身是 UTF-8 XML），不依赖平台默认编码；
 * 显式把 {@code -Dfile.encoding} 交给 JDK 17 的 UTF-8 默认值即可，不在代码里做
 * 「转成 ISO-8859-1 再转回来」这类历史补丁。</p>
 *
 * @author 寇豆码
 */
public final class ExcelColumnDef {

    /** 「用例」sheet 的 12 列，顺序与 {@code CaseRow} 一致。 */
    public static final List<String> CASE_HEADERS = List.of(
            "序号", "用例标题", "前置条件", "测试步骤", "预期结果", "优先级",
            "用例类型", "状态", "置信度", "模型", "提示词版本", "创建时间(UTC)");

    /** 「统计」sheet 两列：统计项 / 值。 */
    public static final List<String> STAT_HEADERS = List.of("统计项", "值");

    /** 「统计」sheet 的 5 个统计项标签（最小集合，不再扩到 T11 指标）。 */
    public static final List<String> STAT_FIELD_LABELS = List.of(
            "导出时间(UTC)", "任务号", "生效导出条件", "导出用例数", "状态分布");

    private ExcelColumnDef() {
        // 纯静态常量类，禁止实例化
    }

    /**
     * 把单级表头转成 EasyExcel 需要的 {@code List<List<String>>} 形态（每列一个单元素列表）。
     *
     * @param headers 列头
     * @return 动态表头
     */
    public static List<List<String>> toHead(List<String> headers) {
        List<List<String>> head = new ArrayList<>(headers.size());
        for (String header : headers) {
            head.add(Collections.singletonList(header));
        }
        return head;
    }
}
