package com.aittest.testpoint.service;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.PageQuery;
import com.aittest.common.PageResult;
import com.aittest.common.enums.CaseType;
import com.aittest.common.enums.RiskLevel;
import com.aittest.common.enums.TestPointStatus;
import com.aittest.testpoint.domain.TestPoint;
import com.aittest.testpoint.domain.TestPointHasher;
import com.aittest.testpoint.dto.BatchStatusReq;
import com.aittest.testpoint.dto.TestPointCreateReq;
import com.aittest.testpoint.dto.TestPointDraft;
import com.aittest.testpoint.dto.TestPointUpdateReq;
import com.aittest.testpoint.dto.TestPointVO;
import com.aittest.testpoint.mapper.TestPointMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 测试点的落库与去重（AI 抽取、人工手补共用同一套写入语义）。
 *
 * <p><b>这个类解决的是"重复抽取怎么办"</b>（验收 ④：同一文档抽取两次，数量不翻倍）。
 * 判据是 {@link TestPointHasher} 的 {@code content_hash} + 唯一索引
 * {@code uk_test_point_doc_hash}。规则一句话：<b>已有的一字不动，新来的插进去，这轮没再出现的标记取代。</b></p>
 *
 * <p><b>四种状态的处置各不相同，这是本类最重要的一张表</b>：</p>
 * <table>
 *   <caption>重抽时对已有行的处置</caption>
 *   <tr><th>已有行状态</th><th>本轮又抽到同一个 hash</th><th>本轮没抽到</th></tr>
 *   <tr><td>{@code ACTIVE}</td><td><b>一字不动</b>（人工修订的描述/风险等级优先）</td><td>置 {@code SUPERSEDED}</td></tr>
 *   <tr><td>{@code DISABLED}</td><td><b>一字不动</b>（人停用的，AI 说了不算）</td><td>不动</td></tr>
 *   <tr><td>{@code SUPERSEDED}</td><td><b>复活</b>为 {@code ACTIVE}（描述仍不动）</td><td>不动</td></tr>
 * </table>
 *
 * <p><b>为什么"已有的一字不动"而不是"用模型的新值刷新"</b>：评审页允许人改描述、调风险等级（验收 ⑥）。
 * 若重抽时用模型输出覆盖，人的每一次修订都会被下一次抽取冲掉，而用户<b>看不出</b>发生了什么——
 * 他只会觉得"这个系统老是丢我的修改"。反过来不覆盖，最坏情况是描述停留在旧版本，
 * 而这在界面上是看得见、可再改的。<b>可见的保守，好过不可见的覆盖。</b></p>
 *
 * <p><b>为什么 {@code SUPERSEDED} 要能复活</b>：模型抽取是非确定性的，同一段内容两次调用可能
 * 少抽一条。若不能复活，第 1 轮的点在第 2 轮消失后就被永久埋掉，第 3 轮即使抽到也保持
 * {@code SUPERSEDED}——表现是"这个点明明该有，列表里没有"。{@code SUPERSEDED} 的语义是
 * "上一轮有、这一轮没有"，不是"这个点作废了"；作废是人的动作，对应 {@code DISABLED}。</p>
 *
 * <p><b>事务边界</b>：整个批次一个事务。中途失败回滚到"这一轮什么都没发生"，
 * 不会留下"插了 5 条、又把 3 条标成取代、然后崩了"的半成品状态。</p>
 *
 * @author 寇豆码
 */
@Service
public class TestPointService {

    private static final Logger log = LoggerFactory.getLogger(TestPointService.class);

    /** 测试点 Mapper。 */
    private final TestPointMapper testPointMapper;

    public TestPointService(TestPointMapper testPointMapper) {
        this.testPointMapper = testPointMapper;
    }

    /**
     * 取某文档下当前活动的测试点。
     *
     * @param docId 文档 ID
     * @return 活动测试点（按创建顺序）
     */
    public List<TestPoint> listActive(Long docId) {
        return testPointMapper.selectActiveByDocId(docId);
    }

