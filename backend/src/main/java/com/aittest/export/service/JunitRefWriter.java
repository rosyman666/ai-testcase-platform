package com.aittest.export.service;

import com.aittest.export.dto.CaseSnapshot;
import com.aittest.export.support.JUnitNaming;
import com.aittest.testcase.mapper.TestCaseMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把「本次 JUnit 导出的第 N 行 → 用例」固化到 {@code test_case.junit_ref}（T10 的前置接线）。
 *
 * <p><b>要解决的问题</b>：JUnit 导出给每条用例分配的类名是 {@code GeneratedCase_NNN}，
 * NNN 是<b>当次导出的查询结果第 N 行</b>的序号（见 {@link JUnitNaming#simpleClassName}）。
 * 这个序号只存在于导出那一刻的内存里——导出后文件就交给人去跑了。等执行结果（JUnit XML）
 * 回来时，导入侧只拿到 {@code classname="...GeneratedCase_007"}，而 {@code test_case}
 * 上原本没有任何列能表达"上次导出的第 7 行是哪条用例"。</p>
 *
 * <p>于是只剩两条路：</p>
 * <ol>
 *   <li>导入时按「与导出相同的筛选条件重算行号」——只要两次之间新增了用例、有人评审把
 *       DRAFT 改成 ACCEPTED、或删掉了 {@code doc_id} 关联，行号就<b>整体错位</b>，
 *       而且映射率仍是 100%，全错却全绿，没有异常也没有日志。</li>
 *   <li><b>本类</b>：导出那一刻就把编号写进库，导入时按 {@code (taskId, junitRef)} 精确匹配。
 *       编号一旦落库就是事实，后续新增/评审/删除都改不动它。</li>
 * </ol>
 * <p>本项目反复登记的「不许静默通过」（D-39 / D-41 / D-46）要求选第 2 条：宁可导入时
 * 映射不上（{@code mapped_count < total_count}，页面上一眼可见），也不能全错还显示全绿。</p>
 *
 * <p><b>语义：同一任务只保留「最近一次导出」的编号。</b> 每次导出先
 * {@link TestCaseMapper#clearJunitRef} 清空本任务的全部编号，再按本批顺序写回。
 * 清空是刻意的——上一次导出的旧编号与这一次的第 N 行是两套互不相容的编号体系，
 * 并存会让同一个 {@code GeneratedCase_007} 同时指向两条用例，导入时"命中"就变成了
 * 新的静默错误来源。代价是：导出新文件之后用旧文件导入会全部映射不上（{@code mapped_rate}
 * 掉到 0），这是<b>可见</b>的失败方向，符合纪律。</p>
 *
 * <p><b>为什么按 500 条一批</b>：一次导出上限 5000 条（{@code export.max-rows}），
 * 逐条 UPDATE 就是 5000 次往返 + 5000 次行锁；一条 {@code CASE WHEN} 语句写 500 行，
 * 10 次往返写完，也不会把 {@code max_allowed_packet} 撑爆。批大小与
 * {@code ExecutionWriteService} 的明细批量保持一致，便于统一调参。</p>
 *
 * @author 寇豆码
 */
@Component
public class JunitRefWriter {

    private static final Logger log = LoggerFactory.getLogger(JunitRefWriter.class);

    /** 单批写回条数（与执行结果明细的批量大小一致）。 */
    private static final int BATCH_SIZE = 500;

    private final TestCaseMapper testCaseMapper;

    public JunitRefWriter(TestCaseMapper testCaseMapper) {
        this.testCaseMapper = testCaseMapper;
    }

    /**
     * 把快照里的「行号 → 用例」写回 {@code test_case.junit_ref}。
     *
     * <p><b>不抛异常给"没有可写数据"</b>：{@code caseIds} 为空时直接返回。快照本身保证
     * 至少 1 行（空集在 {@code CaseSnapshotBuilder} 里已抛 40003），因此正常路径不会走到
     * 这个分支；留着是为了让"调用方传了半构造的快照"变成无操作，而不是
     * {@code NullPointerException} 冒到全局处理器变成 50000。</p>
     *
     * @param snapshot 导出快照（{@code taskId} 与 {@code caseIds} 由 builder 填充）
     */
    public void persist(CaseSnapshot snapshot) {
        if (snapshot == null || snapshot.getCaseIds() == null || snapshot.getCaseIds().isEmpty()) {
            log.warn("[EXPORT] junit_ref 写回跳过：快照里没有 caseIds（导出内容仍会正常产出）");
            return;
        }
        Long taskId = snapshot.getTaskId();
        if (taskId == null) {
            log.warn("[EXPORT] junit_ref 写回跳过：快照缺少 taskId（导出内容仍会正常产出）");
            return;
        }
        List<Long> caseIds = snapshot.getCaseIds();

        // 先清空：旧编号与本次行号是两套体系，并存会让导入"命中"到不在本次文件里的用例
        int cleared = testCaseMapper.clearJunitRef(taskId);
        int written = 0;
        for (int from = 0; from < caseIds.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, caseIds.size());
            List<TestCaseMapper.JunitRef> refs = new ArrayList<>(to - from);
            for (int index = from; index < to; index++) {
                // index 是「本批导出结果」里的 0 基下标，编号必须从 1 开始且与
                // JUnitExportService 的写入循环（seq = index + 1）保持同一口径
                refs.add(new TestCaseMapper.JunitRef(caseIds.get(index),
                        JUnitNaming.simpleClassName(index + 1)));
            }
            written += testCaseMapper.markJunitRef(refs);
        }
        log.info("[EXPORT] junit_ref 已固化 taskId={} 本批={} 条 清除旧编号={} 条 写回={} 行",
                taskId, caseIds.size(), cleared, written);
    }
}
