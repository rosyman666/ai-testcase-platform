package com.aittest.execution.service;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.PageQuery;
import com.aittest.common.PageResult;
import com.aittest.common.enums.ExecutionSourceType;
import com.aittest.common.enums.ExecutionStatus;
import com.aittest.execution.domain.ExecutionResult;
import com.aittest.execution.domain.ExecutionRun;
import com.aittest.execution.dto.ExecutionResultVO;
import com.aittest.execution.dto.ExecutionRunDetailVO;
import com.aittest.execution.dto.ExecutionRunVO;
import com.aittest.execution.mapper.ExecutionResultMapper;
import com.aittest.execution.mapper.ExecutionRunMapper;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.mapper.GenTaskMapper;
import com.aittest.system.domain.FailureCategory;
import com.aittest.system.mapper.FailureCategoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 执行批次的<b>只读</b>查询：列表、详情、明细。
 *
 * <p><b>为什么查询与导入分成两个类</b>：导入侧的事务语义是被 {@code IdGen} 的
 * 「独立事务边界」约束逼出来的（见 {@link ExecutionImportService} 与
 * {@link ExecutionWriteService}），读取侧完全不需要事务。混在一起时，任何一次
 * "给类加个 {@code @Transactional}" 的顺手改动都会同时影响导入的重试机制——
 * 而那个影响是静默的（只表现为偶尔撞键后重试不生效）。</p>
 *
 * <p><b>脏筛选值一律 40001，不静默返回空列表</b>：空列表在前端显示成"没有数据"，
 * 用户会以为筛选条件没问题、只是这批没数据，排查方向完全相反。
 * 这与 {@code CaseService#page} 同一口径。</p>
 *
 * @author 寇豆码
 */
@Service
public class ExecutionQueryService {

    private final ExecutionRunMapper executionRunMapper;

    private final ExecutionResultMapper executionResultMapper;

    /** 失败分类字典（只读，用于把字典码翻成中文；表很小，一次全量读即可）。 */
    private final FailureCategoryMapper failureCategoryMapper;

    /** 任务 Mapper（只读，用于把 {@code task_id} 翻成 {@code taskNo}）。 */
    private final GenTaskMapper genTaskMapper;

    public ExecutionQueryService(ExecutionRunMapper executionRunMapper,
                                 ExecutionResultMapper executionResultMapper,
                                 FailureCategoryMapper failureCategoryMapper,
                                 GenTaskMapper genTaskMapper) {
        this.executionRunMapper = executionRunMapper;
        this.executionResultMapper = executionResultMapper;
        this.failureCategoryMapper = failureCategoryMapper;
        this.genTaskMapper = genTaskMapper;
    }

    /**
     * 批次分页列表（{@code GET /api/executions}）。
     *
     * <p>排序固定 {@code created_at DESC, id DESC}：批次列表天然是"最近的在前"，
     * 而同一毫秒内插入的多批（批量导入）用主键倒序兜底，否则翻页会抖动。</p>
     *
     * <p><b>{@code taskNo} 用一次 {@code selectBatchIds} 批量补齐，不 N+1</b>：
     * 一页 20 条各查一次任务就是 21 次查询，且这些查询之间没有任何一致性关系
     * （查到一半任务被删，同一页里有的行有 taskNo、有的没有）。批量取一次既省往返，
     * 也保证同一页的任务名来自同一次读。</p>
     *
     * @param sourceType 来源格式过滤（可空；脏值 40001）
     * @param keyword    关键词（匹配批次号 / 名称 / 文件名 / 关联任务号，可空）
     * @param query      分页参数
     * @return 分页结果
     * @throws BizException 来源格式非法 40001
     */
    public PageResult<ExecutionRunVO> page(String sourceType, String keyword, PageQuery query) {
        PageQuery effectiveQuery = query == null ? new PageQuery() : query;

        LambdaQueryWrapper<ExecutionRun> wrapper = new LambdaQueryWrapper<>();
        if (sourceType != null && !sourceType.isBlank()) {
            wrapper.eq(ExecutionRun::getSourceType, requireSourceType(sourceType.trim()));
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            // 批次号 / 名称 / 文件名 / 关联任务号四处一起搜：
            // ① 人记得的往往是"那个 csv 叫什么"或客服给的批次单号；
            // ② 排查时手上更常见的其实是被测任务的单号（T20261116-000004）——
            //    搜不到会被直接读成"这个任务我没导过"，比搜错了更糟。
            // 任务号匹配走子查询而不是 join：本表没有冗余 task_no，join 会让分页 count
            // 变成两表统计（且 gen_task 被删后行会消失，与 ON DELETE SET NULL 的语义打架）。
            // {0} 是 MyBatis-Plus 的占位符绑定（PreparedStatement 参数），不是字符串拼接。
            // 通配符 % / _ 本轮四处统一不做转义：只给第四处转义会让前三处仍把 % 当通配符，
            // 行为更不一致；而批次名/文件名里出现 % 的概率极低，任务号里不会有通配符。
            wrapper.and(inner -> inner.like(ExecutionRun::getRunNo, kw)
                    .or()
                    .like(ExecutionRun::getName, kw)
                    .or()
                    .like(ExecutionRun::getFileName, kw)
                    .or()
                    .apply("task_id IN (SELECT id FROM gen_task WHERE task_no LIKE {0})",
                            "%" + kw + "%"));
        }
        wrapper.orderByDesc(ExecutionRun::getCreatedAt).orderByDesc(ExecutionRun::getId);

        IPage<ExecutionRun> result = executionRunMapper.selectPage(
                new Page<>(effectiveQuery.pageOrDefault(), effectiveQuery.sizeOrDefault()), wrapper);
        Map<Long, String> taskNos = taskNosOfRuns(result.getRecords());
        List<ExecutionRunVO> records = result.getRecords().stream()
                .map(run -> ExecutionRunVO.from(run, taskNos.get(run.getTaskId())))
                .collect(Collectors.toList());
        return PageResult.of(records, result.getTotal(),
                effectiveQuery.pageOrDefault(), effectiveQuery.sizeOrDefault());
    }

    /**
     * 批次详情（{@code GET /api/executions/{id}}）。
     *
     * @param id 批次主键
     * @return 详情（含三值状态分布与失败分类分布）
     * @throws BizException 批次不存在 40401
     */
    public ExecutionRunDetailVO detail(Long id) {
        ExecutionRun entity = requireRun(id);
        return ExecutionRunDetailVO.from(entity, taskNoOf(entity.getTaskId()),
                categoryDistributionOf(entity.getId()));
    }

    /**
     * 明细分页（{@code GET /api/executions/{id}/results}）。
     *
     * <p><b>按 {@code id} 升序稳定排序</b>：明细的 {@code created_at} 在同一次批量插入里
     * 完全相同（都取数据库默认值），按时间排序等于按随机顺序，翻页会重复/漏行。
     * 雪花 id 单调递增，等价于"文件里的原始顺序"，也是人核对时最自然的顺序。</p>
     *
     * @param runId    批次主键
     * @param status   状态过滤（可空；脏值 40001）
     * @param category 失败分类过滤（可空；不在字典里的码 40001）
     * @param query    分页参数
     * @return 分页结果（{@code stackTrace} 只含前 500 字）
     * @throws BizException 批次不存在 40401 / 筛选值非法 40001
     */
    public PageResult<ExecutionResultVO> results(Long runId, String status, String category,
                                                 PageQuery query) {
        PageQuery effectiveQuery = query == null ? new PageQuery() : query;
        requireRun(runId);

        Map<String, String> categoryLabels = categoryLabels();
        LambdaQueryWrapper<ExecutionResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ExecutionResult::getRunId, runId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(ExecutionResult::getStatus, requireStatus(status.trim()));
        }
        if (category != null && !category.isBlank()) {
            String code = category.trim();
            if (!categoryLabels.containsKey(code)) {
                throw new BizException(ErrorCode.E_PARAM_INVALID,
                        "失败分类取值不合法，可选值：" + String.join("/", categoryLabels.keySet()));
            }
            wrapper.eq(ExecutionResult::getFailureCategory, code);
        }
        wrapper.orderByAsc(ExecutionResult::getId);

        IPage<ExecutionResult> result = executionResultMapper.selectPage(
                new Page<>(effectiveQuery.pageOrDefault(), effectiveQuery.sizeOrDefault()), wrapper);
        List<ExecutionResultVO> records = result.getRecords().stream()
                .map(row -> ExecutionResultVO.from(row,
                        row.getFailureCategory() == null ? null : categoryLabels.get(row.getFailureCategory())))
                .collect(Collectors.toList());
        return PageResult.of(records, result.getTotal(),
                effectiveQuery.pageOrDefault(), effectiveQuery.sizeOrDefault());
    }

    /**
     * 按主键取批次。
     *
     * @param id 主键
     * @return 实体
     * @throws BizException 不存在 40401
     */
    private ExecutionRun requireRun(Long id) {
        ExecutionRun entity = id == null ? null : executionRunMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("执行批次");
        }
        return entity;
    }

    /**
     * 批量取当前页的「任务 ID → 任务单号」（一次查询，避免 N+1）。
     *
     * @param runs 当前页批次
     * @return 任务单号映射（不含 task_id 为 null 的批次）
     */
    private Map<Long, String> taskNosOfRuns(List<ExecutionRun> runs) {
        List<Long> taskIds = runs.stream()
                .map(ExecutionRun::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        return taskNosByIds(taskIds);
    }

    /**
     * 查单个任务单号。
     *
     * <p><b>任务不存在返回 null，不返回空串</b>：{@code ON DELETE SET NULL} 之后
     * {@code task_id} 为 null 是正常状态，"任务已删"与"任务单号是空字符串"是两件事；
     * 前端按 null 显示"—"，空串会渲染成空白单元格，看起来像数据丢了。</p>
     *
     * @param taskId 任务 ID（可为 null）
     * @return 任务单号；{@code taskId} 为 null 或任务已删时返回 null
     */
    private String taskNoOf(Long taskId) {
        if (taskId == null) {
            return null;
        }
        return taskNosByIds(List.of(taskId)).get(taskId);
    }

    /**
     * 按任务 ID 批量查任务单号。
     *
     * @param taskIds 任务 ID 集合（可为空）
     * @return 任务单号映射（查不到的任务不出现在 map 里）
     */
    private Map<Long, String> taskNosByIds(List<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        List<GenTask> tasks = genTaskMapper.selectBatchIds(taskIds);
        Map<Long, String> taskNos = new HashMap<>(Math.max(tasks.size(), 1) * 2);
        for (GenTask task : tasks) {
            if (task.getId() != null && task.getTaskNo() != null) {
                taskNos.put(task.getId(), task.getTaskNo());
            }
        }
        return taskNos;
    }

    /**
     * 失败分类分布（真实聚合；本批归因未交付，故通常为空列表）。
     *
     * @param runId 批次主键
     * @return 分布（按条数倒序）
     */
    private List<ExecutionRunDetailVO.CategoryPoint> categoryDistributionOf(Long runId) {
        List<Map<String, Object>> rows = executionResultMapper.selectCategoryDistribution(runId);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<ExecutionRunDetailVO.CategoryPoint> points = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String category = asText(row, "category");
            points.add(new ExecutionRunDetailVO.CategoryPoint(category, asText(row, "label"),
                    asInt(row, "cnt")));
        }
        return points;
    }

    /**
     * 失败分类字典（码 → 中文）。
     *
     * <p>一次全量读而不是按 id 逐个查：字典表是几十行的小表，读两次（校验 + 翻译）
     * 比 N 条明细各查一次便宜得多。</p>
     *
     * @return 码 → 文案（按 sort_no 升序，便于拼错误消息里的可选值）
     */
    private Map<String, String> categoryLabels() {
        List<FailureCategory> categories = failureCategoryMapper.selectList(
                new LambdaQueryWrapper<FailureCategory>().orderByAsc(FailureCategory::getSortNo));
        Map<String, String> labels = new HashMap<>(Math.max(categories.size(), 1) * 2);
        for (FailureCategory category : categories) {
            if (category.getCode() != null) {
                labels.put(category.getCode(),
                        category.getLabel() == null ? category.getCode() : category.getLabel());
            }
        }
        return labels;
    }

    /**
     * 校验来源格式编码。
     *
     * @param sourceType 编码
     * @return 规范化编码
     * @throws BizException 非法 40001
     */
    private static String requireSourceType(String sourceType) {
        ExecutionSourceType type = ExecutionSourceType.ofCode(sourceType);
        if (type == null) {
            String allowed = Arrays.stream(ExecutionSourceType.values())
                    .map(ExecutionSourceType::code)
                    .collect(Collectors.joining("/"));
            throw new BizException(ErrorCode.E_PARAM_INVALID,
                    "来源格式取值不合法，可选值：" + allowed);
        }
        return type.code();
    }

    /**
     * 校验结果状态编码。
     *
     * @param status 编码
     * @return 规范化编码
     * @throws BizException 非法 40001
     */
    private static String requireStatus(String status) {
        String normalized = status.toUpperCase(Locale.ROOT);
        for (ExecutionStatus item : ExecutionStatus.values()) {
            if (item.code().equals(normalized)) {
                return item.code();
            }
        }
        String allowed = Arrays.stream(ExecutionStatus.values())
                .map(ExecutionStatus::code)
                .collect(Collectors.joining("/"));
        throw new BizException(ErrorCode.E_PARAM_INVALID,
                "状态取值不合法，可选值：" + allowed);
    }

    /**
     * 从聚合结果里取文本列（键名大小写不敏感，不同驱动返回的列标签大小写不一致）。
     *
     * @param row 行
     * @param key 键名
     * @return 文本；缺失返回空串
     */
    private static String asText(Map<String, Object> row, String key) {
        Object value = findValue(row, key);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 从聚合结果里取整数列。
     *
     * @param row 行
     * @param key 键名
     * @return 整数；缺失或非数字返回 0
     */
    private static int asInt(Map<String, Object> row, String key) {
        Object value = findValue(row, key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * 大小写不敏感地查 Map 的键。
     *
     * @param row 行
     * @param key 键名
     * @return 值；缺失返回 null
     */
    private static Object findValue(Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
