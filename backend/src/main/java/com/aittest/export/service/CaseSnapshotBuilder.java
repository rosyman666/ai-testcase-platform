package com.aittest.export.service;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.CaseStatus;
import com.aittest.config.ExportProperties;
import com.aittest.export.dto.CaseRow;
import com.aittest.export.dto.CaseSnapshot;
import com.aittest.export.dto.ExportQuery;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.mapper.GenTaskMapper;
import com.aittest.testcase.domain.TestCase;
import com.aittest.testcase.mapper.TestCaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 单次查询组装导出快照（docs/07 §2.1 / D-11）。
 *
 * <p><b>校验顺序固定</b>（§4.1）：① 参数（40001/40002）→ ② 任务存在性（40401）→
 * ③ 单次查询用例集 → ④ 空集/超限（40003）。顺序反了会出现「任务不存在却报空集」，
 * 或「参数非法却先跑了一次大查询」。</p>
 *
 * <p><b>单次查询</b>：状态分布、导出条数、生效条件都由这一批 {@code rows} 在内存里算完，
 * 绝不二次查库。两次查询之间有人评审，统计 sheet 与用例 sheet 就会对不上且不会报错。</p>
 *
 * <p><b>超限用 {@code LIMIT maxRows+1} 探测</b>：不做额外 {@code COUNT(*)}，
 * 也不把 50000 行全部捞进内存——超出上限时只多读 1 行就返回 40003。</p>
 *
 * @author 寇豆码
 */
@Component
public class CaseSnapshotBuilder {

    private final TestCaseMapper testCaseMapper;
    private final GenTaskMapper genTaskMapper;
    private final ExportProperties exportProperties;

    public CaseSnapshotBuilder(TestCaseMapper testCaseMapper,
                               GenTaskMapper genTaskMapper,
                               ExportProperties exportProperties) {
        this.testCaseMapper = testCaseMapper;
        this.genTaskMapper = genTaskMapper;
        this.exportProperties = exportProperties;
    }

    /**
     * 组装快照。
     *
     * @param query 导出入参
     * @return 非空快照（rows 至少 1 行）
     * @throws BizException 40001 / 40002 / 40003 / 40401
     */
    public CaseSnapshot build(ExportQuery query) {
        Long taskId = query.requireTaskId();
        List<String> statuses = query.normalizeStatuses();

        GenTask task = genTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.E_NOT_FOUND, "任务不存在：taskId=" + taskId);
        }

        int maxRows = Math.max(1, exportProperties.getMaxRows());
        LambdaQueryWrapper<TestCase> wrapper = new LambdaQueryWrapper<TestCase>()
                .eq(TestCase::getTaskId, taskId)
                .in(TestCase::getStatus, statuses)
                .orderByAsc(TestCase::getCreatedAt)
                .orderByAsc(TestCase::getId)
                .last("LIMIT " + (maxRows + 1));
        if (query.getDocId() != null) {
            wrapper.eq(TestCase::getDocId, query.getDocId());
        }

        // 唯一一次查库：后续状态分布 / 统计 sheet / 用例 sheet 全部来自这批 rows
        List<TestCase> entities = testCaseMapper.selectList(wrapper);
        if (entities == null || entities.isEmpty()) {
            throw new BizException(ErrorCode.E_EMPTY_RESULT, "没有符合条件的用例");
        }
        if (entities.size() > maxRows) {
            throw new BizException(ErrorCode.E_EMPTY_RESULT,
                    "超出单次上限 " + maxRows + " 条，请加筛选条件");
        }

        List<CaseRow> rows = new ArrayList<>(entities.size());
        Map<String, Integer> statusCounts = emptyStatusCounts();
        for (TestCase entity : entities) {
            rows.add(toRow(entity));
            String status = entity.getStatus() == null ? "" : entity.getStatus();
            statusCounts.merge(status, 1, Integer::sum);
        }

        return CaseSnapshot.builder()
                .taskNo(task.getTaskNo())
                .rows(rows)
                .statusCounts(statusCounts)
                .exportedAt(LocalDateTime.now(ZoneOffset.UTC))
                .criteria(query.criteria(statuses))
                .build();
    }

    /**
     * 四值状态分布初值（未导出的状态也要保留 0）。
     *
     * @return 按 {@link CaseStatus} 声明顺序的可变 Map
     */
    private static Map<String, Integer> emptyStatusCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CaseStatus status : CaseStatus.values()) {
            counts.put(status.name(), 0);
        }
        return counts;
    }

    /**
     * 实体 → 快照行（只拷贝导出列，不带 id / contentHash / updatedAt）。
     *
     * @param entity 用例实体
     * @return 快照行
     */
    private static CaseRow toRow(TestCase entity) {
        return CaseRow.builder()
                .seqNo(entity.getSeqNo())
                .title(nullToEmpty(entity.getTitle()))
                .precondition(nullToEmpty(entity.getPrecondition()))
                .steps(nullToEmpty(entity.getSteps()))
                .expected(nullToEmpty(entity.getExpected()))
                .priority(nullToEmpty(entity.getPriority()))
                .caseType(nullToEmpty(entity.getCaseType()))
                .status(nullToEmpty(entity.getStatus()))
                .confidence(entity.getConfidence())
                .model(nullToEmpty(entity.getModel()))
                .promptVersion(nullToEmpty(entity.getPromptVersion()))
                .createdAt(entity.getCreatedAt())
                .build();
    }

    /**
     * null 文本列 → 空串，避免 EasyExcel 写出 {@code null} 字面量或 NPE。
     *
     * @param value 原值
     * @return 非 null 文本
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
