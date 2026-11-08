package com.aittest.testcase.service;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.PageQuery;
import com.aittest.common.PageResult;
import com.aittest.common.enums.CaseStatus;
import com.aittest.common.enums.CodedEnum;
import com.aittest.testcase.domain.CaseReview;
import com.aittest.testcase.domain.TestCase;
import com.aittest.testcase.dto.BatchReviewReq;
import com.aittest.testcase.dto.BatchReviewResult;
import com.aittest.testcase.dto.CaseUpdateReq;
import com.aittest.testcase.dto.ReviewReq;
import com.aittest.testcase.dto.TestCaseVO;
import com.aittest.testcase.mapper.CaseReviewMapper;
import com.aittest.testcase.mapper.TestCaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 用例评审的<b>读取侧</b>：列表、详情，并把三个写操作转发给 {@link CaseReviewService}。
 *
 * <p><b>为什么写操作放在另一个类</b>：评审写入的四条硬约束（只追加 / 同事务 / 锁行 /
 * 先校验字典）是同一个话题，塞进查询类里会让"这个类到底负责什么"变得含糊。
 * 更实际的理由是<b>不能出现循环依赖</b>：写操作执行完要回读详情（见
 * {@link #review} / {@link #update}），若把读也放进写类，两个类就得互相注入，
 * 构造器注入直接启动失败。</p>
 *
 * <p><b>写操作为什么"写完再回读"</b>：返回的 VO 必须是<b>库里真实的样子</b>。
 * 用内存里的实体拼一个 VO 会漏掉两件事：评审行的 {@code created_at} 由数据库默认值写，
 * 内存里是 {@code null}；{@code updated_at} 由 {@code ON UPDATE CURRENT_TIMESTAMP(3)} 推进，
 * 内存里还是旧值。前端拿到 {@code null} 的时间戳会显示成 1970 年。
 * 多两次 SELECT 换"返回值与库一致"，值得。</p>
 *
 * @author 寇豆码
 */
@Service
public class CaseService {

    private static final Logger log = LoggerFactory.getLogger(CaseService.class);

    /** 用例 Mapper。 */
    private final TestCaseMapper testCaseMapper;

    /** 评审台账 Mapper（只读，用于详情页的历史）。 */
    private final CaseReviewMapper caseReviewMapper;

    /** 评审写入服务（三分支 + 批量）。 */
    private final CaseReviewService caseReviewService;

    public CaseService(TestCaseMapper testCaseMapper,
                       CaseReviewMapper caseReviewMapper,
                       CaseReviewService caseReviewService) {
        this.testCaseMapper = testCaseMapper;
        this.caseReviewMapper = caseReviewMapper;
        this.caseReviewService = caseReviewService;
    }

    /**
     * 用例分页列表（评审看板的主查询）。
     *
     * <p><b>列表不返回评审历史</b>：一页 20 条 × 每条 2~3 次评审 = 40~60 条台账行的额外读取，
     * 而列表根本不展示历史。历史只在 {@link #get(Long)}（详情）里带。</p>
     *
     * <p><b>{@code keyword} 同时匹配标题与预期结果</b>：评审时人往往记得"预期里写着 40001"
     * 却记不住标题，只搜标题会让他以为这条用例不存在。</p>
     *
     * @param taskId  所属任务（可为 {@code null}：不按任务过滤，用于"待评审收件箱"）
     * @param status  状态过滤（可为 {@code null}：全部状态）
     * @param keyword 关键词（可为 {@code null}：不搜索）
     * @param query   分页与排序
     * @return 分页结果（{@code steps} 已解析成对象数组）
     * @throws BizException 状态取值非法 40001
     */
    public PageResult<TestCaseVO> page(Long taskId, String status, String keyword, PageQuery query) {
        PageQuery effectiveQuery = query == null ? new PageQuery() : query;

        LambdaQueryWrapper<TestCase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(taskId != null, TestCase::getTaskId, taskId);
        if (status != null && !status.isBlank()) {
            // 状态是白名单枚举：传了脏值直接 40001，而不是静默返回空列表——
            // 空列表会让前端显示"没有数据"，用户以为筛对了但没有内容
            wrapper.eq(TestCase::getStatus, requireEnum(status.trim(), CaseStatus.class, "状态"));
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(inner -> inner.like(TestCase::getTitle, kw)
                    .or()
                    .like(TestCase::getExpected, kw));
        }
        applySort(wrapper, effectiveQuery);

        IPage<TestCase> result = testCaseMapper.selectPage(
                new Page<>(effectiveQuery.pageOrDefault(), effectiveQuery.sizeOrDefault()), wrapper);
        List<TestCaseVO> records = result.getRecords().stream()
                .map(TestCaseVO::from)
                .collect(Collectors.toList());
        return PageResult.of(records, result.getTotal(),
                effectiveQuery.pageOrDefault(), effectiveQuery.sizeOrDefault());
    }

    /**
     * 用例详情（含评审历史，按 {@code created_at} 升序）。
     *
     * <p><b>升序而不是倒序</b>：评审历史是一条时间线，人要看出"先驳回、后来编辑后采纳"
     * 这个演变过程。倒序会把因果翻过来，读起来像"先采纳又驳回"。</p>
     *
     * <p>同一毫秒内的两条评审（批量评审很容易撞）用主键升序兜底：雪花 ID 单调递增，
     * 等价于插入顺序，翻页与刷新都不会抖动。</p>
     *
     * @param id 用例主键
     * @return 详情
     * @throws BizException 用例不存在 40401
     */
    public TestCaseVO get(Long id) {
        TestCase entity = require(id);
        List<CaseReview> rows = caseReviewMapper.selectList(new LambdaQueryWrapper<CaseReview>()
                .eq(CaseReview::getCaseId, entity.getId())
                .orderByAsc(CaseReview::getCreatedAt)
                .orderByAsc(CaseReview::getId));
        List<TestCaseVO.ReviewItem> reviews = rows.stream()
                .map(TestCaseVO::reviewItemOf)
                .collect(Collectors.toList());
        return TestCaseVO.from(entity, reviews);
    }

    /**
     * 编辑后采纳（{@code PUT /api/cases/{id}}）。
     *
     * @param id  用例主键
     * @param req 编辑入参
     * @return 更新后的详情（含本次编辑的台账行）
     * @throws BizException 参数非法 40001 / 用例不存在 40401
     */
    public TestCaseVO update(Long id, CaseUpdateReq req) {
        CaseReview review = caseReviewService.edit(id, req);
        log.info("[CASE] 编辑接口完成 caseId={} reviewId={}", id, review.getId());
        return get(id);
    }

    /**
     * 单条评审（{@code POST /api/cases/{id}/review}，采纳或驳回）。
     *
     * @param id  用例主键
     * @param req 评审入参
     * @return 评审后的详情（含本次台账行）
     * @throws BizException 缺动作 40002 / 动作非法 40001 / 缺驳回原因 40002 /
     *                      驳回原因非法 40001 / 用例不存在 40401
     */
    public TestCaseVO review(Long id, ReviewReq req) {
        CaseReview review = caseReviewService.apply(id, req);
        log.info("[CASE] 评审接口完成 caseId={} action={} reviewId={}",
                id, review.getAction(), review.getId());
        return get(id);
    }

    /**
     * 批量评审（{@code POST /api/cases/batch-review}）。
     *
     * @param req 批量入参
     * @return {@code {updated, skipped}}
     * @throws BizException 见 {@link CaseReviewService#batchReview}
     */
    public BatchReviewResult batchReview(BatchReviewReq req) {
        return caseReviewService.batchReview(req);
    }

    /**
     * 排序（白名单列，未登记的回落到创建时间）。
     *
     * <p>与 {@code TestPointService#applySort} 同一套写法：只允许白名单列，
     * 末尾补一个稳定主键排序，避免同时间戳下翻页抖动。</p>
     *
     * @param wrapper 查询条件
     * @param query   分页参数
     */
    private void applySort(LambdaQueryWrapper<TestCase> wrapper, PageQuery query) {
        String column = query.sortColumnOrDefault("created_at");
        boolean desc = query.isDesc();
        switch (column) {
            case "updated_at" -> wrapper.orderBy(true, desc, TestCase::getUpdatedAt);
            case "title" -> wrapper.orderBy(true, desc, TestCase::getTitle);
            case "priority" -> wrapper.orderBy(true, desc, TestCase::getPriority);
            case "status" -> wrapper.orderBy(true, desc, TestCase::getStatus);
            case "seq_no" -> wrapper.orderBy(true, desc, TestCase::getSeqNo);
            default -> wrapper.orderBy(true, desc, TestCase::getCreatedAt);
        }
        wrapper.orderByDesc(TestCase::getId);
    }

    /**
     * 按主键取实体。
     *
     * @param id 主键
     * @return 实体
     * @throws BizException 不存在 40401
     */
    private TestCase require(Long id) {
        TestCase entity = id == null ? null : testCaseMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("用例");
        }
        return entity;
    }

    /**
     * 校验编码取值在枚举内。
     *
     * @param value 待校验值
     * @param type  枚举类型
     * @param label 中文名（异常文案用）
     * @return 规范化后的码
     * @throws BizException 取值不在枚举内 40001
     */
    private String requireEnum(String value, Class<? extends CodedEnum> type, String label) {
        for (CodedEnum item : type.getEnumConstants()) {
            if (item.code().equals(value)) {
                return item.code();
            }
        }
        String allowed = Arrays.stream(type.getEnumConstants())
                .map(CodedEnum::code)
                .collect(Collectors.joining("/"));
        throw new BizException(ErrorCode.E_PARAM_INVALID, label + "取值不合法，可选值：" + allowed);
    }
}
