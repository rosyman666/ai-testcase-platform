package com.aittest.execution.service;

import com.aittest.common.enums.ExecutionStatus;
import com.aittest.common.enums.FailureCategoryCode;
import com.aittest.execution.domain.ExecutionResult;
import com.aittest.execution.domain.ExecutionRun;
import com.aittest.execution.mapper.ExecutionResultMapper;
import com.aittest.execution.mapper.ExecutionRunMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 执行结果的<b>写侧</b>：批次插入、明细批量写入与映射回填、失败时的批次补偿删除。
 *
 * <p><b>为什么和 {@link ExecutionImportService} 分成两个类</b>：{@code IdGen.generateWithRetry}
 * 的 javadoc 有一条硬约束——{@code action} 必须是<b>独立事务边界</b>。Spring 在事务内抛出
 * {@code DuplicateKeyException} 后会把该事务标记 rollback-only，在同一事务里重试必然继续失败。
 * 因此插入批次的动作（{@code insertRun}）必须是一个<b>没有</b> {@code @Transactional} 的方法
 * （单条 insert 自动提交，撞 UK 时由重试逻辑重新播种），而明细批量写入需要一个真事务
 * （几千条明细要么全进要么全不进）。两件事的事务语义相反，写在同一个类里迟早有人
 * 给类加一个类级 {@code @Transactional}，把重试机制悄悄废掉——那时表现为"偶尔导入失败，
 * 重试也没用"，且日志里只有一条撞键 WARN。</p>
 *
 * @author 寇豆码
 */
