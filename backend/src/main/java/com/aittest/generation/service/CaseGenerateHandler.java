package com.aittest.generation.service;

import com.aittest.aicore.prompt.PromptTemplate;
import com.aittest.aicore.prompt.PromptTemplateService;
import com.aittest.common.ErrorCode;
import com.aittest.common.JsonUtils;
import com.aittest.common.enums.CaseStatus;
import com.aittest.common.enums.CaseType;
import com.aittest.common.enums.TaskScene;
import com.aittest.config.AiProperties;
import com.aittest.doc.domain.ApiOperation;
import com.aittest.doc.domain.DocSection;
import com.aittest.doc.mapper.ApiOperationMapper;
import com.aittest.doc.mapper.DocSectionMapper;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.domain.GenTaskItem;
import com.aittest.generation.orchestrator.CaseGenerateOrchestrator;
import com.aittest.generation.orchestrator.GenerationContext;
import com.aittest.generation.orchestrator.GenerationResult;
import com.aittest.generation.service.TaskItemHandler.ItemOutcome;
import com.aittest.testcase.domain.TestCase;
import com.aittest.testcase.dto.CaseDraft;
import com.aittest.testcase.mapper.TestCaseMapper;
import com.aittest.testpoint.domain.TestPoint;
import com.aittest.testpoint.mapper.TestPointMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 用例生成场景的 item 执行器：**一个测试点 → 1~3 条用例落库**。
 *
 * <p><b>本类只做 I/O，模型细节全部在编排层</b>（T07-2 的分层，用户已拍板）：</p>
 * <ul>
 *   <li><b>本类负责</b>：定位测试点、查接口/章节上下文、查 few-shot 素材 → 组装
 *       {@link GenerationContext} → 调 {@link CaseGenerateOrchestrator} → 幂等 upsert + 裁尾 →
 *       映射 {@link ItemOutcome}；</li>
 *   <li><b>编排层负责</b>：渲染、调用、解析、返修、判定档位（NORMAL/REPAIRED/LENIENT）。</li>
 * </ul>
 * <p>边界这么切是因为"表结构/索引"和"模型输出长什么样"变更频率差一个量级：
 * 混在一个方法里，改幂等口径要重读降级逻辑，改降级轮次要重读落库 SQL。</p>
 *
 * <p><b>落库口径留在本类，且只有本类知道</b>：</p>
 * <ol>
 *   <li><b>幂等</b>：{@code INSERT ... ON DUPLICATE KEY UPDATE}（撞 {@code uk_test_case_item_seq}），
 *       更新列不含 {@code status}/{@code id}/{@code created_at}；</li>
 *   <li><b>裁尾</b>：重跑条数变少时删 {@code seq_no > 本次条数}，只按 {@code task_item_id} 删；</li>
 *   <li><b>置信度</b>：{@code NORMAL}/{@code REPAIRED} → {@code 1.000}，
 *       {@code LENIENT} → {@code 0.500}（D-39 把它钉成"解析保真度"，<b>不是内容质量分</b>）；</li>
 *   <li><b>{@code degraded} 标记</b>：只有宽松档置 1，靠 {@link ItemOutcome#degraded} 带出去。</li>
 * </ol>
 *
 * <p><b>失败一律收成 {@link ItemOutcome#failed}</b>（D-27 的失败隔离纪律）：
 * "没关联测试点""测试点被别人删了""模型一条都没给"都是<b>预期内</b>的结果，
 * 抛异常只能表达"第一条失败"。真正的程序错误（NPE、schema 与 DTO 漂移）照旧抛出，
 * 由 worker 按退避重试 3 次后置 FAILED。</p>
 *
 * @author 寇豆码
 */
@Service
public class CaseGenerateHandler implements TaskItemHandler {

    private static final Logger log = LoggerFactory.getLogger(CaseGenerateHandler.class);

    /** 提示词模板编码（{@code prompts/case_generate.v1.md} 的 front-matter {@code code}）。 */
    private static final String TEMPLATE_CODE = "case_generate";

    /** {@code gen_task_item.unit_ref} 里测试点定位的前缀：{@code testpoint:<id>}。 */
    private static final String UNIT_REF_PREFIX = "testpoint:";

    /** 章节正文喂给模型的上限（章节可能几万字，全塞进去会撑爆上下文）。 */
    private static final int SECTION_CONTENT_MAX = 2000;

    /** {@code test_case.title} 是 {@code VARCHAR(500)}，兜底截断长度（schema 已限 200）。 */
    private static final int TITLE_MAX = 500;

    /** 优先级默认值（{@code test_case.priority} 有 DEFAULT 'P2'，这里与之对齐）。 */
    private static final String DEFAULT_PRIORITY = "P2";

    /** 优先级判定规则（提示词占位符 {@code {{priority_rules}}} 的取值）。 */
    private static final String PRIORITY_RULES = "P0/P1/P2/P3，按影响面与发生概率评定";

    /**
     * few-shot 素材条数。
     *
     * <p>5 条是"够学到风格、又不至于挤掉本次任务说明"的经验值：每条渲染成一行（标题 + 预期），
     * 5 行约 300 字；再多就变成"让模型模仿历史用例"而不是"针对这个测试点生成"。</p>
     */
    private static final int FEW_SHOT_LIMIT = 5;

    /** 单条 few-shot 素材里"预期"字段的长度上限（{@code expected} 本身可到 1000 字）。 */
    private static final int FEW_SHOT_TEXT_MAX = 200;

    /**
     * 正常档置信度（解析保真度 = 1.000）。
     *
     * <p><b>为什么是常量而不是"模型给的值"</b>：{@code schema/testcase.schema.json} 里
     * 没有 {@code confidence} 字段且 {@code additionalProperties: false}，
     * 模型<b>写不出</b>置信度（写了会被 schema 拒掉）。所以只能由服务端按档位定。</p>
     */
    private static final BigDecimal CONFIDENCE_NORMAL = new BigDecimal("1.000");

    /**
     * 宽松档置信度（解析保真度 = 0.500）。
     *
     * <p>含义是"这条用例来自元素级打捞，可能缺字段或与兄弟条目不齐"，<b>不是"内容质量差"</b>——
     * 内容好坏只能由人工评审（{@code status}）表达。任何拿 {@code confidence} 排序展示的逻辑
     * 都是误用（D-39）。</p>
     */
    private static final BigDecimal CONFIDENCE_LENIENT = new BigDecimal("0.500");

    /** 测试点定位（读 {@code test_point}）。 */
    private final TestPointMapper testPointMapper;

    /** 接口上下文（{@code test_point.operation_id} → method/path/summary）。 */
    private final ApiOperationMapper apiOperationMapper;

    /** 章节上下文（{@code test_point.section_id} → 标题 + 正文摘要）。 */
    private final DocSectionMapper docSectionMapper;

    /** 用例落库（幂等写入）与 few-shot 素材查询。 */
    private final TestCaseMapper testCaseMapper;

    /** 模板查询（只为拿 {@code promptVersion} 落库；渲染与 schema 由编排层自己查）。 */
    private final PromptTemplateService promptTemplateService;

    /** 编排层（渲染 → 调用 → 解析 → 返修 → 定档）。 */
    private final CaseGenerateOrchestrator caseGenerateOrchestrator;

    /** JSON 转换（steps 拼成 JSON 数组）。 */
    private final ObjectMapper objectMapper;

    /** 模型名兜底（任务没指定时用全局配置）。 */
    private final AiProperties aiProperties;

    public CaseGenerateHandler(TestPointMapper testPointMapper,
                               ApiOperationMapper apiOperationMapper,
                               DocSectionMapper docSectionMapper,
                               TestCaseMapper testCaseMapper,
                               PromptTemplateService promptTemplateService,
                               CaseGenerateOrchestrator caseGenerateOrchestrator,
                               ObjectMapper objectMapper,
                               AiProperties aiProperties) {
        this.testPointMapper = testPointMapper;
        this.apiOperationMapper = apiOperationMapper;
        this.docSectionMapper = docSectionMapper;
        this.testCaseMapper = testCaseMapper;
        this.promptTemplateService = promptTemplateService;
        this.caseGenerateOrchestrator = caseGenerateOrchestrator;
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
    }

    /**
     * 本处理器负责的场景。
     *
     * @return {@link TaskScene#CASE_GENERATE}
     */
    @Override
    public TaskScene scene() {
        return TaskScene.CASE_GENERATE;
    }

    /**
     * 把一个测试点展开成 1~3 条用例并落库。
     *
     * <p><b>执行顺序</b>：定位测试点 → 取模板版本（落库用）→ 查上下文与素材 → 组装上下文
     * → 交给编排层 → 空产出判定 → 幂等落库（先 upsert 再删多余序号）→ 回写指标与档位。</p>
     *
     * @param task 所属任务（提供 docId、taskId、model 兜底）
     * @param item 待执行的子项（提供 taskItemId、attempts、测试点定位）
     * @return 产出条数与真实指标；预期内失败返回 {@link ItemOutcome#failed}
     */
    @Override
    public ItemOutcome execute(GenTask task, GenTaskItem item) {
        // 1) 定位测试点：先看外键，再退回 unit_ref 里的 testpoint:<id>
        Long testPointId = resolveTestPointId(item);
        if (testPointId == null) {
            return ItemOutcome.failed("E_PARAM_INVALID", "任务子项未关联测试点", 0);
        }
        TestPoint testPoint = testPointMapper.selectById(testPointId);
        if (testPoint == null) {
            // 测试点被物理删了（外键是 SET NULL，历史 item 仍指向旧 id）
            return ItemOutcome.failed("E_NOT_FOUND", "测试点不存在：" + testPointId, 0);
        }

        // 2) 模板版本。必须在这里落定：模板改版后老用例的这个字段不能跟着变。
        //    （内容与 schema 由编排层再查一次——它要按同一套规则决定"用哪个模板渲染"，
        //    让 handler 传 schema 会出现"handler 按 A 取 schema、编排层按 B 渲染"的错配。）
        PromptTemplate template = promptTemplateService.activeOf(TEMPLATE_CODE);
        String promptVersion = template.getCode() + ".v" + template.getVersion();

        // 3) 组装纯输入：本类把该查的库全查完，编排层拿到的只有字符串与 id
        GenerationContext context = new GenerationContext(
                task.getId(),
                item.getId(),
                task.getDocId(),
                testPoint.getId(),
                attemptNoOf(item),
                promptVersion,
                testPointTextOf(testPoint),
                apiSpecOf(testPoint),
                allowedCaseTypes(),
                PRIORITY_RULES,
                fewShotOf(task.getDocId()));

        // 4) 编排层：三档降级在这里面。LlmException 不接，交给 worker 退避重试
        GenerationResult result = caseGenerateOrchestrator.generate(context);

        // 5) 一条都没有：不算成功。返回 0 会让任务看起来"跑完了但没产出"
        if (result.drafts().isEmpty()) {
            return emptyOutcome(item, testPointId, result);
        }

        // 6) 幂等落库：先 upsert 覆盖，再删本次 item 的多余序号
        BigDecimal confidence = result.lenient() ? CONFIDENCE_LENIENT : CONFIDENCE_NORMAL;
        List<TestCase> rows = toRows(task, item, testPoint, result.drafts(), promptVersion,
                resolveModel(task), confidence);
        testCaseMapper.upsertBatch(rows);
        int trimmed = testCaseMapper.deleteStaleSeqNo(item.getId(), rows.size());

        log.info("[CASE] 生成完成 taskNo={} itemId={} 测试点={} 用例={} 清理残留={} 版本={} 模型={} "
                        + "档位={} 返修轮次={} tokens={}/{} 成本={}μ 耗时={}ms",
                task.getTaskNo(), item.getId(), testPointId, rows.size(), trimmed,
                promptVersion, rows.get(0).getModel(), result.level(), result.repairRounds(),
                result.promptTokens(), result.completionTokens(), result.costMicro(), result.latencyMs());

        // 7) 指标真回写（不要像抽点侧那样全传 0）；宽松档必须置 degraded=1
        if (result.lenient()) {
            return ItemOutcome.degraded(rows.size(), result.promptTokens(), result.completionTokens(),
                    result.costMicro(), result.latencyMs());
        }
        return ItemOutcome.ok(rows.size(), result.promptTokens(), result.completionTokens(),
                result.costMicro(), result.latencyMs());
    }

    // ------------------------------------------------------------------ 失败/空产出

    /**
     * 草稿为空时的结果：<b>宽松档与正常档的错误码不同</b>。
     *
     * <p>宽松档为空说明"三档全走完仍然一条都救不回来"，根因是<b>结构不合规</b>
     * （{@code E_SCHEMA_INVALID}）；正常档为空说明模型给了一个合法但空的数组，
     * 根因是<b>没产出</b>（{@code E_LLM_EMPTY}）。两者混用会把"模型输出坏"记成"模型没说话"，
     * 排障方向直接跑偏（前者要改提示词/schema，后者要查接口是不是没上下文）。</p>
     *
     * @param item        本次子项
     * @param testPointId 测试点 ID（日志用）
     * @param result      编排层结果
     * @return 失败结果（带真实耗时，便于区分"模型慢"与"模型没给"）
     */
    private ItemOutcome emptyOutcome(GenTaskItem item, Long testPointId, GenerationResult result) {
        log.warn("[CASE] 无可落库用例 itemId={} 测试点={} 档位={} 截断={} 返修轮次={} 原因={}",
                item.getId(), testPointId, result.level(), result.truncated(),
                result.repairRounds(), result.degradeReason());
        if (result.lenient()) {
            return ItemOutcome.failed(ErrorCode.E_SCHEMA_INVALID.name(),
                    "模型输出不合规，宽松解析后仍无可保留的用例", result.latencyMs());
        }
        return ItemOutcome.failed(ErrorCode.E_LLM_EMPTY.name(), "模型未产出任何用例", result.latencyMs());
    }

    // ------------------------------------------------------------------ 落库映射

    /**
     * draft 列表 → 待写入行（{@code seq_no} 从 1 递增，状态固定 {@code DRAFT}）。
     *
     * @param task          所属任务
     * @param item          本次子项
     * @param testPoint     关联测试点
     * @param drafts        编排层产出的草稿
     * @param promptVersion 本次使用的模板版本
     * @param model         本次使用的模型
     * @param confidence    解析保真度（1.000 正常 / 0.500 宽松）
     * @return 待写入行
     */
    private List<TestCase> toRows(GenTask task, GenTaskItem item, TestPoint testPoint,
                                  List<CaseDraft> drafts, String promptVersion, String model,
                                  BigDecimal confidence) {
        List<TestCase> rows = new ArrayList<>(drafts.size());
        int seqNo = 1;
        for (CaseDraft draft : drafts) {
            String stepsJson = stepsJsonOf(draft.steps());
            TestCase row = new TestCase();
            // 绕开 BaseMapper.insert 就走不到 @TableId 的主键填充，id 必须自己生成
            row.setId(IdWorker.getId());
            row.setTaskId(task.getId());
            row.setTaskItemId(item.getId());
            row.setTestPointId(testPoint.getId());
            row.setDocId(task.getDocId());
            row.setSeqNo(seqNo++);
            row.setTitle(truncate(nullToEmpty(draft.title()), TITLE_MAX));
            row.setPrecondition(nullToEmpty(draft.precondition()));
            row.setSteps(stepsJson);
            row.setExpected(nullToEmpty(draft.expected()));
            row.setPriority(isBlank(draft.priority()) ? DEFAULT_PRIORITY : draft.priority());
            row.setCaseType(isBlank(draft.caseType()) ? CaseType.FUNCTIONAL.code() : draft.caseType());
            // 新生成的一律 DRAFT；重跑时 upsert 不覆盖 status，评审结果不会被冲掉
            row.setStatus(CaseStatus.DRAFT.code());
            row.setConfidence(confidence);
            row.setPromptVersion(promptVersion);
            row.setModel(model);
            row.setContentHash(contentHashOf(row.getTitle(), stepsJson, row.getExpected()));
            rows.add(row);
        }
        return rows;
    }

    /**
     * 把模型的 {@code string[]} 步骤拼成 DDL 注释里的 JSON **数组**字符串：
     * {@code [{"no":1,"action":"...","data":""}]}。
     *
     * <p><b>为什么要转成对象数组</b>：列注释与前端契约都按
     * {@code {no, action, data}} 结构写（{@code no} 给"失败在第几步"用，
     * {@code data} 留给请求体/入参）。模型的 schema 只给 {@code string[]}
     * （一步一个动作、不要求它编造请求体），所以 {@code data} 恒空串——
     * 这是<b>有意的有损映射</b>，不是漏填：让模型编请求体只会得到幻觉数据。</p>
     *
     * @param steps 模型给的步骤（可为 null，等价于空数组）
     * @return JSON 数组字符串
     */
    private String stepsJsonOf(List<String> steps) {
        ArrayNode array = objectMapper.createArrayNode();
        if (steps != null) {
            int no = 1;
            for (String step : steps) {
                ObjectNode node = array.addObject();
                node.put("no", no++);
                node.put("action", nullToEmpty(step));
                node.put("data", "");
            }
        }
        return JsonUtils.toJson(array);
    }

    // ------------------------------------------------------------------ few-shot 素材

    /**
     * few-shot 素材（提示词 {@code {{few_shot}}}）。
     *
     * <p><b>查不到就传空串，不传"无"也不传 null</b>：模板里 {@code {{few_shot}}} 的位置在"参考范例"
     * 段落，空串让它自然留白；写成"无"会让模型以为"参考范例就是'无'"，反而降低输出质量。</p>
     *
     * <p><b>查询失败不许让整个 item 失败</b>：素材只是锦上添花，正文（测试点 + 接口上下文）
     * 才是主体。素材表扫不动（锁、慢查询、索引缺失）时整个生成任务全红，是明显不成比例的
     * 失败放大。这里 try/catch 后按"没有素材"继续，并打 WARN 留痕。</p>
     *
     * @param docId 文档 ID（可为 null）
     * @return 渲染好的素材文本；无素材/查询失败返回空串
     */
    private String fewShotOf(Long docId) {
        if (docId == null) {
            return "";
        }
        List<TestCase> samples;
        try {
            samples = testCaseMapper.selectFewShot(docId, FEW_SHOT_LIMIT);
        } catch (RuntimeException ex) {
            log.warn("[CASE] 查询 few-shot 素材失败，本次按无素材继续 docId={} 原因={}",
                    docId, ex.getMessage());
            return "";
        }
        if (samples == null || samples.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (TestCase sample : samples) {
            if (sample == null) {
                continue;
            }
            out.append("- [").append(nullToEmpty(sample.getCaseType())).append("] 标题：")
                    .append(oneLine(sample.getTitle()))
                    .append("；预期：").append(oneLine(sample.getExpected()))
                    .append('\n');
        }
        return out.toString().trim();
    }

    // ------------------------------------------------------------------ 上下文拼装

    /**
     * 测试点的可读文本（提示词 {@code {{test_point}}}）。
     *
     * @param point 测试点
     * @return 多行文本（字段缺失的行直接省略，不写"null"）
     */
    private String testPointTextOf(TestPoint point) {
        StringBuilder out = new StringBuilder();
        appendLine(out, "标题", point.getTitle());
        appendLine(out, "分类", point.getCategory());
        appendLine(out, "风险等级", point.getRiskLevel());
        appendLine(out, "来源", point.getSourceRef());
        appendLine(out, "描述", point.getDescription());
        return out.toString().trim();
    }

    /**
     * 接口/需求上下文（提示词 {@code {{api_spec}}}）。
     *
     * <p><b>拿不到就传空串，绝不编造</b>：提示词里明确要求"判断字段名、错误码、边界值时
     * 只能依据这里"，凭空补一段"常见的 POST /api/users"会让模型生成一堆对不上真实接口的用例——
     * 那比空着更糟，因为空着时模型会写"返回业务错误码"，而编造的上下文会让它写死一个不存在的码。</p>
     *
     * @param point 测试点（携带 {@code operationId} 或 {@code sectionId}）
     * @return {@code METHOD /path\nsummary} 或 {@code 标题\n正文摘要}；都没有则空串
     */
    private String apiSpecOf(TestPoint point) {
        if (point.getOperationId() != null) {
            ApiOperation operation = apiOperationMapper.selectById(point.getOperationId());
            if (operation != null) {
                StringBuilder out = new StringBuilder();
                out.append(nullToEmpty(operation.getMethod()))
                        .append(' ')
                        .append(nullToEmpty(operation.getPath()));
                if (!isBlank(operation.getSummary())) {
                    out.append('\n').append(operation.getSummary());
                }
                return out.toString().trim();
            }
        }
        if (point.getSectionId() != null) {
            DocSection section = docSectionMapper.selectById(point.getSectionId());
            if (section != null) {
                return (nullToEmpty(section.getHeading()) + "\n"
                        + abbreviate(nullToEmpty(section.getContent()))).trim();
            }
        }
        return "";
    }

    /**
     * 可用用例类型（提示词 {@code {{case_types}}}）。
     *
     * <p>从 {@link CaseType} 枚举<b>现算</b>，不写死一份：枚举是分类的单一真源（D-14），
     * 写死就等于又造一个真源，两边漂移时模型会被引导去写 schema 拒收的值。</p>
     *
     * @return 以 {@code /} 连接的分类编码
     */
    private String allowedCaseTypes() {
        StringBuilder out = new StringBuilder();
        for (CaseType type : CaseType.values()) {
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(type.code());
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ 定位与兜底

    /**
     * 测试点定位：外键优先，其次 {@code unit_ref} 的 {@code testpoint:<id>}。
     *
     * @param item 子项
     * @return 测试点 ID；两处都拿不到返回 {@code null}
     */
    private Long resolveTestPointId(GenTaskItem item) {
        if (item.getTestPointId() != null) {
            return item.getTestPointId();
        }
        String unitRef = item.getUnitRef();
        if (unitRef == null || !unitRef.startsWith(UNIT_REF_PREFIX)) {
            return null;
        }
        String raw = unitRef.substring(UNIT_REF_PREFIX.length()).trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException ex) {
            // 脏 unit_ref（例如 testpoint:abc）：当成"没关联"处理，不抛异常
            log.warn("[CASE] unit_ref 里的测试点 id 不是数字，按未关联处理 unitRef={}", unitRef);
            return null;
        }
    }

    /**
     * 本次调用的 attemptNo（从 1 开始）。
     *
     * <p>{@code item.attempts} 记的是<b>已经失败过几次</b>，所以本次是第 {@code attempts + 1} 次。
     * 它落到 {@code llm_call_log.attempt_no}，是"首次通过率 / 平均重试次数"两个指标的唯一来源。
     * 返修轮次<b>不</b>递增它（返修是同一次尝试内部的补救，不是 item 级重试）。</p>
     *
     * @param item 子项
     * @return {@code attempts + 1}
     */
    private int attemptNoOf(GenTaskItem item) {
        return (item.getAttempts() == null ? 0 : item.getAttempts()) + 1;
    }

    /**
     * 落库用的模型名：任务配置优先，其次全局配置。
     *
     * <p><b>不再优先用响应回显</b>：{@code LlmRequest} 里没有 model 字段（模型由 provider +
     * {@code ai.model} 决定），所以"任务/配置里写的模型"就是本次实际使用的模型；
     * 上游回显反而是网关二次加工后的值。真正按模型归集成本的 {@code llm_call_log}
     * 由编排层用响应回显优先的口径记录，两条口径各服务各的报表。</p>
     *
     * @param task 所属任务（可带本次提交的 model）
     * @return 非空模型名
     */
    private String resolveModel(GenTask task) {
        if (task != null && !isBlank(task.getModel())) {
            return task.getModel();
        }
        return nullToEmpty(aiProperties.getModel());
    }

    // ------------------------------------------------------------------ 小工具

    /**
     * 内容哈希：{@code sha256(title \n steps \n expected)}。
     *
     * <p><b>为什么不带 {@code status}/{@code confidence}/{@code promptVersion}</b>：
     * 那三个会随评审与模板改版变化，掺进哈希后"同一份内容"会被算成不同内容，
     * 查重（{@code idx_test_case_content_hash}）就失效了。
     * 分隔符用 {@code \n}：三个字段都是人写文本，{@code \n} 是它们天然的分隔，
     * 且碰撞边界（{@code "a\nb"} vs {@code "a"+"\nb"}）在标题里会被 schema 的长度与
     * 内容约束挡住——这里不需要 {@code TestPointHasher} 那种 {@code \0} 的强度。</p>
     *
     * <p><b>关于又写了一份 SHA-256</b>：{@code TestPointHasher} / {@code DocService} /
     * {@code TokenService} / {@code MockLlmProvider} 各有各的截断与编码口径，
     * 合并属于独立清理任务，不在本文件顺手做（与 {@code TestPointHasher} 类注释同一立场）。</p>
     *
     * @param title     标题（落库值）
     * @param stepsJson 步骤 JSON（落库值）
     * @param expected  预期（落库值）
     * @return 64 位小写十六进制
     */
    private static String contentHashOf(String title, String stepsJson, String expected) {
        String material = title + "\n" + stepsJson + "\n" + expected;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException ex) {
            // JDK 一定带 SHA-256；走到这里说明运行环境被动过手脚，不能吞
            throw new IllegalStateException("运行环境缺少 SHA-256，无法计算用例去重键", ex);
        }
    }

    /**
     * 压成一行（few-shot 素材用）：换行会把"一行一条"的素材列表切成碎片。
     *
     * @param text 原文
     * @return 单行文本（超长截断到 {@link #FEW_SHOT_TEXT_MAX}）
     */
    private static String oneLine(String text) {
        String flat = nullToEmpty(text).replaceAll("\\s+", " ").trim();
        return flat.length() <= FEW_SHOT_TEXT_MAX
                ? flat
                : flat.substring(0, FEW_SHOT_TEXT_MAX) + "…";
    }

    /**
     * 追加一行"标签：值"（值为空白时整行省略，避免提示词里出现 {@code 描述：null}）。
     *
     * @param out   输出缓冲
     * @param label 标签
     * @param value 值
     */
    private static void appendLine(StringBuilder out, String label, String value) {
        if (isBlank(value)) {
            return;
        }
        out.append(label).append('：').append(value.trim()).append('\n');
    }

    /**
     * 截断到上限（{@code test_case.title} 是 VARCHAR，超长会 1406）。
     *
     * @param text      原文
     * @param maxLength 上限
     * @return 截断后的文本
     */
    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * 截断章节正文（只用于喂模型，不落库）。
     *
     * @param content 正文
     * @return 不超过 {@link #SECTION_CONTENT_MAX} 的文本
     */
    private static String abbreviate(String content) {
        return content.length() <= SECTION_CONTENT_MAX
                ? content
                : content.substring(0, SECTION_CONTENT_MAX) + "…";
    }

    /**
     * 空白判断（null / 空串 / 全空白）。
     *
     * @param value 原值
     * @return 是否空白
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 空值转空串（提示词变量不接受 null；{@code Map.of} 也不接受）。
     *
     * @param value 原值
     * @return 非 null 字符串
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
