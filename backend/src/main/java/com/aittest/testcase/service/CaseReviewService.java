package com.aittest.testcase.service;

import com.aittest.auth.AuthContext;
import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.JsonUtils;
import com.aittest.common.enums.CasePriority;
import com.aittest.common.enums.CaseStatus;
import com.aittest.common.enums.CaseType;
import com.aittest.common.enums.CodedEnum;
import com.aittest.system.domain.RejectReason;
import com.aittest.system.mapper.RejectReasonMapper;
import com.aittest.testcase.domain.CaseReview;
import com.aittest.testcase.domain.ReviewAction;
import com.aittest.testcase.domain.TestCase;
import com.aittest.testcase.dto.BatchReviewReq;
import com.aittest.testcase.dto.BatchReviewResult;
import com.aittest.testcase.dto.CaseUpdateReq;
import com.aittest.testcase.dto.ReviewReq;
import com.aittest.testcase.dto.TestCaseVO;
import com.aittest.testcase.mapper.CaseReviewMapper;
import com.aittest.testcase.mapper.TestCaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用例评审的写入侧：<b>采纳 / 驳回 / 编辑</b>三个分支 + 批量评审（验收①~⑤）。
 *
 * <p><b>这个类只有一件事：把"人的一个动作"落成两行写入。</b>
 * 一行 {@code case_review}（台账，只追加）+ 一次 {@code test_case.status} 更新（投影）。
 * 台账是标注数据的唯一真源，status 只是它最后一次动作的缓存。</p>
 *
 * <h2>四条不可破坏的约束</h2>
 * <ol>
 *   <li><b>台账只 INSERT</b>：改主意不是 UPDATE，而是再插一行。台账被改写等于把
 *       "这批提示词产出的用例质量差"的证据洗白，事后永远查不出来。</li>
 *   <li><b>INSERT 与 UPDATE 必须同一事务</b>：半成功会留下"台账说有这次评审、
 *       用例状态却没变"的不一致，而这种状态没有任何日志能定位——必须整体回滚。</li>
 *   <li><b>先 {@code SELECT ... FOR UPDATE} 锁用例行</b>：两个评审人同时点"编辑"时，
 *       各自基于同一份旧内容写快照，两份快照"看起来都对"，但后写的那份把先写的
 *       内容静默覆盖了。锁行把并发评审串行化，old_content 才可信。</li>
 *   <li><b>驳回原因必须<b>先查字典再 INSERT</b></b>：{@code case_review.reject_reason_code}
 *       上有真实外键（{@code fk_case_review_reject_reason}）。传一个字典里没有的码，
 *       MySQL 会在 INSERT 时抛外键约束异常，被 {@code GlobalExceptionHandler} 兜成
 *       {@code 50000 系统内部错误}——用户看到"服务器开小差了"，日志里是
 *       {@code Cannot add or update a child row}。<b>应用层必须先校验</b>，
 *       把它翻译成 {@code 40001 参数校验失败}。</li>
 * </ol>
 *
 * <h2>外键只校验"存在"，不校验"启用"</h2>
 * <p>{@code enabled=0} 的字典项外键照样放行，数据库不会拦。所以"选了已停用的项"
 * 只能靠应用层读 {@code enabled} 判断。这正是驳回缺码与码非法要分成两个错的原因：</p>
 * <ul>
 *   <li><b>没传码</b> → {@code 40002 必填缺失}（"忘了选"）；</li>
 *   <li><b>传了不存在 / 已停用的码</b> → {@code 40001 参数校验失败}（"选了一个用不了的项"）。</li>
 * </ul>
 * <p>混成一个码，前端就没法区分"表单没填"和"字典过期了"，两种情况该做的补救完全不同。
 * 校验顺序固定为"先判空短路、再一次 {@code selectById} 同时判存在与启用"，
 * 全程只查一次库——没传码的情况连库都不摸。</p>
 *
 * <h2>为什么不能拿 UPDATE 的 affected rows 判断用例是否存在</h2>
 * <p>MySQL 在"新值与旧值完全相同"时返回 0 行受影响。同一个用例<b>再次采纳</b>
 * （改主意后又改回来，或者刷新后重试）时 {@code status} 从 {@code ACCEPTED} 写回
 * {@code ACCEPTED}，affected rows 就是 0。若拿它当"用例不存在"，这类完全正常的操作
 * 会报 404。存在性只由前面的 {@code FOR UPDATE} 确认。</p>
 *
 * @author 寇豆码
 */