    /**
     * 分页查询测试点。
     *
     * <p><b>默认只返回 {@code ACTIVE}</b>：评审页不该看到 {@code SUPERSEDED}（上一轮的旧点）
     * 与 {@code DISABLED}（人已停用的），除非用户显式筛选。把三类混在一页里返回，
     * 用户会看到"我明明删掉的点怎么又出现了"。</p>
     *
     * @param docId    文档（必填：没有"全库测试点"这个概念，列表永远是某篇文档的）
     * @param status   状态过滤（空则 ACTIVE）
     * @param category 分类过滤（空则全部）
     * @param query    分页与排序
     * @return 分页结果
     */
    public PageResult<TestPointVO> page(Long docId, String status, String category, PageQuery query) {
        if (docId == null) {
            throw new BizException(ErrorCode.E_PARAM_INVALID, "必须指定 docId：测试点列表是按文档查看的");
        }
        String wantedStatus = status == null || status.isBlank()
                ? TestPointStatus.ACTIVE.code()
                : status.trim();

        LambdaQueryWrapper<TestPoint> wrapper = new LambdaQueryWrapper<TestPoint>()
                .eq(TestPoint::getDocId, docId)
                .eq(TestPoint::getStatus, wantedStatus)
                .eq(category != null && !category.isBlank(), TestPoint::getCategory, category);
        applySort(wrapper, query);

        IPage<TestPoint> result = testPointMapper.selectPage(
                new Page<>(query.pageOrDefault(), query.sizeOrDefault()), wrapper);
        List<TestPointVO> records = result.getRecords().stream()
                .map(TestPointVO::from)
                .collect(Collectors.toList());
        return PageResult.of(records, result.getTotal(), query.pageOrDefault(), query.sizeOrDefault());
    }

    /**
     * 取单条。
     *
     * @param id 主键
     * @return 出参
     */
    public TestPointVO get(Long id) {
        return TestPointVO.from(require(id));
    }

    /**
     * 手动补点。
     *
     * <p><b>与 AI 抽的点共用同一张表与同一套状态</b>，差别只在 {@code promptVersion}（手补为空串）。
     * 若撞上已有的 {@code contentHash}，唯一索引 {@code uk_test_point_doc_hash} 会拦成
     * {@code 1062} → {@code 40902 数据已存在}——这是<b>正确</b>的行为：同一个点不该有两条。</p>
     *
     * @param req 入参
     * @return 新建的测试点
     */
    public TestPointVO createManual(TestPointCreateReq req) {
        requireEnum(req.category(), CaseType.class, "分类");
        String riskLevel = req.riskLevel() == null || req.riskLevel().isBlank()
                ? RiskLevel.MEDIUM.code()
                : req.riskLevel().trim();
        requireEnum(riskLevel, RiskLevel.class, "风险等级");

        TestPoint entity = new TestPoint();
        entity.setDocId(req.docId());
        entity.setTitle(req.title().trim());
        entity.setCategory(req.category().trim());
        entity.setRiskLevel(riskLevel);
        entity.setDescription(req.description() == null ? "" : req.description());
        // 手补点没有来源文档上下文：sourceRef 留空，promptVersion 留空（区分于 AI 抽的点）
        entity.setSourceRef("");
        entity.setStatus(TestPointStatus.ACTIVE.code());
        entity.setPromptVersion("");
        entity.setContentHash(TestPointHasher.hashOf(req.docId(), req.title(), req.category()));
        testPointMapper.insert(entity);
        log.info("[TESTPOINT] 手动补点 docId={} title={} id={}", req.docId(), req.title(), entity.getId());
        return TestPointVO.from(entity);
    }

