package com.aittest.testpoint.dto;

import java.util.List;

/**
 * 一篇文档的抽取汇总报告。
 *
 * <p><b>为什么要有汇总而不是只给 {@code List<TestPointExtractResult>}</b>：
 * 调用方（前端 / 后续的异步任务）最想知道的是"这篇抽成了什么样"——几个单元、成了几个、
 * 总共新增几条、有几个要人工看一眼。让每个调用方自己去 list 上做聚合，
 * 迟早出现两种口径（一个算 parsedCount 之和、一个算 insertedCount 之和）。</p>
 *
 * @param docNo       文档单号（给人看的定位，不用再去查 docId）
 * @param unitCount   待分析单元总数（OpenAPI 数 operation、纯文本数 section）
 * @param successCount 抽取成功的单元数
 * @param failedCount 抽取失败的单元数（验收 ⑤：失败不影响其它单元）
 * @param parsedCount 模型返回的测试点总数（去重前）
 * @param insertedCount 实际新增的测试点数（去重后）
 * @param results     逐单元结果，顺序与单元顺序一致
 * @author 寇豆码
 */
public record TestPointExtractReport(String docNo,
                                     int unitCount,
                                     int successCount,
                                     int failedCount,
                                     int parsedCount,
                                     int insertedCount,
                                     List<TestPointExtractResult> results) {

    /**
     * 由逐单元结果汇总。
     *
     * @param docNo   文档单号
     * @param results 逐单元结果
     * @return 汇总报告
     */
    public static TestPointExtractReport of(String docNo, List<TestPointExtractResult> results) {
        int success = 0;
        int parsed = 0;
        int inserted = 0;
        for (TestPointExtractResult r : results) {
            if (r.success()) {
                success++;
                parsed += r.parsedCount();
                inserted += r.insertedCount();
            }
        }
        return new TestPointExtractReport(docNo, results.size(), success,
                results.size() - success, parsed, inserted, results);
    }
}
