package com.aittest.testcase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.JsonUtils;
import com.aittest.generation.support.MybatisPlusLambdaCache;
import com.aittest.system.domain.RejectReason;
import com.aittest.system.mapper.RejectReasonMapper;
import com.aittest.testcase.domain.CaseReview;
import com.aittest.testcase.domain.TestCase;
import com.aittest.testcase.dto.BatchReviewReq;
import com.aittest.testcase.dto.BatchReviewResult;
import com.aittest.testcase.dto.CaseUpdateReq;
import com.aittest.testcase.dto.ReviewReq;
import com.aittest.testcase.mapper.CaseReviewMapper;
import com.aittest.testcase.mapper.TestCaseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评审写入侧的契约测试（验收①~⑤）。
 *
 * <p><b>为什么用 mock 而不是连库</b>：被测的是<b>决策与写入顺序</b>——"这个动作该写哪一行、
 * 状态改成什么、驳回码何时校验、哪一步先执行"。连库会把外键、事务、MySQL 的
 * affected-rows 语义一起卷进来，而那些恰恰是本类<b>刻意绕开</b>的东西（见
 * {@link CaseReviewService} 类注释）。这里断言"服务端发出了什么写操作、以什么顺序"，
 * 覆盖更准也更快。</p>
 *
 * <p><b>本类要挡住的具体事故</b>（都不会抛异常，只会让台账与状态悄悄不一致）：</p>
 * <ul>
 *   <li>驳回没选原因却照样写了两行 → 台账里出现无法归因的驳回，"驳回原因分布"从此失真；</li>
 *   <li>传了字典里没有的码 → 外键在 INSERT 时炸成 {@code 50000}，用户看到"服务器开小差"；</li>
 *   <li>编辑快照用两个不同的序列化器 → diff 出来整段都变了，编辑幅度指标失真；</li>
 *   <li>先改状态后写台账（或其中一个不在事务里） → "台账说有评审、用例状态没变"；</li>
 *   <li>批量评审无条件覆盖 → 把刚评过的结论重刷一遍。</li>
 * </ul>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class CaseReviewServiceTest {

    /** 用例主键。 */
    private static final Long CASE_ID = 7_000_001L;

    /** 驳回原因字典码（V2 种子数据里存在且 enabled=1）。 */
    private static final String REASON_CODE = "EXPECT_UNCLEAR";

    @Mock
    private TestCaseMapper testCaseMapper;

    @Mock
    private CaseReviewMapper caseReviewMapper;

    @Mock
    private RejectReasonMapper rejectReasonMapper;

    private CaseReviewService service;

    /**
     * 安装 MyBatis-Plus 的 lambda 列缓存（{@code TestCase} / {@code CaseReview}）。
     *
     * <p>纯 Mockito 不启 Spring 上下文，{@code LambdaQueryWrapper} 解析列名时缓存是空的。
     * 见 {@link MybatisPlusLambdaCache} 类注释。</p>
     */
    @BeforeAll
    static void installLambdaCache() {
        MybatisPlusLambdaCache.install();
    }

    @BeforeEach
    void setUp() {
        service = new CaseReviewService(testCaseMapper, caseReviewMapper, rejectReasonMapper);
    }

    // ------------------------------------------------------------------
    // 验收①：三种动作都落 case_review 并同步 test_case.status
    // ------------------------------------------------------------------

    /**
     * 采纳：台账一行（无原因码、无快照），状态 {@code ACCEPTED}。
     */
    @Test
    @DisplayName("采纳：落一行台账 + 状态改 ACCEPTED，且只写 status 这一列")
    void acceptWritesLedgerRowAndFlipsStatusToAccepted() {
        when(testCaseMapper.selectOne(any())).thenReturn(caseRow(CASE_ID, "DRAFT"));

        service.apply(CASE_ID, new ReviewReq("ACCEPT", null, "  原文没问题  "));

        ArgumentCaptor<CaseReview> ledger = ArgumentCaptor.forClass(CaseReview.class);
        verify(caseReviewMapper).insert(ledger.capture());
        CaseReview saved = ledger.getValue();
        assertThat(saved.getCaseId()).isEqualTo(CASE_ID);
        assertThat(saved.getAction()).isEqualTo("ACCEPT");
        assertThat(saved.getRejectReasonCode()).as("采纳不该带驳回原因").isNull();
        assertThat(saved.getReasonText()).as("自由文本应去掉首尾空白").isEqualTo("原文没问题");
        assertThat(saved.getOldContent()).as("非 EDIT 不写快照").isNull();
        assertThat(saved.getNewContent()).isNull();
        assertThat(saved.getReviewerId()).as("单测没有登录上下文").isNull();

        ArgumentCaptor<TestCase> patch = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseMapper).updateById(patch.capture());
        assertThat(patch.getValue().getId()).isEqualTo(CASE_ID);
        assertThat(patch.getValue().getStatus()).isEqualTo("ACCEPTED");
        // 补丁实体只带 id + status：带上 updated_at 会把 ON UPDATE 按死（见类注释）
        assertThat(patch.getValue().getTitle()).as("状态补丁不该碰内容列").isNull();
        assertThat(patch.getValue().getUpdatedAt()).isNull();
    }

    /**
     * 驳回：台账一行（带原因码），状态 {@code REJECTED}。
     */
    @Test
    @DisplayName("驳回：落一行带原因码的台账 + 状态改 REJECTED")
    void rejectWritesLedgerRowWithReasonCodeAndFlipsStatusToRejected() {
        when(testCaseMapper.selectOne(any())).thenReturn(caseRow(CASE_ID, "DRAFT"));
        when(rejectReasonMapper.selectById(REASON_CODE)).thenReturn(reason(REASON_CODE, true));

        service.apply(CASE_ID, new ReviewReq("REJECT", " " + REASON_CODE + " ", "预期没写清楚"));

        ArgumentCaptor<CaseReview> ledger = ArgumentCaptor.forClass(CaseReview.class);
        verify(caseReviewMapper).insert(ledger.capture());
        assertThat(ledger.getValue().getAction()).isEqualTo("REJECT");
        assertThat(ledger.getValue().getRejectReasonCode()).as("码要 trim 后落库").isEqualTo(REASON_CODE);
        assertThat(ledger.getValue().getReasonText()).isEqualTo("预期没写清楚");

        ArgumentCaptor<TestCase> patch = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseMapper).updateById(patch.capture());
        assertThat(patch.getValue().getStatus()).isEqualTo("REJECTED");
        // 字典校验只查一次库（同时判存在与启用），不是查两次
        verify(rejectReasonMapper, times(1)).selectById(REASON_CODE);
    }

    /**
     * 动作码大小写不敏感：前端传小写也要认。
     */
    @Test
    @DisplayName("动作码大小写不敏感：accept 等同 ACCEPT")
    void actionCodeIsCaseInsensitive() {
        when(testCaseMapper.selectOne(any())).thenReturn(caseRow(CASE_ID, "DRAFT"));

        service.apply(CASE_ID, new ReviewReq("accept", null, null));

        ArgumentCaptor<CaseReview> ledger = ArgumentCaptor.forClass(CaseReview.class);
        verify(caseReviewMapper).insert(ledger.capture());
        assertThat(ledger.getValue().getAction()).isEqualTo("ACCEPT");
        assertThat(ledger.getValue().getReasonText()).as("null 说明归一成空串").isEmpty();
    }

    // ------------------------------------------------------------------
    // 验收②：驳回必须选字典原因码（40002 / 40001 分离）
    // ------------------------------------------------------------------

    /**
     * 没传原因码 → {@code 40002}，且<b>连库都不摸</b>。
     */
    @Test
    @DisplayName("驳回缺码：40002 必填缺失，且不查库、不写台账")
    void rejectWithoutReasonCodeFailsWith40002AndTouchesNothing() {
        when(testCaseMapper.selectOne(any())).thenReturn(caseRow(CASE_ID, "DRAFT"));

        BizException ex = catchThrowableOfType(
                () -> service.apply(CASE_ID, new ReviewReq("REJECT", "   ", null)), BizException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_REQUIRED_MISSING);
        assertThat(ex.getErrorCode().getCode()).as("前端按 40002 高亮'忘了选原因'").isEqualTo(40002);
        assertThat(ex.getMessage()).contains("rejectReasonCode");
        verifyNoInteractions(rejectReasonMapper);
        verify(caseReviewMapper, never()).insert(any(CaseReview.class));
        verify(testCaseMapper, never()).updateById(any(TestCase.class));
    }

    /**
     * 传了字典里没有的码 → {@code 40001}（不是 40002，也不是 50000）。
     *
     * <p><b>这条是黑盒能看见的护栏</b>：{@code reject_reason_code} 有真实外键，
     * 不在应用层校验就会在 INSERT 时炸成"系统内部错误"。这里断言
     * "查不到 → 不INSERT → 抛 40001"。</p>
     */
    @Test
    @DisplayName("驳回码不存在：40001 参数校验失败，绝不 INSERT（否则会炸成 50000）")
    void rejectWithUnknownReasonCodeFailsWith40001() {
        when(testCaseMapper.selectOne(any())).thenReturn(caseRow(CASE_ID, "DRAFT"));
        when(rejectReasonMapper.selectById("NOT_EXIST")).thenReturn(null);

        BizException ex = catchThrowableOfType(
                () -> service.apply(CASE_ID, new ReviewReq("REJECT", "NOT_EXIST", null)), BizException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_PARAM_INVALID);
        assertThat(ex.getErrorCode().getCode()).isEqualTo(40001);
        assertThat(ex.getMessage()).contains("NOT_EXIST");
        verify(caseReviewMapper, never()).insert(any(CaseReview.class));
        verify(testCaseMapper, never()).updateById(any(TestCase.class));
    }

    /**
     * 传了已停用的码 → {@code 40001}。
     *
     * <p>外键只校验"存在"，不校验 {@code enabled}，数据库不会拦——只能靠应用层。</p>
     */
    @Test
    @DisplayName("驳回码已停用：40001（外键放行，只能应用层拦）")
    void rejectWithDisabledReasonCodeFailsWith40001() {
        when(testCaseMapper.selectOne(any())).thenReturn(caseRow(CASE_ID, "DRAFT"));
        when(rejectReasonMapper.selectById(REASON_CODE)).thenReturn(reason(REASON_CODE, false));

        BizException ex = catchThrowableOfType(
                () -> service.apply(CASE_ID, new ReviewReq("REJECT", REASON_CODE, null)), BizException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_PARAM_INVALID);
        assertThat(ex.getMessage()).contains("停用");
        verify(caseReviewMapper, never()).insert(any(CaseReview.class));
    }

    /**
     * 采纳时带了残留的原因码 → 忽略并落 NULL，不报错。
     */
    @Test
    @DisplayName("采纳附带残留原因码：忽略落 NULL，不报错也不查字典")
    void acceptIgnoresLeftoverReasonCode() {
        when(testCaseMapper.selectOne(any())).thenReturn(caseRow(CASE_ID, "DRAFT"));

        service.apply(CASE_ID, new ReviewReq("ACCEPT", REASON_CODE, null));

        ArgumentCaptor<CaseReview> ledger = ArgumentCaptor.forClass(CaseReview.class);
        verify(caseReviewMapper).insert(ledger.capture());
        assertThat(ledger.getValue().getRejectReasonCode())
                .as("落 NULL 才不会污染驳回原因分布的分母")
                .isNull();
        verifyNoInteractions(rejectReasonMapper);
    }

    /**
     * 动作缺失 → {@code 40002}；动作非法 → {@code 40001}。
     */
    @Test
    @DisplayName("动作缺失是 40002，动作非法是 40001（两种成因分开）")
    void missingActionAndBadActionUseDifferentCodes() {
        BizException missing = catchThrowableOfType(
                () -> service.apply(CASE_ID, new ReviewReq(null, null, null)), BizException.class);
        assertThat(missing.getErrorCode()).isEqualTo(ErrorCode.E_REQUIRED_MISSING);
        assertThat(missing.getErrorCode().getCode()).isEqualTo(40002);

        BizException bad = catchThrowableOfType(
                () -> service.apply(CASE_ID, new ReviewReq("APPROVE", null, null)), BizException.class);
        assertThat(bad.getErrorCode()).isEqualTo(ErrorCode.E_PARAM_INVALID);
        assertThat(bad.getErrorCode().getCode()).isEqualTo(40001);
        assertThat(bad.getMessage()).contains("ACCEPT").contains("REJECT");

        verifyNoInteractions(testCaseMapper, caseReviewMapper, rejectReasonMapper);
    }

    /**
     * 评审端点上送 EDIT → {@code 40001}，并明确指向 PUT。
     */
    @Test
    @DisplayName("评审端点不接受 EDIT：40001 且提示改走 PUT")
    void editActionOnReviewEndpointIsRejected() {
        BizException ex = catchThrowableOfType(
                () -> service.apply(CASE_ID, new ReviewReq("EDIT", null, null)), BizException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_PARAM_INVALID);
        assertThat(ex.getMessage()).contains("PUT").contains("ACCEPT / REJECT");
        verifyNoInteractions(testCaseMapper, caseReviewMapper);
    }

    /**
     * 用例不存在 → 40401。
     */
    @Test
    @DisplayName("用例不存在：40401，不写台账")
    void reviewingMissingCaseFailsWithNotFound() {
        when(testCaseMapper.selectOne(any())).thenReturn(null);

        BizException ex = catchThrowableOfType(
                () -> service.apply(CASE_ID, new ReviewReq("ACCEPT", null, null)), BizException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_NOT_FOUND);
        verifyNoInteractions(caseReviewMapper);
    }

    // ------------------------------------------------------------------
    // 验收③：编辑存完整快照，可 diff
    // ------------------------------------------------------------------

    /**
     * 编辑：old/new 两份快照同源可 diff，且每一步的 {@code no} 被重排 1..N。
     */
    @Test
    @DisplayName("编辑：前后快照同序列化器、字段齐全、old≠new、步骤序号重排")
    void editStoresDiffableSnapshots() throws Exception {
        when(testCaseMapper.selectOne(any())).thenReturn(caseRow(CASE_ID, "DRAFT"));
        when(testCaseMapper.updateById(any(TestCase.class))).thenReturn(1);

        CaseUpdateReq req = new CaseUpdateReq("登录成功返回 token", null,
                List.of(new CaseUpdateReq.Step("输入账号", "admin"),
                        new CaseUpdateReq.Step("点击登录", "")),
                "返回 200 且 data.token 非空", "P0", "FUNCTIONAL");

        CaseReview saved = service.edit(CASE_ID, req);

        assertThat(saved.getAction()).isEqualTo("EDIT");
        assertThat(saved.getCaseId()).isEqualTo(CASE_ID);
        assertThat(saved.getRejectReasonCode()).as("编辑不是驳回").isNull();
        assertThat(saved.getReasonText()).as("PUT 没有说明字段").isEmpty();
        assertThat(saved.getOldContent()).isNotNull();
        assertThat(saved.getNewContent()).isNotNull().isNotEqualTo(saved.getOldContent());

        // 两份快照必须是同一个 VO 类型序列化出来的：字段集合一致，才谈得上逐字段 diff
        JsonNode oldNode = JsonUtils.mapper().readTree(saved.getOldContent());
        JsonNode newNode = JsonUtils.mapper().readTree(saved.getNewContent());
        List<String> oldFields = new ArrayList<>();
        oldNode.fieldNames().forEachRemaining(oldFields::add);
        List<String> newFields = new ArrayList<>();
        newNode.fieldNames().forEachRemaining(newFields::add);
        assertThat(newFields)
                .as("新旧快照字段集合必须完全一致，否则 diff 全是噪声")
                .containsExactlyElementsOf(oldFields);

        assertThat(oldNode.get("title").asText()).isEqualTo("旧标题");
        assertThat(newNode.get("title").asText()).isEqualTo("登录成功返回 token");
        assertThat(oldNode.get("status").asText()).isEqualTo("DRAFT");
        assertThat(newNode.get("status").asText())
                .as("快照里唯一的非内容差异是状态（DRAFT → EDITED），它正是这次编辑的痕迹")
                .isEqualTo("EDITED");
        assertThat(newNode.get("steps").isArray()).as("steps 必须是对象数组").isTrue();
        assertThat(newNode.get("steps")).hasSize(2);
        assertThat(newNode.get("steps").get(0).get("no").asInt()).isEqualTo(1);
        assertThat(newNode.get("steps").get(0).get("action").asText()).isEqualTo("输入账号");
        assertThat(newNode.get("steps").get(1).get("no").asInt()).as("序号由服务端重排").isEqualTo(2);
        assertThat(newNode.get("priority").asText()).isEqualTo("P0");

        ArgumentCaptor<TestCase> patch = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseMapper).updateById(patch.capture());
        TestCase written = patch.getValue();
        assertThat(written.getStatus()).isEqualTo("EDITED");
        assertThat(written.getTitle()).isEqualTo("登录成功返回 token");
        assertThat(written.getSteps()).isEqualTo(JsonUtils.toJson(List.of(
                new com.aittest.testcase.dto.TestCaseVO.Step(1, "输入账号", "admin"),
                new com.aittest.testcase.dto.TestCaseVO.Step(2, "点击登录", ""))));
        // 身份与溯源字段一律不改
        assertThat(written.getTaskId()).isNull();
        assertThat(written.getTaskItemId()).isNull();
        assertThat(written.getSeqNo()).isNull();
        assertThat(written.getContentHash()).isNull();
    }

    /**
     * 只改一个字段时，其余字段保持原值（不是被 null 覆盖）。
     */
    @Test
    @DisplayName("编辑只改标题：其余字段沿用库里的旧值")
    void editOnlyTouchesProvidedFields() {
        when(testCaseMapper.selectOne(any())).thenReturn(caseRow(CASE_ID, "DRAFT"));
        when(testCaseMapper.updateById(any(TestCase.class))).thenReturn(1);

        service.edit(CASE_ID, new CaseUpdateReq("只改标题", null, null, null, null, null));

        ArgumentCaptor<TestCase> patch = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseMapper).updateById(patch.capture());
        TestCase written = patch.getValue();
        assertThat(written.getTitle()).isEqualTo("只改标题");
        assertThat(written.getExpected()).as("没传的字段不能变成 null").isEqualTo("返回 200");
        assertThat(written.getPriority()).isEqualTo("P2");
        assertThat(written.getSteps()).contains("输入账号");
    }

    /**
     * 一个字都没改 → {@code 40001}，不落台账。
     */
    @Test
    @DisplayName("编辑没有任何改动：40001，不写台账（old==new 的快照会让编辑幅度失真）")
    void editWithNoChangeIsRejected() {
        when(testCaseMapper.selectOne(any())).thenReturn(caseRow(CASE_ID, "EDITED"));

        BizException ex = catchThrowableOfType(
                () -> service.edit(CASE_ID, new CaseUpdateReq("旧标题", null, null, null, null, null)),
                BizException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_PARAM_INVALID);
        assertThat(ex.getMessage()).contains("没有任何字段发生变化");
        verify(caseReviewMapper, never()).insert(any(CaseReview.class));
        verify(testCaseMapper, never()).updateById(any(TestCase.class));
    }

    /**
     * 编辑参数非法：空步骤 / 空动作 / 非法优先级 / 非法类型 / 空白标题，一律 40001。
     */
    @Test
    @DisplayName("编辑参数校验：空步骤、空动作、非法优先级/类型、空白标题都是 40001")
    void editRejectsInvalidContent() {
        when(testCaseMapper.selectOne(any())).thenReturn(caseRow(CASE_ID, "DRAFT"));

        assertThat(codeOf(() -> service.edit(CASE_ID,
                new CaseUpdateReq(null, null, List.of(), null, null, null))))
                .as("步骤不能清空").isEqualTo(40001);
        assertThat(codeOf(() -> service.edit(CASE_ID,
                new CaseUpdateReq(null, null, List.of(new CaseUpdateReq.Step("  ", "")), null, null, null))))
                .as("每一步都要有动作描述").isEqualTo(40001);
        assertThat(codeOf(() -> service.edit(CASE_ID,
                new CaseUpdateReq(null, null, null, null, "P9", null))))
                .as("优先级必须是 P0~P3").isEqualTo(40001);
        assertThat(codeOf(() -> service.edit(CASE_ID,
                new CaseUpdateReq(null, null, null, null, null, "SMOKE"))))
                .as("用例类型必须是 CaseType 的合法值").isEqualTo(40001);
        assertThat(codeOf(() -> service.edit(CASE_ID,
                new CaseUpdateReq("   ", null, null, null, null, null))))
                .as("标题不能被清空").isEqualTo(40001);

        verify(caseReviewMapper, never()).insert(any(CaseReview.class));
    }

    /**
     * 前置条件允许被清空（生成 schema 里它没有 minLength）。
     */
    @Test
    @DisplayName("前置条件允许清空：传空串是合法的修改")
    void editAllowsClearingPrecondition() {
        when(testCaseMapper.selectOne(any())).thenReturn(caseRow(CASE_ID, "DRAFT"));
        when(testCaseMapper.updateById(any(TestCase.class))).thenReturn(1);

        service.edit(CASE_ID, new CaseUpdateReq(null, "", null, null, null, null));

        ArgumentCaptor<TestCase> patch = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseMapper).updateById(patch.capture());
        assertThat(patch.getValue().getPrecondition()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 验收⑤：只追加不覆盖 + 同事务
    // ------------------------------------------------------------------

    /**
     * 同一用例两次评审 → 台账两行，状态取最后一次。
     */
    @Test
    @DisplayName("两次评审：台账两行（只追加），状态取最后一次动作")
    void repeatedReviewAppendsLedgerRowsAndLastStatusWins() {
        when(testCaseMapper.selectOne(any()))
                .thenReturn(caseRow(CASE_ID, "DRAFT"), caseRow(CASE_ID, "ACCEPTED"));
        when(rejectReasonMapper.selectById(REASON_CODE)).thenReturn(reason(REASON_CODE, true));

        service.apply(CASE_ID, new ReviewReq("ACCEPT", null, ""));
        service.apply(CASE_ID, new ReviewReq("REJECT", REASON_CODE, "改主意了"));

        ArgumentCaptor<CaseReview> ledger = ArgumentCaptor.forClass(CaseReview.class);
        verify(caseReviewMapper, times(2)).insert(ledger.capture());
        assertThat(ledger.getAllValues()).extracting(CaseReview::getAction)
                .containsExactly("ACCEPT", "REJECT");

        ArgumentCaptor<TestCase> patch = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseMapper, times(2)).updateById(patch.capture());
        assertThat(patch.getAllValues()).extracting(TestCase::getStatus)
                .containsExactly("ACCEPTED", "REJECTED");
    }

    /**
     * 写入顺序：先 INSERT 台账，再 UPDATE 状态。
     */
    @Test
    @DisplayName("写入顺序：先 INSERT 台账、再 UPDATE 状态")
    void ledgerIsInsertedBeforeStatusUpdate() {
        when(testCaseMapper.selectOne(any())).thenReturn(caseRow(CASE_ID, "DRAFT"));

        service.apply(CASE_ID, new ReviewReq("ACCEPT", null, null));

        InOrder order = inOrder(caseReviewMapper, testCaseMapper);
        order.verify(caseReviewMapper).insert(any(CaseReview.class));
        order.verify(testCaseMapper).updateById(any(TestCase.class));
    }

    /**
     * 状态更新失败必须向上抛（同事务才会整体回滚），不能吞掉。
     */
    @Test
    @DisplayName("状态更新失败：异常上抛，配合 @Transactional 整体回滚")
    void statusUpdateFailurePropagatesForRollback() throws Exception {
        when(testCaseMapper.selectOne(any())).thenReturn(caseRow(CASE_ID, "DRAFT"));
        when(testCaseMapper.updateById(any(TestCase.class))).thenThrow(new IllegalStateException("连接被重置"));

        assertThatThrownBy(() -> service.apply(CASE_ID, new ReviewReq("ACCEPT", null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("连接被重置");
        verify(caseReviewMapper).insert(any(CaseReview.class));

        // 事务边界是这个类唯一的"半成功"防线，用注解声明，删掉它必须让测试红
        assertTransactional("apply", Long.class, ReviewReq.class);
        assertTransactional("edit", Long.class, CaseUpdateReq.class);
        assertTransactional("batchReview", BatchReviewReq.class);
    }

    // ------------------------------------------------------------------
    // 验收④：批量评审返回 {updated, skipped}
    // ------------------------------------------------------------------

    /**
     * 批量只处理 DRAFT：非 DRAFT 与不存在都进 skipped，并带可读原因。
     */
    @Test
    @DisplayName("批量评审：只处理 DRAFT，其余进 skipped 并带中文原因")
    void batchOnlyProcessesDraftsAndReportsSkipReasons() {
        when(testCaseMapper.selectById(1L)).thenReturn(caseRow(1L, "DRAFT"));
        when(testCaseMapper.selectById(2L)).thenReturn(caseRow(2L, "ACCEPTED"));
        when(testCaseMapper.selectById(3L)).thenReturn(null);
        when(testCaseMapper.update(any(TestCase.class), any())).thenReturn(1);

        BatchReviewResult result = service.batchReview(
                new BatchReviewReq(List.of(1L, 2L, 3L), "ACCEPT", null, "整批采纳"));

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.skipped()).hasSize(2);
        assertThat(result.skipped().get(0).id()).isEqualTo(2L);
        assertThat(result.skipped().get(0).reason()).contains("已评审").contains("ACCEPTED");
        assertThat(result.skipped().get(1).id()).isEqualTo(3L);
        assertThat(result.skipped().get(1).reason()).isEqualTo("用例不存在");

        ArgumentCaptor<CaseReview> ledger = ArgumentCaptor.forClass(CaseReview.class);
        verify(caseReviewMapper, times(1)).insert(ledger.capture());
        assertThat(ledger.getValue().getCaseId()).isEqualTo(1L);
        assertThat(ledger.getValue().getReasonText()).isEqualTo("整批采纳");
        assertThat(ledger.getValue().getAction()).isEqualTo("ACCEPT");
    }

    /**
     * 批量驳回：整批共用一条原因码，只查一次字典。
     */
    @Test
    @DisplayName("批量驳回：整批共用原因码，字典只查一次")
    void batchRejectShareSingleReasonCode() {
        when(testCaseMapper.selectById(1L)).thenReturn(caseRow(1L, "DRAFT"));
        when(testCaseMapper.selectById(2L)).thenReturn(caseRow(2L, "DRAFT"));
        when(testCaseMapper.update(any(TestCase.class), any())).thenReturn(1);
        when(rejectReasonMapper.selectById(REASON_CODE)).thenReturn(reason(REASON_CODE, true));

        BatchReviewResult result = service.batchReview(
                new BatchReviewReq(List.of(1L, 2L), "REJECT", REASON_CODE, "预期都不清楚"));

        assertThat(result.updated()).isEqualTo(2);
        assertThat(result.skipped()).isEmpty();
        verify(rejectReasonMapper, times(1)).selectById(REASON_CODE);
        ArgumentCaptor<CaseReview> ledger = ArgumentCaptor.forClass(CaseReview.class);
        verify(caseReviewMapper, times(2)).insert(ledger.capture());
        assertThat(ledger.getAllValues()).extracting(CaseReview::getRejectReasonCode)
                .containsOnly(REASON_CODE);
    }

    /**
     * 并发抢输（条件更新 0 行）→ 跳过且<b>不写台账</b>。
     *
     * <p>这条锁的是"先 UPDATE 后 INSERT"的顺序：顺序反了会留下一行"评审了但状态没变"的
     * 孤儿台账，而它同事务提交，回滚也救不回来。</p>
     */
    @Test
    @DisplayName("批量并发抢输：计入 skipped，且不写台账")
    void batchSkipsRaceLoserWithoutWritingLedger() {
        when(testCaseMapper.selectById(1L)).thenReturn(caseRow(1L, "DRAFT"));
        when(testCaseMapper.update(any(TestCase.class), any())).thenReturn(0);

        BatchReviewResult result = service.batchReview(
                new BatchReviewReq(List.of(1L), "ACCEPT", null, null));

        assertThat(result.updated()).isZero();
        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().get(0).reason()).contains("抢先");
        verify(caseReviewMapper, never()).insert(any(CaseReview.class));
    }

    /**
     * 批量去重：同一 ID 传两次只处理一次。
     */
    @Test
    @DisplayName("批量评审：重复 ID 去重，只处理一次")
    void batchDeduplicatesIds() {
        when(testCaseMapper.selectById(1L)).thenReturn(caseRow(1L, "DRAFT"));
        when(testCaseMapper.selectById(2L)).thenReturn(caseRow(2L, "DRAFT"));
        when(testCaseMapper.update(any(TestCase.class), any())).thenReturn(1);

        BatchReviewResult result = service.batchReview(
                new BatchReviewReq(List.of(1L, 1L, 2L, 1L), "ACCEPT", null, null));

        assertThat(result.updated()).isEqualTo(2);
        verify(testCaseMapper, times(2)).selectById(any());
    }

    /**
     * 批量评审的参数护栏：EDIT 不支持、ids 不能空、驳回必须带码。
     */
    @Test
    @DisplayName("批量参数护栏：EDIT 不支持、ids 不能空、驳回必须带码")
    void batchRejectsInvalidRequests() {
        assertThat(codeOf(() -> service.batchReview(
                new BatchReviewReq(List.of(1L), "EDIT", null, null))))
                .as("批量编辑没有意义：每条内容都不同").isEqualTo(40001);
        assertThat(codeOf(() -> service.batchReview(
                new BatchReviewReq(List.of(), "ACCEPT", null, null))))
                .as("一条都没选是必填缺失").isEqualTo(40002);
        assertThat(codeOf(() -> service.batchReview(
                new BatchReviewReq(null, "ACCEPT", null, null))))
                .isEqualTo(40002);
        assertThat(codeOf(() -> service.batchReview(
                new BatchReviewReq(List.of(1L), null, null, null))))
                .as("动作缺失").isEqualTo(40002);
        assertThat(codeOf(() -> service.batchReview(
                new BatchReviewReq(List.of(1L), "REJECT", null, null))))
                .as("驳回没选原因").isEqualTo(40002);

        verifyNoInteractions(testCaseMapper, caseReviewMapper);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /**
     * 构造一条用例行。
     *
     * @param id     主键
     * @param status 状态
     * @return 实体
     */
    private static TestCase caseRow(Long id, String status) {
        TestCase entity = new TestCase();
        entity.setId(id);
        entity.setTaskId(9_000L);
        entity.setTaskItemId(90_000L);
        entity.setTestPointId(900_000L);
        entity.setDocId(9_000_000L);
        entity.setSeqNo(1);
        entity.setTitle("旧标题");
        entity.setPrecondition("已登录");
        entity.setSteps("[{\"no\":1,\"action\":\"输入账号\",\"data\":\"admin\"}]");
        entity.setExpected("返回 200");
        entity.setPriority("P2");
        entity.setCaseType("FUNCTIONAL");
        entity.setStatus(status);
        entity.setConfidence(new BigDecimal("1.000"));
        entity.setPromptVersion("case_generate.v1");
        entity.setModel("deepseek-chat");
        entity.setContentHash("content-hash-1");
        entity.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        return entity;
    }

    /**
     * 构造一条字典行。
     *
     * @param code    字典码
     * @param enabled 是否启用
     * @return 字典实体
     */
    private static RejectReason reason(String code, boolean enabled) {
        RejectReason row = new RejectReason();
        row.setCode(code);
        row.setLabel("预期结果不明确");
        row.setCategory("CONTENT");
        row.setSortNo(10);
        row.setEnabled(enabled);
        return row;
    }

    /**
     * 取出业务异常的码值（断言"抛的是哪个码"用）。
     *
     * @param action 被测动作
     * @return 业务码；没抛异常时返回 -1（让断言失败而不是报空指针）
     */
    private static int codeOf(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        BizException ex = catchThrowableOfType(action, BizException.class);
        return ex == null ? -1 : ex.getErrorCode().getCode();
    }

    /**
     * 断言某个公开方法带 {@code @Transactional(rollbackFor = Exception.class)}。
     *
     * @param name   方法名
     * @param params 参数类型
     * @throws NoSuchMethodException 方法不存在
     */
    private static void assertTransactional(String name, Class<?>... params) throws NoSuchMethodException {
        Transactional tx = CaseReviewService.class.getMethod(name, params)
                .getAnnotation(Transactional.class);
        assertThat(tx).as("%s 必须有 @Transactional：INSERT 与 UPDATE 要同一事务", name).isNotNull();
        assertThat(tx.rollbackFor())
                .as("%s 必须声明 rollbackFor = Exception.class，否则受检异常不回滚", name)
                .contains(Exception.class);
    }
}