    /**
     * 更新测试点（标题/分类/风险等级/描述/状态）。
     *
     * <p><b>改标题或分类会重算 {@code contentHash}</b>（见 {@link TestPointUpdateReq} 类注释）：
     * 去重键是 {@code sha256(docId + title + category)}，不同步重算就会留下一个
     * 与实际内容对不上的脏键，下次重抽时唯一索引拦下的将是一个完全不相干的插入。</p>
     *
     * @param id  主键
     * @param req 可选字段更新
     * @return 更新后的测试点
     */
    public TestPointVO update(Long id, TestPointUpdateReq req) {
        TestPoint entity = require(id);

        String newTitle = req.title() == null ? entity.getTitle() : req.title().trim();
        String newCategory = req.category() == null ? entity.getCategory() : req.category().trim();
        boolean identityChanged = !newTitle.equals(entity.getTitle())
                || !newCategory.equals(entity.getCategory());

        if (req.category() != null) {
            requireEnum(newCategory, CaseType.class, "分类");
        }
        if (req.riskLevel() != null) {
            requireEnum(req.riskLevel().trim(), RiskLevel.class, "风险等级");
            entity.setRiskLevel(req.riskLevel().trim());
        }
        if (req.description() != null) {
            entity.setDescription(req.description());
        }
        if (req.status() != null) {
            // SUPERSEDED 是抽取轮次的产物，不允许人手工置位；人能给的只有"启用/停用"
            String wanted = req.status().trim();
            if (!TestPointStatus.ACTIVE.code().equals(wanted)
                    && !TestPointStatus.DISABLED.code().equals(wanted)) {
                throw new BizException(ErrorCode.E_PARAM_INVALID,
                        "状态只能是 ACTIVE 或 DISABLED，SUPERSEDED 由抽取过程自动维护");
            }
            entity.setStatus(wanted);
        }
        entity.setTitle(newTitle);
        entity.setCategory(newCategory);
        if (identityChanged) {
            entity.setContentHash(TestPointHasher.hashOf(entity.getDocId(), newTitle, newCategory));
        }
        testPointMapper.updateById(entity);
        return TestPointVO.from(entity);
    }

    /**
     * 批量启停。
     *
     * @param req 入参
     * @return 实际更新条数
     */
    public int batchStatus(BatchStatusReq req) {
        String wanted = req.status() == null ? "" : req.status().trim();
        if (!TestPointStatus.ACTIVE.code().equals(wanted)
                && !TestPointStatus.DISABLED.code().equals(wanted)) {
            throw new BizException(ErrorCode.E_PARAM_INVALID,
                    "状态只能是 ACTIVE 或 DISABLED");
        }
        int updated = 0;
        for (Long id : req.ids()) {
            TestPoint entity = require(id);
            if (wanted.equals(entity.getStatus())) {
                continue;
            }
            entity.setStatus(wanted);
            testPointMapper.updateById(entity);
            updated++;
        }
        log.info("[TESTPOINT] 批量启停 status={} 请求={} 实际变更={}", wanted, req.ids().size(), updated);
        return updated;
    }

    /**
     * 排序（白名单列，未登记的回落到创建时间）。
     *
     * <p>与 {@code DocService#applySort} 同一套写法：只允许白名单列，
     * 并在末尾补一个稳定主键排序，避免同时间戳下翻页抖动。</p>
     *
     * @param wrapper 查询条件
     * @param query   分页参数
     */
    private void applySort(LambdaQueryWrapper<TestPoint> wrapper, PageQuery query) {
        String column = query.sortColumnOrDefault("created_at");
        boolean desc = query.isDesc();
        switch (column) {
            case "updated_at" -> wrapper.orderBy(true, desc, TestPoint::getUpdatedAt);
            case "title" -> wrapper.orderBy(true, desc, TestPoint::getTitle);
            case "category" -> wrapper.orderBy(true, desc, TestPoint::getCategory);
            case "risk_level" -> wrapper.orderBy(true, desc, TestPoint::getRiskLevel);
            default -> wrapper.orderBy(true, desc, TestPoint::getCreatedAt);
        }
        wrapper.orderByDesc(TestPoint::getId);
    }

