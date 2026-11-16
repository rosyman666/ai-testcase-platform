package com.aittest.execution.service;

import com.aittest.testcase.domain.TestCase;
import com.aittest.testcase.mapper.TestCaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 「{@code junit_ref} → 用例 id」的唯一查询入口。
 *
 * <p><b>为什么要单独抽出来</b>：导入（#37）与重跑映射（#42）都要做同一件事，
 * 而这件事里有两个容易写错、写错后不报错的细节：</p>
 * <ol>
 *   <li><b>不兜底</b>：没有 {@code junit_ref} 的用例（从未导出过）不参与映射。任何
 *       "按 seq_no / title 再猜一次"的兜底都会把"这条没导出过"变成"这条长得像"，
 *       映射率就此失去意义；</li>
 *   <li><b>重复编号的处理</b>：同一个 {@code junit_ref} 对应两条用例说明库里已有脏数据
 *       （并发导出的极端情况）。保留 id 最小的一条并留 WARN——映射率因此低于 100% 是
 *       <b>正确</b>结果，而不是要修掉的噪声。</li>
 * </ol>
 * <p>两处各写一遍，早晚会有一处忘记第 2 条，而那种偏差在页面上完全看不出来。</p>
 *
 * @author 寇豆码
 */
@Component
public class JunitRefLookup {

    private static final Logger log = LoggerFactory.getLogger(JunitRefLookup.class);

    /** 用例 Mapper（只读）。 */
    private final TestCaseMapper testCaseMapper;

    public JunitRefLookup(TestCaseMapper testCaseMapper) {
        this.testCaseMapper = testCaseMapper;
    }

    /**
     * 建「{@code junit_ref} → 用例 id」映射（一次查询）。
     *
     * <p>{@code orderByAsc(id)} 是为了让重复编号的取舍结果<b>稳定</b>：
     * 没有排序时 MySQL 返回的顺序依赖执行计划，同一份数据两次导入可能映射到不同用例。</p>
     *
     * @param taskId 任务 ID
     * @return 映射（键是 {@code GeneratedCase_NNN}）；任务的用例从未导出过时返回空 Map
     */
    public Map<String, Long> load(Long taskId) {
        List<TestCase> cases = testCaseMapper.selectList(new LambdaQueryWrapper<TestCase>()
                .select(TestCase::getId, TestCase::getJunitRef)
                .eq(TestCase::getTaskId, taskId)
                .isNotNull(TestCase::getJunitRef)
                .orderByAsc(TestCase::getId));
        Map<String, Long> refToCaseId = new HashMap<>(Math.max(cases.size(), 1) * 2);
        int duplicates = 0;
        for (TestCase item : cases) {
            String ref = item.getJunitRef();
            if (ref == null || ref.isBlank()) {
                continue;
            }
            if (refToCaseId.putIfAbsent(ref, item.getId()) != null) {
                duplicates++;
            }
        }
        if (duplicates > 0) {
            log.warn("[EXECUTION] 任务下存在重复 junit_ref taskId={} 重复条数={}（保留 id 最小的一条）",
                    taskId, duplicates);
        }
        return refToCaseId;
    }

    /**
     * 取类名对应的编号键（{@code com.aittest.generated.GeneratedCase_001} → {@code GeneratedCase_001}）。
     *
     * <p>{@code junit_ref} 存的就是简单类名（与 {@code JUnitNaming.simpleClassName} 同口径），
     * 而 JUnit XML 的 {@code classname} 是全限定名，所以只比最后一段。
     * <b>不做 {@code contains} 匹配</b>：{@code GeneratedCase_0011} 会误命中
     * {@code GeneratedCase_001}，于是全部错位且不报错。</p>
     *
     * @param className 全限定类名（可空）
     * @return 最后一段（可能为空串）
     */
    public static String refOf(String className) {
        if (className == null) {
            return "";
        }
        String trimmed = className.trim();
        int index = trimmed.lastIndexOf('.');
        return index < 0 ? trimmed : trimmed.substring(index + 1);
    }
}
