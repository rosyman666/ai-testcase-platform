package com.aittest.aicore.prompt;

import com.aittest.aicore.LlmException;
import com.aittest.aicore.LlmMessage;
import com.aittest.aicore.LlmRequest;
import com.aittest.aicore.LlmResponse;
import com.aittest.aicore.prompt.dto.PromptPreviewReq;
import com.aittest.aicore.prompt.dto.PromptPreviewVO;
import com.aittest.aicore.prompt.dto.PromptTemplateCreateReq;
import com.aittest.aicore.prompt.dto.PromptTemplateDetailVO;
import com.aittest.aicore.prompt.dto.PromptTemplateVO;
import com.aittest.aicore.provider.LlmProviderRouter;
import com.aittest.aicore.provider.LoggingLlmProvider;
import com.aittest.aicore.structured.JsonRepairer;
import com.aittest.aicore.structured.JsonSchemaValidator;
import com.aittest.aicore.structured.StructuredOutputParser;
import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.config.AiProperties;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提示词模板服务：查询、版本管理、渲染、试跑。
 *
 * <p><b>版本生效的唯一规则</b>（§8.6）：同 code 下 {@code is_active=1} 的<b>有且只有一行</b>，
 * 由本类保证。这个"唯一"不是靠业务自觉，而是靠两个写方法：新建版本与激活版本，
 * 都先把同 code 的全部版本置 0 再置 1。少做这一步的后果极其隐蔽——
 * {@code selectActiveByCode} 里带了 {@code LIMIT 1}，多行 active 时<b>不报错</b>，
 * 只是"有时候用新版、有时候用旧版"，表现为生成质量随机抖动，谁也查不出来。</p>
 *
 * <p><b>为什么渲染/试跑放在 service 而不是 controller</b>：试跑会真实调用模型（可能产生费用），
 * 属于业务动作；controller 只做"入参校验 → 调 service → 转 VO"（§8.4）。</p>
 *
 * @author 寇豆码
 */
