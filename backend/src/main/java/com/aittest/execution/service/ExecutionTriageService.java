package com.aittest.execution.service;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.ExecutionStatus;
import com.aittest.common.enums.TriageSource;
import com.aittest.execution.domain.ExecutionResult;
import com.aittest.execution.domain.ExecutionRun;
import com.aittest.execution.dto.ExecutionRemapResult;
import com.aittest.execution.dto.ExecutionResultVO;
import com.aittest.execution.mapper.ExecutionResultMapper;
import com.aittest.execution.mapper.ExecutionRunMapper;
import com.aittest.system.domain.FailureCategory;
import com.aittest.system.mapper.FailureCategoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 失败归因的<b>人工纠正</b>（#41）与<b>重跑用例映射</b>（#42）。
 *
 * <p>两件事放在同一个类里，是因为它们共享一个前提：<b>都能改变一行明细的展示含义，
 * 但绝不能互相覆盖</b>。重跑映射只动 {@code case_id}，人工纠正只动
 * {@code failure_category / triage_source}。任何一方顺手把对方的列带上，
 * 后果都是静默的数据丢失——例如用户花 20 分钟纠正了 40 条分类，一次重跑映射
 * 全部冲回 RULE 规则判定，而页面上只看到"映射率更新了"。</p>
 *
 * @author 寇豆码
 */