    /**
     * 按主键取实体。
     *
     * @param id 主键
     * @return 实体
     * @throws BizException 不存在
     */
    private TestPoint require(Long id) {
        TestPoint entity = id == null ? null : testPointMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("测试点");
        }
        return entity;
    }

    /**
     * 校验编码取值在枚举内。
     *
     * <p>把允许值一次列全（与 {@code PromptRenderer} 缺变量报错同一思路）：
     * 只说"分类不合法"，用户要猜五次才知道有哪些可选。</p>
     *
     * @param value 待校验值
     * @param type  枚举类型
     * @param label 中文名（异常文案用）
     * @throws BizException 取值不在枚举内
     */
    private void requireEnum(String value, Class<? extends com.aittest.common.enums.CodedEnum> type,
                             String label) {
        for (com.aittest.common.enums.CodedEnum item : type.getEnumConstants()) {
            if (item.code().equals(value)) {
                return;
            }
        }
        String allowed = java.util.Arrays.stream(type.getEnumConstants())
                .map(com.aittest.common.enums.CodedEnum::code)
                .collect(Collectors.joining("/"));
        throw new BizException(ErrorCode.E_PARAM_INVALID, label + "取值不合法，可选值：" + allowed);
    }

    /**
     * 合并**一个单元**的抽取结果：去重、插入新点、复活被取代的点。
     *
     * <p><b>本方法不做"取代消失的点"</b>——那是整篇文档抽完之后才能判定的事。
     * 曾经把两者写在一起，结果是：文档有 N 个单元时，第 1 个单元处理完会把后面 N-1 个单元
     * 的产出标成 {@code SUPERSEDED}，第 2 个单元再把它们救回来并反手标掉第 1 个的——
     * 互相踩，最终 ACTIVE 数随机。<b>去重是单元级的，取代是文档级的</b>，
     * 两个动作的时间尺度不同，不能放在同一个事务段里。取代由
     * {@link #supersedeMissing(Long, Set)} 在所有单元完成后统一执行。</p>
     *
     * <p>同批内的重复（模型对同一段内容返回了两条同名同分类的点）只保留<b>第一条</b>：
     * "哪条算数"必须是确定的，按返回顺序取首条即可；若改成"取最长的描述"，
     * 模型措辞的随机性会让落库结果也随机，回归测试无法稳定复现。</p>
     *
     * @param docId        所属文档
     * @param drafts       模型抽出的点（去重前）
     * @param operationId  来源接口（纯文本为 {@code null}）
     * @param sectionId    来源章节（OpenAPI 为 {@code null}）
     * @param sourceRef    权威来源定位（本批所有点共用）
     * @param promptVersion 抽取所用的提示词版本（如 {@code testpoint_extract.v1}）
     * @return 本单元的落库结果：涉及的去重键（含命中已有行的）+ 实际新增条数
     */
    @Transactional(rollbackFor = Exception.class)
    public MergeOutcome mergeExtraction(Long docId, List<TestPointDraft> drafts,
                                        Long operationId, Long sectionId,
                                        String sourceRef, String promptVersion) {
        List<TestPoint> existing = testPointMapper.selectByDocId(docId);
        Map<String, TestPoint> byHash = new LinkedHashMap<>();
        for (TestPoint row : existing) {
            byHash.putIfAbsent(row.getContentHash(), row);
        }

        Set<String> seen = new HashSet<>();
        int inserted = 0;
        for (TestPointDraft draft : drafts) {
            String hash = TestPointHasher.hashOf(docId, draft.title(), draft.category());
            if (!seen.add(hash)) {
                continue;
            }
            TestPoint old = byHash.get(hash);
            if (old != null) {
                // 已存在：ACTIVE/DISABLED 一字不动；SUPERSEDED 复活（描述仍不动）
                if (TestPointStatus.SUPERSEDED.code().equals(old.getStatus())) {
                    old.setStatus(TestPointStatus.ACTIVE.code());
                    testPointMapper.updateById(old);
                }
                continue;
            }
            testPointMapper.insert(toEntity(docId, draft, operationId, sectionId, sourceRef,
                    promptVersion, hash));
            inserted++;
        }

        log.info("[TESTPOINT] 单元落库 docId={} sourceRef={} 模型返回={} 新增={}",
                docId, sourceRef, drafts.size(), inserted);
        return new MergeOutcome(seen, inserted);
    }

    /**
     * 一个单元的落库结果。
     *
     * @param keptHashes 本单元涉及的去重键（含命中已有行的），供文档级取代判定汇总
     * @param inserted   实际新增条数（命中已有行的不算，复活 {@code SUPERSEDED} 也不算）
     * @author 寇豆码
     */
    public record MergeOutcome(Set<String> keptHashes, int inserted) {
    }

    /**
     * 把本轮（整篇文档的一次抽取）**没再产出**的活动点置 {@code SUPERSEDED}。
     *
     * <p>必须在该文档所有单元处理完之后调用<b>恰好一次</b>。
     * 提前调用会把还没处理的单元的产出标掉；调用多次则互相踩。
     * <b>不是删除</b>：这些点可能已经被生成的用例引用着，删了外键就断了。</p>
     *
     * <p><b>调用方必须把"抽取失败的单元"的点也算进 {@code keptHashes}</b>（见
     * {@link #keptHashesOfUnit}）：取代的语义是"原文里已经没有这个点了"，
     * 而一次模型超时并不能证明这一点。把失败单元的点漏在 kept 之外，
     * 一次上游抖动就会静默废掉一批有效测试点——和提前取代是同一类事故。</p>
     *
     * @param docId      文档
     * @param keptHashes 本轮全部单元产出（含命中已有行、含失败单元的原有点）的去重键
     * @return 被标记取代的条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int supersedeMissing(Long docId, Set<String> keptHashes) {
        int superseded = 0;
        for (TestPoint old : testPointMapper.selectByDocId(docId)) {
            if (TestPointStatus.ACTIVE.code().equals(old.getStatus())
                    && !keptHashes.contains(old.getContentHash())) {
                old.setStatus(TestPointStatus.SUPERSEDED.code());
                testPointMapper.updateById(old);
                superseded++;
            }
        }
        if (superseded > 0) {
            log.info("[TESTPOINT] 本轮未再产出的点置 SUPERSEDED docId={} 条数={}", docId, superseded);
        }
        return superseded;
    }

    /**
     * 某个待分析单元当前已有的测试点去重键（<b>失败保全</b>用）。
     *
     * <p>单元抽取失败时，把它已有的点算进 {@code kept}，本轮就不取代它们——
     * 我们并没有证据说明原文里这些点消失了，只是这一轮没看清。
     * 下一轮该单元成功后，真正消失的点才会被正常取代。</p>
     *
     * @param docId       文档
     * @param operationId 来源接口（纯文本为 {@code null}）
     * @param sectionId   来源章节（OpenAPI 为 {@code null}）
     * @return 该单元所有测试点（含 {@code SUPERSEDED}，反正取代只动 {@code ACTIVE}）的去重键
     */
    public Set<String> keptHashesOfUnit(Long docId, Long operationId, Long sectionId) {
        Set<String> hashes = new HashSet<>();
        for (TestPoint row : testPointMapper.selectByDocId(docId)) {
            boolean sameUnit = operationId != null
                    ? operationId.equals(row.getOperationId())
                    : sectionId != null && sectionId.equals(row.getSectionId());
            if (sameUnit) {
                hashes.add(row.getContentHash());
            }
        }
        return hashes;
    }

    /**
     * 组装实体。
     *
     * <p><b>{@code sourceRef} 用入参而不是 {@code draft.sourceRef()}</b>：模型可能把来源写错
     * （比如给 A 接口的点写上 B 接口的 operationId），而 {@code sourceRef} 的唯一用途是反查回
     * 原文，反查的真源是数据库外键。让模型说了算，等于把一个可确定的事实交给不确定的输出。
     * 模型写的值留在 {@link TestPointDraft} 里可用于诊断，但不落库。</p>
     *
     * @param docId         文档
     * @param draft         模型产出
     * @param operationId   来源接口
     * @param sectionId     来源章节
     * @param sourceRef     权威来源定位
     * @param promptVersion 提示词版本
     * @param contentHash   去重键
     * @return 实体
     */
    private TestPoint toEntity(Long docId, TestPointDraft draft,
                               Long operationId, Long sectionId,
                               String sourceRef, String promptVersion, String contentHash) {
        TestPoint entity = new TestPoint();
        entity.setDocId(docId);
        entity.setOperationId(operationId);
        entity.setSectionId(sectionId);
        entity.setTitle(draft.title().trim());
        entity.setCategory(draft.category());
        entity.setRiskLevel(draft.riskLevel());
        entity.setDescription(draft.description());
        entity.setSourceRef(sourceRef);
        entity.setStatus(TestPointStatus.ACTIVE.code());
        entity.setPromptVersion(promptVersion);
        entity.setContentHash(contentHash);
        return entity;
    }
}
