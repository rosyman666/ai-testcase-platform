package com.aittest.execution.service;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.IdGen;
import com.aittest.common.enums.ExecutionSourceType;
import com.aittest.common.enums.ExecutionStatus;
import com.aittest.common.enums.TriageSource;
import com.aittest.execution.domain.ExecutionResult;
import com.aittest.execution.domain.ExecutionRun;
import com.aittest.execution.dto.ExecutionImportResult;
import com.aittest.execution.mapper.ExecutionResultMapper;
import com.aittest.execution.parser.ParsedResult;
import com.aittest.execution.parser.ResultParser;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.mapper.GenTaskMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * T10 执行结果导入的<b>编排者</b>（本类刻意<b>不加</b> {@code @Transactional}）。
 *
 * <p><b>六个步骤与它们的事务语义</b>（顺序不能调，理由都在每一步上）：</p>
 * <ol>
 *   <li><b>参数校验</b>（任务存在、文件非空、扩展名可识别）——在一次查询之前做完，
 *       避免"文件格式不对"却先做了一次任务查询；</li>
 *   <li><b>解析</b>——<b>在事务之外</b>。一个 20MB 的结果文件解析要几百毫秒到数秒，
 *       放进事务就等于占着一条数据库连接不干活；连接池只有 20 条，几个人同时导入就能把
 *       整个应用拖住。解析产出的是纯内存对象，不需要事务保护；</li>
 *   <li><b>映射</b>——只读一次 {@code test_case}，建「{@code junit_ref} → 用例 id」；
 *       <b>映射不上不阻断导入</b>，留 {@code case_id = NULL}，让映射率如实掉下来；</li>
 *   <li><b>取号 + 插批次</b>——{@code IdGen.generateWithRetry(NoType.RUN, ...)}，
 *       它要求 {@code action} 是<b>独立事务边界</b>，所以这里调用的
 *       {@code ExecutionWriteService#insertRun} 没有事务注解（单条 insert 自动提交）；</li>
 *   <li><b>批量写明细 + 回填 case_id</b>——一个事务（{@code saveResults}）；</li>
 *   <li><b>失败补偿</b>——第 5 步失败时删掉第 4 步插入的批次，不让"有计数、没明细"的
 *       空批次留在库里（详情页会显示总数 100、明细 0 条，看起来像分页坏了）。</li>
 * </ol>
 *
 * <p><b>为什么要用 {@code test_case.junit_ref} 精确匹配，而不是重算行号</b>：
 * JUnit 类名是 {@code GeneratedCase_NNN}，NNN 是<b>导出那一刻</b>"查询结果第 N 行"的编号。
 * 导入时若用相同的筛选条件重算一遍行号，只要两次之间发生过下面任意一件事就会整体错位：
 * 同任务新增了用例、有人评审把 DRAFT 改成 ACCEPTED（statuses 过滤集合变了）、
 * 删掉了 doc_id 关联（docId 过滤集合变了）。错位之后映射率仍是 100%，
 * 每一条结果都"成功"挂到了<b>错误的用例</b>上，没有异常、没有日志。
 * 因此编号在导出时就固化进库（{@code export/service/JunitRefWriter}），导入只做字符串比对。</p>
 *
 * @author 寇豆码
 */
@Service
public class ExecutionImportService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionImportService.class);

    /** 批次名称列宽（{@code execution_run.name VARCHAR(200)}）。 */
    private static final int NAME_MAX = 200;

    /** 文件名列宽（{@code execution_run.file_name VARCHAR(255)}）。 */
    private static final int FILE_NAME_MAX = 255;

    /** 类名/方法名列宽（{@code execution_result.class_name / case_name VARCHAR(500)}）。 */
    private static final int LABEL_MAX = 500;

    /** 未填名称时的兜底批次名。 */
    private static final String DEFAULT_BATCH_NAME = "执行结果导入";

    /**
     * 0 字节文件的提示文案（<b>与前端本地拦截逐字一致</b>，改动需两边同时改）。
     *
     * <p>抽出常量而不是散在 throw 里：这句话是跨端契约的一部分，需要一个能被引用、
     * 能被单测断言、且改一处就全改的地方。</p>
     *
     * <p><b>文案必须说清成因，不能只描述现象</b>：0 字节几乎只有一个来路——导出/下载
     * 中途断了，文件根本没被写进去。只说「文件是空的，请重新选择」的话，用户会在原地
     * 再挑一个同样没下完的文件重试，撞第二次同样的错，然后开始怀疑功能坏了。所以这里
     * 直接给出下一步动作（回去重新导出），而不是让他换个文件再撞一次。</p>
     */
    static final String EMPTY_FILE_MESSAGE =
            "文件内容为空（0 字节），通常是导出或下载没有完成，请重新导出后再上传";

    private final GenTaskMapper genTaskMapper;

    private final JunitRefLookup junitRefLookup;

    private final ExecutionWriteService executionWriteService;

    private final IdGen idGen;

    /** 格式 → 解析器（由 Spring 注入全部实现后建索引，避免运行时判空分支散落各处）。 */
    private final Map<ExecutionSourceType, ResultParser> parsers;

    public ExecutionImportService(GenTaskMapper genTaskMapper,
                                  JunitRefLookup junitRefLookup,
                                  ExecutionWriteService executionWriteService,
                                  IdGen idGen,
                                  List<ResultParser> parserList) {
        this.genTaskMapper = genTaskMapper;
        this.junitRefLookup = junitRefLookup;
        this.executionWriteService = executionWriteService;
        this.idGen = idGen;
        this.parsers = indexParsers(parserList);
    }

    /**
     * 导入一个执行结果文件。
     *
     * @param taskId 结果对应的生成任务（<b>必填</b>：映射只在本任务的用例范围内进行）
     * @param name   批次名称（可空；空时用文件名兜底）
     * @param file   结果文件（.xml / .csv）
     * @return 批次 id/单号与六个计数
     * @throws BizException 缺 taskId 40002 / 任务不存在 40401 / 格式不支持 44002 /
     *                      解析失败 44001 / 文件里没有结果 40003 / 名称过长 40001
     */
    public ExecutionImportResult importResult(Long taskId, String name, MultipartFile file) {
        GenTask task = requireTask(taskId);
        String fileName = normalizedFileName(file);
        ResultParser parser = resolveParser(fileName);
        ExecutionSourceType sourceType = parser.sourceType();

        // ② 解析：事务外
        List<ParsedResult> parsed = parse(parser, file, fileName);
        if (parsed.isEmpty()) {
            throw new BizException(ErrorCode.E_EMPTY_RESULT, "执行结果文件里没有任何用例结果");
        }

        // ③ 映射：只读一次 test_case；映射不上留 NULL，不阻断
        Map<String, Long> refToCaseId = junitRefLookup.load(taskId);

        List<ExecutionResult> rows = new ArrayList<>(parsed.size());
        List<ExecutionResultMapper.CaseMapping> pairs = new ArrayList<>(parsed.size());
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        long durationMs = 0L;
        for (int index = 0; index < parsed.size(); index++) {
            ParsedResult item = parsed.get(index);
            int rowNo = index + 1;
            ExecutionStatus status = requireStatus(item.status(), rowNo);
            requireLabelLength(item.className(), "classname", rowNo);
            requireLabelLength(item.caseName(), "name", rowNo);

            switch (status) {
                case PASSED -> passed++;
                case FAILED -> failed++;
                default -> skipped++;
            }
            long rowDuration = Math.max(item.durationMs(), 0L);
            durationMs += rowDuration;

            ExecutionResult row = new ExecutionResult();
            // id 由应用侧生成：insertBatch 是手写 XML，绕开 MP 的主键填充；
            // 有了 id 才能用一条 CASE 语句回填 case_id
            row.setId(IdWorker.getId());
            row.setClassName(nullToEmpty(item.className()));
            row.setCaseName(nullToEmpty(item.caseName()));
            row.setStatus(status.code());
            row.setDurationMs(rowDuration);
            // 空消息/空堆栈存 NULL 而不是空串：前端契约里"通过/跳过的 failureMessage 为 null"，
            // 空串会让"没有失败信息"与"失败信息是空字符串"在展示与筛选上分叉
            row.setFailureMessage(emptyToNull(item.failureMessage()));
            row.setStackTrace(emptyToNull(item.stackTrace()));
            // DDL 默认值是 RULE；insertBatch 显式列出该列，故必须绑值（下一批归因才会改写它）
            row.setTriageSource(TriageSource.RULE.code());

            Long caseId = refToCaseId.get(JunitRefLookup.refOf(item.className()));
            if (caseId != null) {
                row.setCaseId(caseId);
                pairs.add(new ExecutionResultMapper.CaseMapping(row.getId(), caseId));
            }
            rows.add(row);
        }

        // ④ 取号 + 插批次（insertRun 无事务，撞 UK 由 generateWithRetry 重新播种后重试）
        String batchName = resolveBatchName(name, fileName);
        // lambda 只能捕获 effectively-final 变量，计数在循环里累加过，这里显式定稿一份
        int totalCount = parsed.size();
        int passedCount = passed;
        int failedCount = failed;
        int skippedCount = skipped;
        int mappedCount = pairs.size();
        long totalDurationMs = durationMs;
        String sourceCode = sourceType.code();
        ExecutionRun run = idGen.generateWithRetry(IdGen.NoType.RUN,
                runNo -> executionWriteService.insertRun(runNo, taskId, batchName, sourceCode, fileName,
                        totalCount, passedCount, failedCount, skippedCount, mappedCount, totalDurationMs));

        // ⑤ 批量写明细（一个事务）+ ⑥ 失败补偿
        try {
            executionWriteService.saveResults(run.getId(), rows, pairs);
        } catch (RuntimeException ex) {
            log.error("[EXECUTION] 明细写入失败，开始补偿删除批次 runNo={} total={}",
                    run.getRunNo(), parsed.size(), ex);
            try {
                executionWriteService.deleteRun(run.getId());
            } catch (RuntimeException cleanupEx) {
                // 补偿也失败：留 WARN 并带上 runId，人工可清理；绝不吞掉原始异常
                log.error("[EXECUTION] 补偿删除批次失败，需人工清理 runId={}", run.getId(), cleanupEx);
            }
            // 原样抛出：交给 GlobalExceptionHandler 按既有映射产出业务码
            // （包成 E_INTERNAL 会把 40004 字段超长之类的精确码盖掉）
            throw ex;
        }

        log.info("[EXECUTION] 导入完成 runNo={} file={} source={} total={} passed={} failed={} "
                        + "skipped={} mapped={} durationMs={}",
                run.getRunNo(), fileName, sourceType.code(), parsed.size(), passed, failed,
                skipped, mappedCount, durationMs);
        return new ExecutionImportResult(run.getId(), run.getRunNo(), taskId, task.getTaskNo(),
                parsed.size(), passed, failed, skipped, mappedCount);
    }

    /**
     * 校验任务存在并返回它（返回值只用于回填 {@code taskNo} 与 {@code taskId}）。
     *
     * @param taskId 任务 ID
     * @return 任务实体
     * @throws BizException 缺失 40002 / 不存在 40401
     */
    private GenTask requireTask(Long taskId) {
        if (taskId == null) {
            throw new BizException(ErrorCode.E_REQUIRED_MISSING, "缺少 taskId（执行结果必须归属一个生成任务）");
        }
        GenTask task = genTaskMapper.selectById(taskId);
        if (task == null) {
            throw BizException.notFound("任务");
        }
        return task;
    }

    /**
     * 取规范化后的文件名（去掉浏览器可能带上的本地路径、截断到列宽）。
     *
     * <p><b>0 字节文件的文案要求</b>（团队裁决）：仍归 {@code 44002}
     * （{@code E_RESULT_UNSUPPORTED}，"这个文件我处理不了"，不值得为它单开一个码），
     * 但 {@code message} 必须<b>自解释且能指导下一步动作</b>，并且与前端本地拦截的文案
     * <b>逐字一致</b>——同一个错误两种说法，排查时会被当成两个原因。因此这里刻意不写
     * "不支持的文件格式"：那会让人以为是后缀名的问题，去改后缀重试。</p>
     *
     * @param file 上传文件
     * @return 非 null 文件名（可能为空串）
     * @throws BizException 文件为空 44002
     */
    private static String normalizedFileName(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.E_RESULT_UNSUPPORTED, EMPTY_FILE_MESSAGE);
        }
        String original = file.getOriginalFilename();
        if (original == null) {
            return "";
        }
        // 少数浏览器会把本地路径一起送上来（含盘符），只保留最后一段
        String name = original.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.length() > FILE_NAME_MAX) {
            // 文件名只用于排障展示，超长就截断；为它整批导入失败不划算
            name = name.substring(0, FILE_NAME_MAX);
        }
        return name;
    }

    /**
     * 按扩展名选出解析器。
     *
     * @param fileName 文件名
     * @return 解析器
     * @throws BizException 扩展名无法识别 44002
     */
    private ResultParser resolveParser(String fileName) {
        String extension = extensionOf(fileName);
        ExecutionSourceType sourceType = ExecutionSourceType.ofExtension(extension);
        ResultParser parser = sourceType == null ? null : parsers.get(sourceType);
        if (parser == null) {
            // 消息里带上真实文件名：用户常以为自己传的是 .xml 而实际是 .html（导出的报告页）
            throw new BizException(ErrorCode.E_RESULT_UNSUPPORTED,
                    "不支持的文件格式：" + (fileName.isEmpty() ? "（文件名为空）" : fileName)
                            + "，仅支持 .xml（JUnit XML）与 .csv");
        }
        return parser;
    }

    /**
     * 调解析器（读流异常统一翻译成 44001）。
     *
     * @param parser   解析器
     * @param file     上传文件
     * @param fileName 文件名（错误消息用）
     * @return 解析结果（可能为空列表）
     */
    private static List<ParsedResult> parse(ResultParser parser, MultipartFile file, String fileName) {
        try (InputStream in = file.getInputStream()) {
            return parser.parse(in);
        } catch (IOException ex) {
            log.warn("[EXECUTION] 读取执行结果文件失败 file={}", fileName, ex);
            throw new BizException(ErrorCode.E_RESULT_PARSE_FAILED,
                    "执行结果文件读取失败：" + fileName);
        }
    }

    /**
     * 校验并归一化状态编码。
     *
     * @param status 状态编码
     * @param rowNo  第几条结果（从 1 开始，错误消息用）
     * @return 枚举
     * @throws BizException 取值不合法 44001
     */
    private static ExecutionStatus requireStatus(String status, int rowNo) {
        for (ExecutionStatus item : ExecutionStatus.values()) {
            if (item.code().equals(status)) {
                return item;
            }
        }
        throw new BizException(ErrorCode.E_RESULT_PARSE_FAILED,
                "第 " + rowNo + " 条结果的 status 取值不合法：" + status);
    }

    /**
     * 校验类名/方法名不超列宽（{@code VARCHAR(500)}）。
     *
     * <p>提前判是为了给"第几条、哪个字段"的中文提示：留给 MySQL 报 1406 的话，
     * 全局处理器只能给出"字段内容超出长度限制"（40004），用户不知道是哪一条。</p>
     *
     * @param value 字段值
     * @param field 字段名（JUnit 属性名）
     * @param rowNo 第几条结果
     * @throws BizException 超长 44001
     */
    private static void requireLabelLength(String value, String field, int rowNo) {
        if (value != null && value.length() > LABEL_MAX) {
            throw new BizException(ErrorCode.E_RESULT_PARSE_FAILED,
                    "第 " + rowNo + " 条结果的 " + field + " 超过 " + LABEL_MAX + " 字符");
        }
    }

    /**
     * 解析批次名称：显式名称优先（超长报错），否则用文件名兜底（超长截断）。
     *
     * <p>为什么两种超长处理不同：显式名称是用户输入，静默截断会让他之后"按名字搜不到"；
     * 文件名是系统兜底值，截断只影响展示，不值得让导入失败。</p>
     *
     * @param name     用户填的名称（可空）
     * @param fileName 文件名
     * @return 非空批次名
     * @throws BizException 显式名称过长 40001
     */
    private static String resolveBatchName(String name, String fileName) {
        if (name != null && !name.isBlank()) {
            String trimmed = name.trim();
            if (trimmed.length() > NAME_MAX) {
                throw new BizException(ErrorCode.E_PARAM_INVALID,
                        "批次名称过长（上限 " + NAME_MAX + " 字符）");
            }
            return trimmed;
        }
        if (fileName == null || fileName.isBlank()) {
            return DEFAULT_BATCH_NAME;
        }
        return fileName.length() > NAME_MAX ? fileName.substring(0, NAME_MAX) : fileName;
    }

    /**
     * 取扩展名（不含点，转小写；无扩展名返回空串）。
     *
     * @param fileName 文件名
     * @return 扩展名
     */
    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int index = fileName.lastIndexOf('.');
        if (index < 0 || index == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * null 文本 → 空串（{@code class_name}/{@code case_name} 是 {@code NOT NULL DEFAULT ''}，
     * 但 CSV 可能整列缺失，实体里就会出现 null，落库前兜成空串）。
     *
     * @param value 原值
     * @return 非 null 文本
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 空文本 → null（{@code failure_message}/{@code stack_trace} 用 null 表示"没有失败信息"）。
     *
     * <p>与 {@link #nullToEmpty} 方向相反，因为两列语义不同：类名"缺失"就是空串，
     * 而"没有失败信息"是一个<b>不存在的值</b>，用 null 才能让前端把它渲染成"—"而不是空白。</p>
     *
     * @param value 原值
     * @return 非空文本或 null
     */
    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * 把解析器列表建成「格式 → 解析器」索引。
     *
     * @param parserList Spring 注入的实现列表
     * @return 不可变索引
     */
    private static Map<ExecutionSourceType, ResultParser> indexParsers(List<ResultParser> parserList) {
        Map<ExecutionSourceType, ResultParser> indexed = new EnumMap<>(ExecutionSourceType.class);
        if (parserList != null) {
            for (ResultParser parser : parserList) {
                indexed.put(parser.sourceType(), parser);
            }
        }
        return Map.copyOf(indexed);
    }
}