@Service
public class CaseReviewService {

    private static final Logger log = LoggerFactory.getLogger(CaseReviewService.class);

    /** 用例 Mapper。 */
    private final TestCaseMapper testCaseMapper;

    /** 评审台账 Mapper（只 INSERT，见类注释）。 */
    private final CaseReviewMapper caseReviewMapper;

    /** 驳回原因字典 Mapper（只读引用，不复制实体，D-15）。 */
    private final RejectReasonMapper rejectReasonMapper;

    public CaseReviewService(TestCaseMapper testCaseMapper,
                             CaseReviewMapper caseReviewMapper,
                             RejectReasonMapper rejectReasonMapper) {
        this.testCaseMapper = testCaseMapper;
        this.caseReviewMapper = caseReviewMapper;
        this.rejectReasonMapper = rejectReasonMapper;
    }

    /**
     * 单条评审：<b>采纳</b>或<b>驳回</b>（验收①②⑤）。
     *
     * <p>不接受 {@code EDIT}：评审请求里没有内容字段，凭空写一条"编辑"只会得到
     * {@code old_content == new_content} 的快照，那种台账行对 diff 毫无价值，
     * 却会被"编辑次数"指标当真。编辑走 {@code PUT /api/cases/{id}} → {@link #edit}。</p>
     *
     * <p><b>不限制当前状态</b>：允许改主意重复评审（验收⑤），历史全部留在台账里，
     * {@code test_case.status} 取最后一次动作。</p>
     *
     * @param caseId 用例主键
     * @param req    评审入参
     * @return 刚落库的台账行（含快照与评审人）
     * @throws BizException 缺动作 40002 / 动作非法 40001 / 缺驳回原因 40002 /
     *                      驳回原因非法 40001 / 用例不存在 40401
     */
    @Transactional(rollbackFor = Exception.class)
    public CaseReview apply(Long caseId, ReviewReq req) {
        ReviewAction action = ReviewAction.of(req == null ? null : req.action());
        if (action == ReviewAction.EDIT) {
            throw new BizException(ErrorCode.E_PARAM_INVALID,
                    "评审动作只接受 ACCEPT / REJECT：编辑请调用 PUT /api/cases/" + caseId);
        }

        // 1) 锁行：并发评审串行化，后到的那次能看到先到的那次留下的 status
        TestCase current = lockCase(caseId);

        // 2) 字典校验（仅 REJECT）；ACCEPT 传了码一律忽略并落 NULL
        String reasonCode = action == ReviewAction.REJECT
                ? requireRejectReason(req.rejectReasonCode())
                : ignoreReasonCode(req.rejectReasonCode(), caseId);

        // 3) 台账 INSERT（只追加）
        CaseReview review = newLedgerRow(current.getId(), action, reasonCode,
                normalizeReasonText(req.reasonText()), null, null);
        caseReviewMapper.insert(review);

        // 4) 状态 UPDATE：affected rows 不参与存在性判断（见类注释）
        updateStatusOnly(current.getId(), action.targetStatus());

        log.info("[CASE] 评审落库 caseId={} action={} reasonCode={} reviewer={} 状态改为 {}",
                current.getId(), action.code(), reasonCode, review.getReviewerId(),
                action.targetStatus().code());
        return review;
    }