@Service
public class ExecutionWriteService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionWriteService.class);

    /** 单批写入/更新的行数（与 {@code JunitRefWriter} 一致，便于统一调参）。 */
    static final int BATCH_SIZE = 500;

    private final ExecutionRunMapper executionRunMapper;

    private final ExecutionResultMapper executionResultMapper;

    public ExecutionWriteService(ExecutionRunMapper executionRunMapper,
                                 ExecutionResultMapper executionResultMapper) {
        this.executionRunMapper = executionRunMapper;
        this.executionResultMapper = executionResultMapper;
    }

    /**
     * 插入一个执行批次（<b>单条 insert，故意不加 {@code @Transactional}</b>）。
     *
     * <p>它被 {@code IdGen.generateWithRetry} 当作 {@code action} 调用：撞
     * {@code uk_execution_run_run_no} 时必须能<b>整条语句回滚</b>后重新取号。
     * 自动提交的单条 insert 天然满足；若这里加了事务注解，被重试的那次插入会落在
     * 已标记 rollback-only 的事务里，重试永远失败。</p>
     *
     * <p>计数列在插入时一次写全（不做事后 UPDATE）：先插 0 再更新会留一个窗口，
     * 那里批次显示"0 条结果"，而明细已经在写了——页面上看起来像"导入成功但结果为空"。</p>
     *
     * @param runNo       业务单号（由 {@code IdGen} 分配）
     * @param taskId      结果对应的生成任务（<b>必须落库</b>：重跑映射与"这批属于哪个任务"都靠它）
     * @param name        批次名称（调用方保证非空）
     * @param sourceType  来源格式编码
     * @param fileName    原始文件名
     * @param total       结果总数
     * @param passed      通过数
     * @param failed      失败数
     * @param skipped     跳过数
     * @param mappedCount 映射到用例的条数
     * @param durationMs  批次总耗时（毫秒）
     * @return 已插入的实体（{@code id} 由 MyBatis-Plus 的 {@code ASSIGN_ID} 填充）
     */
    public ExecutionRun insertRun(String runNo, Long taskId, String name, String sourceType,
                                  String fileName, int total, int passed, int failed, int skipped,
                                  int mappedCount, long durationMs) {
        ExecutionRun run = new ExecutionRun();
        run.setRunNo(runNo);
        run.setTaskId(taskId);
        run.setName(name == null ? "" : name);
        run.setSourceType(sourceType);
        run.setFileName(fileName == null ? "" : fileName);
        run.setTotalCount(total);
        run.setPassedCount(passed);
        run.setFailedCount(failed);
        run.setSkippedCount(skipped);
        run.setMappedCount(mappedCount);
        run.setDurationMs(durationMs);
        // created_at / updated_at 不赋值：交给 DDL 默认值（UTC 会话时区）
        executionRunMapper.insert(run);
        return run;
    }

    /**
     * 批量写入明细并回填 {@code case_id}（一个事务：要么全进，要么全不进）。
     *
     * <p>两段分开批处理而不是一条 SQL 写完整个文件：单条 SQL 的占位符数量会随条数线性增长，
     * 5000 条 × 11 列 = 55000 个占位符，既逼近驱动/服务的语句长度上限，也让
     * {@code max_allowed_packet} 成为隐藏的失败点。500 条一批是两者之间的折中。</p>
     *
     * @param runId 批次 ID（会写到每一行明细上）
     * @param rows  明细（{@code id} 必须已赋值；{@code runId} 由本方法覆写）
     * @param pairs 结果 id ↔ 用例 id 配对（<b>只含命中项</b>；空列表表示一条都没映射上）
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveResults(Long runId, List<ExecutionResult> rows,
                            List<ExecutionResultMapper.CaseMapping> pairs) {
        for (ExecutionResult row : rows) {
            // 覆写而不是信任调用方：runId 只有本方法拿得到，写错了会撞 FK（50000）
            row.setRunId(runId);
        }
        int inserted = 0;
        for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, rows.size());
            inserted += executionResultMapper.insertBatch(rows.subList(from, to));
        }
        int mapped = 0;
        for (int from = 0; from < pairs.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, pairs.size());
            mapped += executionResultMapper.markCaseMapped(runId, pairs.subList(from, to));
        }
        // 归因放在同一个事务里：它只依赖刚写入的这些行，分出去反而会出现
        // "明细已提交但分类全空"的中间态被前端读到
        int triaged = triageFailed(runId, rows);
        log.info("[EXECUTION] 明细已写入 runId={} 插入={} 行 映射回填={} 行 规则归因={} 行",
                runId, inserted, mapped, triaged);
    }

    /**
     * 对本批<b>失败</b>行做规则归因，按分类分组批量落库。
     *
     * <p><b>只处理 FAILED</b>：PASSED/SKIPPED 没有"失败分类"这回事。给它们写一个分类
     * 会让 {@code categoryDistribution} 的合计大于失败数，饼图与"失败 3 条"的卡片对不上。</p>
     *
     * <p><b>分组而不是逐行 UPDATE</b>：5000 条失败结果逐行更新是 5000 次往返与行锁，
     * 分组的代价只有"最多 6 条语句"（分类是封闭集合）。且每条语句仍带
     * {@code triage_source <> 'MANUAL'}，人工结论不会被机器覆盖。</p>
     *
     * <p>{@code failureType} 传 {@code null} 而不是从别处取：解析阶段已把异常类前置进
     * {@code stackTrace} 首行（{@code FailureTypes#withHeader}），规则自己读得到。
     * 这样归因不依赖"解析中间态被正确传到写侧"这个隐含契约——那类依赖一旦断了，
     * 表现是"全部落到 UNKNOWN"，而 UNKNOWN 看起来像正常结果。</p>
     *
     * @param runId 批次 ID
     * @param rows  本批明细（只读，用于取 id/status/message/stack）
     * @return 被写入分类的行数（受影响行数，正常情况下等于失败条数）
     */
    private int triageFailed(Long runId, List<ExecutionResult> rows) {
        Map<String, List<Long>> idsByCategory = new LinkedHashMap<>();
        for (ExecutionResult row : rows) {
            if (!ExecutionStatus.FAILED.code().equals(row.getStatus())) {
                continue;
            }
            FailureCategoryCode category = FailureTriageRules.decide(
                    null, row.getFailureMessage(), row.getStackTrace());
            idsByCategory.computeIfAbsent(category.code(), key -> new ArrayList<>()).add(row.getId());
            // 同步内存对象：日志与调用方读到的就是将要落库的值，不是"猜的"
            row.setFailureCategory(category.code());
        }
        int updated = 0;
        for (Map.Entry<String, List<Long>> entry : idsByCategory.entrySet()) {
            List<Long> ids = entry.getValue();
            for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
                int to = Math.min(from + BATCH_SIZE, ids.size());
                updated += executionResultMapper.markFailureCategory(
                        runId, entry.getKey(), ids.subList(from, to));
            }
        }
        return updated;
    }

    /**
     * 删除批次（导入后段失败时的补偿动作）。
     *
     * <p><b>为什么要补偿</b>：批次行的插入必须独立提交（见类注释），因此它无法与明细写入
     * 共用一个事务。若明细写入失败就这么算了，库里会留下一个"有计数、没有明细"的批次：
     * 详情页显示总数 100、明细 0 条，看起来像分页坏了。删掉它，用户重试即可得到干净结果。</p>
     *
     * <p>{@code execution_result} 上是 {@code ON DELETE CASCADE}，因此这一条 DELETE
     * 会把已写入的明细一起清掉。</p>
     *
     * @param runId 批次 ID
     * @return 删除行数（0 表示已被删或不存在，不视为错误）
     */
    public int deleteRun(Long runId) {
        int deleted = executionRunMapper.deleteById(runId);
        log.warn("[EXECUTION] 导入后段失败，已补偿删除批次 runId={}（删除行数={}）", runId, deleted);
        return deleted;
    }
}