@Service
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);

    /** 试跑返回的原始输出上限（够看清形状，又不至于把响应体撑到几百 KB）。 */
    private static final int PREVIEW_CONTENT_MAX = 8000;

    /** 模板 Mapper。 */
    private final PromptTemplateMapper promptTemplateMapper;

    /** 渲染器。 */
    private final PromptRenderer promptRenderer;

    /** 结构化解析（试跑时校验 schema）。 */
    private final StructuredOutputParser structuredOutputParser;

    /** schema 读取（试跑时取 schema 原文喂给 mock / response_format）。 */
    private final JsonSchemaValidator jsonSchemaValidator;

    /** 记账装饰器（试跑也必须走它，否则试跑不记账，"每次调用恰好 1 条"就有例外了）。 */
    private final LoggingLlmProvider loggingLlmProvider;

    /** 温度配置。 */
    private final AiProperties aiProperties;

    /** 供应商路由（试跑出参里回显当前供应商）。 */
    private final LlmProviderRouter llmProviderRouter;

    public PromptTemplateService(PromptTemplateMapper promptTemplateMapper,
                                 PromptRenderer promptRenderer,
                                 StructuredOutputParser structuredOutputParser,
                                 JsonSchemaValidator jsonSchemaValidator,
                                 LoggingLlmProvider loggingLlmProvider,
                                 AiProperties aiProperties,
                                 LlmProviderRouter llmProviderRouter) {
        this.promptTemplateMapper = promptTemplateMapper;
        this.promptRenderer = promptRenderer;
        this.structuredOutputParser = structuredOutputParser;
        this.jsonSchemaValidator = jsonSchemaValidator;
        this.loggingLlmProvider = loggingLlmProvider;
        this.aiProperties = aiProperties;
        this.llmProviderRouter = llmProviderRouter;
    }

    /**
     * 模板列表。
     *
     * @param scene      场景过滤（空则全部）
     * @param activeOnly 只看生效版本
     * @return 模板列表（新版本在前）
     */
    public List<PromptTemplateVO> list(String scene, boolean activeOnly) {
        LambdaQueryWrapper<PromptTemplate> wrapper = new LambdaQueryWrapper<PromptTemplate>()
                .eq(scene != null && !scene.isBlank(), PromptTemplate::getScene, scene)
                .eq(activeOnly, PromptTemplate::getIsActive, 1)
                .orderByAsc(PromptTemplate::getCode)
                .orderByDesc(PromptTemplate::getVersion);
        return promptTemplateMapper.selectList(wrapper).stream()
                .map(PromptTemplateVO::from)
                .collect(Collectors.toList());
    }

    /**
     * 模板详情（含正文）。
     *
     * @param id 主键
     * @return 详情
     */
    public PromptTemplateDetailVO detail(Long id) {
        return PromptTemplateDetailVO.from(require(id));
    }

    /**
     * 新建版本：同 code 的版本号自增，并立即生效。
     *
     * <p>用一个事务包住"失效全部 → 插入新版本"：中间失败会留下"一个 active 都没有"的状态，
     * 此时所有生成请求都会报"模板未生效"，而原因指向的是别人上一次没成功的提交。</p>
     *
     * @param req 入参
     * @return 新版本详情
     */
    @Transactional(rollbackFor = Exception.class)
    public PromptTemplateDetailVO createVersion(PromptTemplateCreateReq req) {
        Integer maxVersion = promptTemplateMapper.selectMaxVersion(req.code());
        int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;

        // 先失效旧版本："插入 active=1 之后再失效旧版本"会有一瞬间两行 active，
        // 那个瞬间正好有请求进来就会用到两套模板，而这类问题事后完全无法复现
        promptTemplateMapper.deactivateByCode(req.code());

        PromptTemplate template = new PromptTemplate();
        template.setCode(req.code());
        template.setVersion(nextVersion);
        template.setScene(req.scene());
        template.setName(req.name());
        template.setContent(req.content());
        template.setOutputSchema(req.outputSchema() == null ? "" : req.outputSchema());
        template.setModelHint(req.modelHint() == null ? "" : req.modelHint());
        template.setIsActive(1);
        promptTemplateMapper.insert(template);

        log.info("[PROMPT] 新建模板版本 code={} version={} id={}", req.code(), nextVersion, template.getId());
        return PromptTemplateDetailVO.from(template);
    }

    /**
     * 激活指定版本（同 code 其它版本自动失效）。
     *
     * @param id 主键
     * @return 激活后的详情
     */
    @Transactional(rollbackFor = Exception.class)
    public PromptTemplateDetailVO activate(Long id) {
        PromptTemplate template = require(id);
        promptTemplateMapper.deactivateByCode(template.getCode());
        // is_active 是"版本管理状态"，不是"内容修改"：抑制 ON UPDATE，保留 updated_at 的原义
        // （它回答"这版内容最后改于何时"，见 PromptTemplateMapper 的注释）
        promptTemplateMapper.activateById(id);
        template.setIsActive(1);
        log.info("[PROMPT] 激活模板 code={} version={} id={}", template.getCode(), template.getVersion(), id);
        return PromptTemplateDetailVO.from(template);
    }

    /**
     * 取某 code 当前生效版本。
     *
     * @param code 模板编码
     * @return 生效版本
     * @throws BizException 没有生效版本（说明模板未 seed 或全部被停用）
     */
    public PromptTemplate activeOf(String code) {
        PromptTemplate template = promptTemplateMapper.selectActiveByCode(code);
        if (template == null) {
            throw new BizException(ErrorCode.E_NOT_FOUND,
                    "模板 " + code + " 没有生效版本，请先检查启动日志中的 [PROMPT] 装载结果");
        }
        return template;
    }

    /**
     * 渲染某个 code 的生效版本（T05/T07 直接用这个，不必自己查模板）。
     *
     * @param code      模板编码
     * @param variables 变量表
     * @return 渲染后的提示词
     */
    public String render(String code, Map<String, ?> variables) {
        return promptRenderer.render(activeOf(code).getContent(), variables);
    }

    /**
     * 试跑：渲染 → 真实调用（走记账装饰器）→ 解析/schema 校验。
     *
     * @param id  模板主键
     * @param req 入参
     * @return 试跑结果
     */
    public PromptPreviewVO preview(Long id, PromptPreviewReq req) {
        PromptTemplate template = require(id);
        Map<String, String> variables = req.variables() == null ? Map.of() : req.variables();
        String rendered = promptRenderer.render(template.getContent(), variables);

        String schemaPath = template.getOutputSchema();
        boolean hasSchema = schemaPath != null && !schemaPath.isBlank();
        // 把 schema 原文放进请求：mock 靠它认形状；真实 provider 靠它决定要不要 response_format=json_object
        String schemaText = hasSchema ? jsonSchemaValidator.readSchemaText(schemaPath) : null;

        LlmRequest request = LlmRequest.builder()
                .scene(template.getScene())
                .attemptNo(req.attemptNo() == null ? 1 : Math.max(1, req.attemptNo()))
                // 模板正文整体作为用户消息：这些模板自身就带角色设定与输出约束，不必再拆系统提示词
                .message(LlmMessage.user(rendered))
                .temperature(temperatureOf(template.getScene()))
                .jsonSchema(schemaText)
                .build();

        LlmResponse response = loggingLlmProvider.chat(request);

        boolean validateSchema = req.validateSchema() == null || req.validateSchema();
        Boolean schemaValid = null;
        String schemaError = null;
        Integer parsedCount = null;
        boolean repaired = false;
        boolean truncated = false;

        if (hasSchema) {
            JsonRepairer.RepairOutcome outcome = JsonRepairer.repairWithMeta(response.getContent());
            repaired = outcome.changed();
            truncated = outcome.truncated();
            if (validateSchema) {
                try {
                    if (isArrayScene(template.getScene())) {
                        StructuredOutputParser.ParseResult result =
                                structuredOutputParser.parseArrayWithMeta(response.getContent(), schemaPath);
                        schemaValid = Boolean.TRUE;
                        parsedCount = result.items().size();
                    } else {
                        // 归因类输出是**单个对象**（一条执行结果只判一次），不能按数组解包
                        jsonSchemaValidator.validate(structuredOutputParser.parseToNode(response.getContent()),
                                schemaPath);
                        schemaValid = Boolean.TRUE;
                        parsedCount = 1;
                    }
                } catch (LlmException ex) {
                    // 试跑时 schema 不合规不算请求失败：用户正是想知道"这版模板能不能过校验"
                    schemaValid = Boolean.FALSE;
                    schemaError = ex.getMessage();
                }
            }
        }

        return new PromptPreviewVO(
                llmProviderRouter.providerName(),
                response.getModel(),
                abbreviate(response.getContent()),
                response.getUsage().promptTokens(),
                response.getUsage().completionTokens(),
                (int) Math.min(response.getLatencyMs(), Integer.MAX_VALUE),
                repaired,
                truncated,
                schemaValid,
                schemaError,
                parsedCount);
    }

    /**
     * 该场景的期望输出是不是"数组"。
     *
     * <p>抽点/生成/修复都产出**列表**（一次多条测试点或用例），归因只产出**一个对象**
     * （一条执行结果判一次）。区别放在这里而不是去解析 schema 的 {@code type} 字段：
     * schema 的根类型是"我们自己的 schema 文件怎么写"决定的，读它等于把校验器当配置解析器用，
     * 一旦将来用 {@code $ref} 或 {@code anyOf} 包一层就解析不出来了。</p>
     *
     * @param scene 场景
     * @return 期望数组返回 {@code true}
     */
    private boolean isArrayScene(String scene) {
        return !"FAILURE_TRIAGE".equals(scene);
    }

    /**
     * 按场景取温度（§8.7 第 1 条：修复必须 0）。
     *
     * @param scene 场景
     * @return 温度
     */
    private double temperatureOf(String scene) {
        if (scene == null) {
            return aiProperties.getTemperature().getTestpoint();
        }
        return switch (scene) {
            case "CASE_GENERATE" -> aiProperties.getTemperature().getGenerate();
            case "CASE_REPAIR", "FAILURE_TRIAGE" -> aiProperties.getTemperature().getRepair();
            default -> aiProperties.getTemperature().getTestpoint();
        };
    }

    /**
     * 按主键取模板。
     *
     * @param id 主键
     * @return 模板实体
     * @throws BizException 不存在
     */
    private PromptTemplate require(Long id) {
        PromptTemplate template = id == null ? null : promptTemplateMapper.selectById(id);
        if (template == null) {
            throw BizException.notFound("提示词模板");
        }
        return template;
    }

    /**
     * 截断试跑输出。
     *
     * @param content 原始输出
     * @return 截断后的文本
     */
    private String abbreviate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= PREVIEW_CONTENT_MAX
                ? content
                : content.substring(0, PREVIEW_CONTENT_MAX) + "…（已截断）";
    }
}