    /**
     * 编辑后采纳（验收③⑤）：一个事务里写快照、改内容、置 {@code EDITED}。
     *
     * <p><b>两点快照必须可 diff</b>：{@code old_content} 是更新<b>前</b>从库里读出来的实体，
     * {@code new_content} 是同一实体应用改动<b>后</b>的状态，两者都用 {@link TestCaseVO}
     * 经<b>同一个</b> {@link JsonUtils#mapper()} 序列化——同一类型、同一字段顺序，
     * 逐字段比对才有意义。改用两个不同的 DTO 或两次不同的配置，diff 会退化成"整段都变了"。</p>
     *
     * <p><b>快照里唯一的非内容差异是 {@code status}</b>（旧的 {@code DRAFT}、新的 {@code EDITED}）。
     * 这不是噪声，它正好说明这份快照来自一次编辑；要把它抹平反而是在说谎。</p>
     *
     * <p><b>没有任何改动时拒绝</b>：{@code old_content == new_content} 的台账行会被
     * "编辑幅度"指标当成一次真实编辑，而它什么都没改。这种情况让用户走采纳即可。</p>
     *
     * @param caseId 用例主键
     * @param req    编辑入参
     * @return 刚落库的台账行（含前后快照）
     * @throws BizException 参数非法 40001 / 用例不存在 40401
     */
    @Transactional(rollbackFor = Exception.class)
    public CaseReview edit(Long caseId, CaseUpdateReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.E_REQUIRED_MISSING, "缺少必填参数：编辑内容");
        }
        TestCase current = lockCase(caseId);

        String oldContent = JsonUtils.toJson(TestCaseVO.from(current));
        applyContent(current, req);
        current.setStatus(CaseStatus.EDITED.code());
        String newContent = JsonUtils.toJson(TestCaseVO.from(current));

        if (oldContent.equals(newContent)) {
            log.warn("[CASE] 编辑请求没有任何改动，已拒绝 caseId={}", current.getId());
            throw new BizException(ErrorCode.E_PARAM_INVALID,
                    "没有任何字段发生变化，无需保存；若原文通过请直接采纳");
        }

        CaseReview review = newLedgerRow(current.getId(), ReviewAction.EDIT, null, "", oldContent, newContent);
        caseReviewMapper.insert(review);

        // 只写"内容 + 状态"这几列：拿整份实体 updateById 会把 created_at 与 updated_at
        // 一起写回旧值，显式赋值后 ON UPDATE CURRENT_TIMESTAMP(3) 不再触发，
        // updated_at 就永远停在旧时间上（"这条被编辑过"在库里看不出来）。
        int rows = testCaseMapper.updateById(contentPatchOf(current));
        if (rows == 0) {
            // 存在性已由 FOR UPDATE 确认，且此处内容必然变化过，0 行说明行被并发删除了
            throw BizException.notFound("用例");
        }

        log.info("[CASE] 编辑落库 caseId={} reviewer={} 标题改动={} 快照长度={}/{}",
                current.getId(), review.getReviewerId(), req.title() != null,
                oldContent.length(), newContent.length());
        return review;
    }

    /**
     * 批量评审（验收④）：<b>只处理 {@code DRAFT}</b>，其余的跳过并给出原因。
     *
     * <p><b>为什么不做无条件覆盖</b>：批量按钮一次点几十条，其中难免混着刚评过的。
     * 无条件覆盖会把已有结论重新刷一遍（包括把"驳回"刷成"采纳"），而台账里
     * 只会多出一行看不出前因后果的记录。跳过并回显原因，"我选的 20 条为什么只成功 17 条"
     * 才有答案。</p>
     *
     * <p><b>为什么这里不 {@code FOR UPDATE}</b>：批量是一个事务里的 N 个用例，
     * 逐个加行锁会持有 N 把锁直到提交，两批并发（顺序不同）就有死锁风险。
     * 这里改用<b>带条件更新</b>做乐观判定：{@code UPDATE ... WHERE id=? AND status='DRAFT'}，
     * 并发下只有一个人能把它从 {@code DRAFT} 改走，输的一方 affected rows = 0。</p>
     *
     * <p><b>因此本方法的写入顺序是 UPDATE 在前、INSERT 在后</b>（与单条评审相反）。
     * 原因很具体：条件更新是"抢锁"动作，抢输了就不能写台账——
     * 先 INSERT 再 UPDATE 的话，抢输的那条会留下一行"评审了但状态没变"的孤儿台账，
     * 而它还在同一个事务里，会被一起提交。</p>
     *
     * @param req 批量入参
     * @return {@code {updated, skipped}}；{@code skipped} 带可读中文原因
     * @throws BizException 缺动作 40002 / 动作非法 40001 / 批量不支持 EDIT 40001 /
     *                      未选用例 40002 / 缺驳回原因 40002 / 驳回原因非法 40001
     */
    @Transactional(rollbackFor = Exception.class)
    public BatchReviewResult batchReview(BatchReviewReq req) {
        ReviewAction action = ReviewAction.of(req == null ? null : req.action());
        if (action == ReviewAction.EDIT) {
            throw new BizException(ErrorCode.E_PARAM_INVALID,
                    "批量评审只支持 ACCEPT / REJECT：编辑必须逐条进行（每条的内容不同）");
        }
        List<Long> caseIds = distinctIds(req.ids());
        if (caseIds.isEmpty()) {
            throw new BizException(ErrorCode.E_REQUIRED_MISSING, "缺少必填参数：ids（没有选中任何用例）");
        }

        // 字典校验放在循环外：一次校验对整批生效，否则 30 条用例会把同一行读 30 次
        String reasonCode = action == ReviewAction.REJECT ? requireRejectReason(req.rejectReasonCode()) : null;
        String reasonText = normalizeReasonText(req.reasonText());

        int updated = 0;
        List<BatchReviewResult.Skipped> skipped = new ArrayList<>();
        for (Long caseId : caseIds) {
            TestCase current = testCaseMapper.selectById(caseId);
            if (current == null) {
                skipped.add(new BatchReviewResult.Skipped(caseId, "用例不存在"));
                continue;
            }
            if (!CaseStatus.DRAFT.code().equals(current.getStatus())) {
                skipped.add(new BatchReviewResult.Skipped(caseId,
                        "已评审（当前状态 " + current.getStatus() + "）"));
                continue;
            }
            // 条件更新 = 乐观抢锁：只有仍处于 DRAFT 的那一次能改成功
            int rows = testCaseMapper.update(statusPatchOf(caseId, action.targetStatus()), draftGuard(caseId));
            if (rows == 0) {
                skipped.add(new BatchReviewResult.Skipped(caseId, "已被其他评审人抢先处理，请刷新后重试"));
                continue;
            }
            caseReviewMapper.insert(newLedgerRow(caseId, action, reasonCode, reasonText, null, null));
            updated++;
        }

        log.info("[CASE] 批量评审 action={} reasonCode={} 请求={} 成功={} 跳过={}",
                action.code(), reasonCode, caseIds.size(), updated, skipped.size());
        return BatchReviewResult.of(updated, skipped);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 锁住用例行并返回当前快照。
     *
     * <p>{@code FOR UPDATE} 是这里唯一的目的：拿 {@code old_content} 的那一刻起，
     * 这一行就不能再被别人改，直到本事务提交。</p>
     *
     * @param caseId 用例主键
     * @return 当前用事实体
     * @throws BizException 参数缺失 40001 / 用例不存在 40401
     */
    private TestCase lockCase(Long caseId) {
        if (caseId == null) {
            throw new BizException(ErrorCode.E_PARAM_INVALID, "缺少必填参数：用例 ID");
        }
        TestCase entity = testCaseMapper.selectOne(new LambdaQueryWrapper<TestCase>()
                .eq(TestCase::getId, caseId)
                .last("FOR UPDATE"));
        if (entity == null) {
            throw BizException.notFound("用例");
        }
        return entity;
    }

    /**
     * 只更新状态列（不碰内容、不碰审计列）。
     *
     * @param caseId 用例主键
     * @param target 目标状态
     */
    private void updateStatusOnly(Long caseId, CaseStatus target) {
        // 刻意不判断返回值：MySQL 在"值未变化"时返回 0 行，而"再次采纳"是完全合法的操作
        testCaseMapper.updateById(statusPatchOf(caseId, target));
    }

    /**
     * 构造只含 {@code id + status} 的补丁实体。
     *
     * <p>用补丁而不是整份实体，是为了让 SQL 只出现这两列：整份实体写回会带上
     * {@code updated_at} 的旧值，把 {@code ON UPDATE CURRENT_TIMESTAMP(3)} 按死。</p>
     *
     * @param caseId 用例主键
     * @param target 目标状态
     * @return 补丁实体
     */
    private TestCase statusPatchOf(Long caseId, CaseStatus target) {
        TestCase patch = new TestCase();
        patch.setId(caseId);
        patch.setStatus(target.code());
        return patch;
    }

    /**
     * 批量评审的条件更新条件：{@code id = ? AND status = 'DRAFT'}。
     *
     * @param caseId 用例主键
     * @return 更新条件
     */
    private LambdaUpdateWrapper<TestCase> draftGuard(Long caseId) {
        return new LambdaUpdateWrapper<TestCase>()
                .eq(TestCase::getId, caseId)
                .eq(TestCase::getStatus, CaseStatus.DRAFT.code());
    }

    /**
     * 构造"内容 + 状态"补丁（编辑用）。
     *
     * <p>写全部六个可编辑列，而不是只写用户改过的那几列：值没变的列写回同值不产生副作用，
     * 而"按某个字段是否变化来拼 SQL"会让每条改动走一条不同的 UPDATE 语句，
     * 出问题时无法从一条 SQL 看出改了什么。<b>写全量、比全量</b>更可预测。</p>
     *
     * @param merged 已应用改动并置好状态的实体
     * @return 补丁实体
     */
    private TestCase contentPatchOf(TestCase merged) {
        TestCase patch = new TestCase();
        patch.setId(merged.getId());
        patch.setTitle(merged.getTitle());
        patch.setPrecondition(merged.getPrecondition());
        patch.setSteps(merged.getSteps());
        patch.setExpected(merged.getExpected());
        patch.setPriority(merged.getPriority());
        patch.setCaseType(merged.getCaseType());
        patch.setStatus(merged.getStatus());
        return patch;
    }

    /**
     * 校验并把 {@link CaseUpdateReq} 的改动应用到实体上（就地修改）。
     *
     * <p>{@code null} 表示"这一项不动"，空串表示"清空"。{@code title} / {@code expected}
     * 清空会让用例不可用，因此按"给了就必须非空"处理；{@code precondition} 允许空串
     * （生成 schema 里它没有 {@code minLength}）。</p>
     *
     * @param target 待修改实体
     * @param req    编辑入参
     * @throws BizException 参数非法 40001
     */
    private void applyContent(TestCase target, CaseUpdateReq req) {
        if (req.title() != null) {
            target.setTitle(requireNonBlank(req.title(), "标题"));
        }
        if (req.precondition() != null) {
            target.setPrecondition(req.precondition());
        }
        if (req.expected() != null) {
            target.setExpected(requireNonBlank(req.expected(), "预期结果"));
        }
        if (req.priority() != null) {
            target.setPriority(requireEnum(req.priority().trim(), CasePriority.class, "优先级"));
        }
        if (req.caseType() != null) {
            target.setCaseType(requireEnum(req.caseType().trim(), CaseType.class, "用例类型"));
        }
        if (req.steps() != null) {
            target.setSteps(serializeSteps(req.steps()));
        }
    }

    /**
     * 校验步骤并序列化成库里的契约格式。
     *
     * <p>输出形如 {@code [{"no":1,"action":"...","data":"..."}]}（DDL 注释的原话，
     * 是<b>数组</b>不是对象）。序号由服务端按数组顺序重排 1..N：让客户端传序号会引入
     * "跳号 / 重号 / 顺序与数组不一致"三种脏数据，而序号唯一的作用是"失败在第几步"。</p>
     *
     * @param steps 客户端步骤（可含 null 元素、空动作）
     * @return JSON 数组字符串
     * @throws BizException 步骤为空或某一步缺少动作 40001
     */
    private String serializeSteps(List<CaseUpdateReq.Step> steps) {
        if (steps.isEmpty()) {
            throw new BizException(ErrorCode.E_PARAM_INVALID, "步骤不能为空：至少要保留一条可执行步骤");
        }
        List<TestCaseVO.Step> normalized = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            CaseUpdateReq.Step raw = steps.get(i);
            int no = i + 1;
            if (raw == null) {
                throw new BizException(ErrorCode.E_PARAM_INVALID, "第 " + no + " 步是空对象");
            }
            String action = raw.action() == null ? "" : raw.action().trim();
            if (action.isEmpty()) {
                throw new BizException(ErrorCode.E_PARAM_INVALID, "第 " + no + " 步缺少动作描述");
            }
            normalized.add(new TestCaseVO.Step(no, action, raw.data() == null ? "" : raw.data()));
        }
        return JsonUtils.toJson(normalized);
    }

    /**
     * 校验驳回原因字典码：<b>一次查询同时判存在与启用</b>。
     *
     * <p>顺序很重要：缺码在进 Mapper 之前就短路成 {@code 40002}，
     * 不为"没传"白查一次库。</p>
     *
     * @param rawCode 原始码
     * @return 规范化后的码（已 trim）
     * @throws BizException 缺码 40002 / 码不存在或已停用 40001
     */
    private String requireRejectReason(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new BizException(ErrorCode.E_REQUIRED_MISSING, "驳回必须选择原因（rejectReasonCode）");
        }
        String code = rawCode.trim();
        RejectReason reason = rejectReasonMapper.selectById(code);
        if (reason == null) {
            throw new BizException(ErrorCode.E_PARAM_INVALID, "驳回原因不存在：" + code);
        }
        if (!Boolean.TRUE.equals(reason.getEnabled())) {
            throw new BizException(ErrorCode.E_PARAM_INVALID, "驳回原因已停用：" + code);
        }
        return code;
    }

    /**
     * 非驳回动作传了原因码：<b>忽略并落 NULL</b>，不报错。
     *
     * <p>前端复用同一张表单，从"驳回"切回"采纳"时码会残留在表单里。报错只会让人困惑，
     * 而落库又会污染"驳回原因分布"——那张饼图的分母正是"REJECT 且 code 非空"的行数。</p>
     *
     * @param rawCode 原始码（可能为 null）
     * @param caseId  用例主键（仅用于日志定位）
     * @return 恒为 {@code null}
     */
    private String ignoreReasonCode(String rawCode, Long caseId) {
        if (rawCode != null && !rawCode.isBlank()) {
            log.debug("[CASE] 非驳回动作附带了原因码，已忽略 caseId={} code={}", caseId, rawCode);
        }
        return null;
    }

    /**
     * 组装一条台账行（只构造，不落库）。
     *
     * @param caseId           用例主键
     * @param action           动作
     * @param rejectReasonCode 驳回原因码（非 REJECT 传 {@code null}）
     * @param reasonText       自由文本（null 归一成空串，列是 NOT NULL）
     * @param oldContent       编辑前快照（非 EDIT 传 {@code null}）
     * @param newContent       编辑后快照（非 EDIT 传 {@code null}）
     * @return 台账实体
     */
    private CaseReview newLedgerRow(Long caseId, ReviewAction action, String rejectReasonCode,
                                    String reasonText, String oldContent, String newContent) {
        CaseReview review = new CaseReview();
        review.setCaseId(caseId);
        review.setAction(action.code());
        review.setRejectReasonCode(rejectReasonCode);
        review.setReasonText(reasonText);
        review.setOldContent(oldContent);
        review.setNewContent(newContent);
        review.setReviewerId(currentUserIdOrNull());
        return review;
    }

    /**
     * 取当前登录用户（未登录返回 {@code null}）。
     *
     * <p>与 {@code GenTaskService#currentUserIdOrNull()} 同一写法：{@code reviewer_id}
     * 可空且外键是 {@code ON DELETE SET NULL}，"谁评的"缺失不该拦住评审本身。
     * 缺人时日志里能看到（{@code reviewer=null}），但不影响台账与指标的可用性。</p>
     *
     * @return 用户 ID 或 {@code null}
     */
    private Long currentUserIdOrNull() {
        try {
            return AuthContext.requireUserId();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * 归一自由文本：{@code null} → 空串，并去掉首尾空白。
     *
     * <p>列是 {@code NOT NULL DEFAULT ''}，落 {@code null} 会直接抛约束异常；
     * 去空白是因为"只敲了空格"的说明与没写说明是同一件事。</p>
     *
     * @param raw 原始文本
     * @return 归一后的文本（永不 {@code null}）
     */
    private String normalizeReasonText(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /**
     * 去重并过滤 {@code null} 的用例 ID（保持请求顺序）。
     *
     * <p>同一批里出现重复 ID 时只处理一次：重复处理会产生两行台账（第二次因为
     * status 已不是 DRAFT 而变成 skipped），让"跳过原因"里出现一条莫名其妙的记录。</p>
     *
     * @param ids 原始 ID 列表（可为 {@code null}）
     * @return 去重后的 ID 列表（永不为 {@code null}）
     */
    private List<Long> distinctIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }

    /**
     * 非空校验（{@code null} 表示不改，这里只处理"给了但给了空"）。
     *
     * @param value 待校验值
     * @param label 中文名（异常文案用）
     * @return trim 后的值
     * @throws BizException 空白 40001
     */
    private String requireNonBlank(String value, String label) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new BizException(ErrorCode.E_PARAM_INVALID,
                    label + "不能为空：不修改请传 null，传空串等于把这一项清空");
        }
        return trimmed;
    }

    /**
     * 校验编码取值在枚举内，并返回规范化后的码。
     *
     * <p>把允许值一次列全：只说"优先级不合法"，用户要猜四次才知道有哪些可选。</p>
     *
     * @param value 待校验值（已 trim）
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