@Service
public class ExecutionTriageService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionTriageService.class);

    /** 单批更新行数（与 {@link ExecutionWriteService#BATCH_SIZE} 同口径）。 */
    private static final int BATCH_SIZE = ExecutionWriteService.BATCH_SIZE;

    private final ExecutionRunMapper executionRunMapper;

    private final ExecutionResultMapper executionResultMapper;

    /** 字典（只读）：校验码值、取中文文案。表很小，一次全量读即可。 */
    private final FailureCategoryMapper failureCategoryMapper;

    /** 「junit_ref → 用例 id」的唯一查询入口（与导入同一套口径）。 */
    private final JunitRefLookup junitRefLookup;

    public ExecutionTriageService(ExecutionRunMapper executionRunMapper,
                                  ExecutionResultMapper executionResultMapper,
                                  FailureCategoryMapper failureCategoryMapper,
                                  JunitRefLookup junitRefLookup) {
        this.executionRunMapper = executionRunMapper;
        this.executionResultMapper = executionResultMapper;
        this.failureCategoryMapper = failureCategoryMapper;
        this.junitRefLookup = junitRefLookup;
    }

    /**
     * 人工纠正一条失败结果的归因分类（#41）。
     *
     * <p>{@code triage_source} 强制写 {@code MANUAL}，<b>不接受前端传</b>：这个字段是
     * "结论从哪来"的审计信息，由客户端决定等于让它自己声明可信度。同理不允许清空
     * （{@code failureCategory} 为空 → 40001）："取消归因"没有对应的业务动作，
     * 真要撤销就改成 {@code UNKNOWN}，那是一条可追溯的结论。</p>
     *
     * <p><b>幂等</b>：把 MANUAL 行再改成同一分类按成功处理。脚本重放、双击、网络重试
     * 都会产生这种请求；报错只会让调用方以为数据不一致。</p>
     *
     * @param resultId        明细主键（<b>不是</b>批次 id）
     * @param failureCategory 失败分类字典码
     * @return 更新后的整行（{@code failureLabel} / {@code triageSource} 都是新值，
     *         前端拿它就地替换表格那一行）
     * @throws BizException 明细不存在 40401 / 状态不是 FAILED 40001 / 分类非法或停用 40001
     */
    @Transactional(rollbackFor = Exception.class)
    public ExecutionResultVO updateFailureCategory(Long resultId, String failureCategory) {
        ExecutionResult row = resultId == null ? null : executionResultMapper.selectById(resultId);
        if (row == null) {
            throw BizException.notFound("执行结果");
        }
        // PASSED/SKIPPED 的失败分类只可能是噪声；写入后 categoryDistribution 的合计会大于
        // 失败条数，饼图和"失败 N 条"的卡片再也对不上，且没人能解释多出来的那一块
        if (!ExecutionStatus.FAILED.code().equals(row.getStatus())) {
            throw new BizException(ErrorCode.E_PARAM_INVALID,
                    "只有失败的结果可以人工归因（本行状态：" + row.getStatus() + "）");
        }
        FailureCategory category = requireCategory(failureCategory);

        executionResultMapper.update(null, new LambdaUpdateWrapper<ExecutionResult>()
                .eq(ExecutionResult::getId, resultId)
                // 只改这两列：failure_message / stack_trace 是解析出来的事实，人工改判性质
                // 不等于可以改证据
                .set(ExecutionResult::getFailureCategory, category.getCode())
                .set(ExecutionResult::getTriageSource, TriageSource.MANUAL.code()));

        // 用数据库里的新值回填返回值，而不是拼一个"应该是这样"的对象：
        // 若 UPDATE 因并发被别的事务改成 MANUAL 以外的值，返回的仍是真实落库结果
        row.setFailureCategory(category.getCode());
        row.setTriageSource(TriageSource.MANUAL.code());
        log.info("[EXECUTION] 人工纠正归因 resultId={} category={} runId={}", resultId,
                category.getCode(), row.getRunId());
        return ExecutionResultVO.from(row, category.getLabel());
    }

    /**
     * 重跑用例映射（#42）：按批次所属任务的<b>当前</b> {@code junit_ref} 重算 {@code case_id}。
     *
     * <p><b>为什么需要它</b>：导入时用户可能还没导出 JUnit 骨架（映射率 0），或者导出发生在
     * 导入之后。重跑映射让"先跑测试、后补导出"这种真实顺序也能拿到映射关系，而不必删了
     * 批次重导（重导会丢掉人工纠正的归因）。</p>
     *
     * <p><b>只改 {@code case_id}，绝不碰 {@code failure_category / triage_source}</b>：
     * 重跑映射是"这条结果对应哪条用例"的修正，不是重新归因。把人工纠正冲掉就是数据丢失，
     * 而且丢得没有痕迹（页面上只看到"映射率更新了"）。</p>
     *
     * <p><b>是"重算"而不是"补漏"</b>：命中不了的行的 {@code case_id} 必须被清空。
     * 只补不清时，用例重新导出换了编号之后，旧 {@code case_id} 会永远指向一条实际没跑过的用例——
     * 那时 {@code mapped_count} 还会变小，看起来像"映射修好了"，其实是"旧映射变成了错映射"。</p>
     *
     * @param runId 批次主键
     * @return 新的映射条数 / 总数 / 映射率
     * @throws BizException 批次不存在 40401 / 批次未关联任务 40001
     */
    @Transactional(rollbackFor = Exception.class)
    public ExecutionRemapResult remap(Long runId) {
        ExecutionRun run = runId == null ? null : executionRunMapper.selectById(runId);
        if (run == null) {
            throw BizException.notFound("执行批次");
        }
        // V7 之前（或任务被删后 ON DELETE SET NULL）task_id 为 null：没有任务就无法确定
        // 在哪个用例集合里重算。这里不能"跳过并返回原值"，那会让用户以为已经重算过了
        if (run.getTaskId() == null) {
            throw new BizException(ErrorCode.E_PARAM_INVALID,
                    "该批次未关联任务，无法重跑映射，请重新导入并指定任务");
        }
        Map<String, Long> refToCaseId = junitRefLookup.load(run.getTaskId());
        List<ExecutionResult> rows = executionResultMapper.selectList(
                new LambdaQueryWrapper<ExecutionResult>()
                        .select(ExecutionResult::getId, ExecutionResult::getCaseId,
                                ExecutionResult::getClassName)
                        .eq(ExecutionResult::getRunId, runId)
                        .orderByAsc(ExecutionResult::getId));

        List<ExecutionResultMapper.CaseMapping> pairs = new ArrayList<>();
        Set<Long> staleIds = new LinkedHashSet<>();
        int mappedCount = 0;
        for (ExecutionResult row : rows) {
            Long caseId = refToCaseId.get(JunitRefLookup.refOf(row.getClassName()));
            if (caseId == null) {
                if (row.getCaseId() != null) {
                    // 旧映射必须清掉：见方法注释「重算不是补漏」
                    staleIds.add(row.getId());
                }
                continue;
            }
            mappedCount++;
            if (!Objects.equals(caseId, row.getCaseId())) {
                pairs.add(new ExecutionResultMapper.CaseMapping(row.getId(), caseId));
            }
        }
        int updated = 0;
        for (int from = 0; from < pairs.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, pairs.size());
            updated += executionResultMapper.markCaseMapped(runId, pairs.subList(from, to));
        }
        List<Long> staleList = new ArrayList<>(staleIds);
        int cleared = 0;
        for (int from = 0; from < staleList.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, staleList.size());
            cleared += executionResultMapper.clearCaseMapped(runId, staleList.subList(from, to));
        }

        int totalCount = run.getTotalCount() == null ? rows.size() : run.getTotalCount();
        executionRunMapper.update(null, new LambdaUpdateWrapper<ExecutionRun>()
                .eq(ExecutionRun::getId, runId)
                .set(ExecutionRun::getMappedCount, mappedCount));

        log.info("[EXECUTION] 重跑映射完成 runId={} taskId={} total={} mapped={} 新命中={} 清空={}",
                runId, run.getTaskId(), rows.size(), mappedCount, updated, cleared);
        return ExecutionRemapResult.of(mappedCount, totalCount);
    }

    /**
     * 校验失败分类字典码：必须存在且<b>已启用</b>。
     *
     * <p>停用的分类也拒绝：停用的含义是"以后不要再用它归类"。允许写入等于让停用失效，
     * 而且新写的这条在字典下拉里选不出来，用户下次看到会以为数据坏了。
     * （历史已有数据仍可查询与筛选——那是读取侧，本方法只管写入。）</p>
     *
     * @param code 字典码（可空）
     * @return 字典行
     * @throws BizException 缺失或非法/停用 40001
     */
    private FailureCategory requireCategory(String code) {
        List<FailureCategory> enabled = failureCategoryMapper.selectList(
                new LambdaQueryWrapper<FailureCategory>()
                        .eq(FailureCategory::getEnabled, Boolean.TRUE)
                        .orderByAsc(FailureCategory::getSortNo));
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.E_PARAM_INVALID,
                    "failureCategory 不能为空，可选值：" + codesOf(enabled));
        }
        String normalized = code.trim();
        for (FailureCategory category : enabled) {
            if (normalized.equals(category.getCode())) {
                return category;
            }
        }
        // 报错里带上可选值：前端下拉与字典不同步时，用户能直接看到正确取值
        throw new BizException(ErrorCode.E_PARAM_INVALID,
                "失败分类取值不合法或已停用，可选值：" + codesOf(enabled));
    }

    /**
     * 拼可选值列表（按 sort_no 升序，与前端下拉顺序一致）。
     *
     * @param categories 字典行
     * @return 形如 {@code TIMEOUT/ASSERTION/ENV/...}
     */
    private static String codesOf(List<FailureCategory> categories) {
        if (categories == null || categories.isEmpty()) {
            return "（字典为空）";
        }
        return categories.stream()
                .map(FailureCategory::getCode)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("/"));
    }
}
