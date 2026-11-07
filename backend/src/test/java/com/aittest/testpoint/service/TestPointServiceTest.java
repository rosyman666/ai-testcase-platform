package com.aittest.testpoint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aittest.common.enums.TestPointStatus;
import com.aittest.testpoint.domain.TestPoint;
import com.aittest.testpoint.domain.TestPointHasher;
import com.aittest.testpoint.dto.TestPointDraft;
import com.aittest.testpoint.mapper.TestPointMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 测试点去重与状态流转的回归测试（验收 ④）。
 *
 * <p><b>为什么用 mock 而不是连库</b>：被测的是<b>决策逻辑</b>——"这一条该插、该忽略、还是该复活"，
 * 输入是已有行的集合。连库测会把 MySQL 的唯一索引语义、事务、时区一并卷进来，
 * 而那些各有各的测试（D-18 的 MySQL-only 场景）。这里只用 mock 喂"库里已有什么"，
 * 断言"服务端发出了什么写操作"，覆盖更准也更快。</p>
 *
 * <p><b>本类要挡住的具体事故</b>：重复抽取导致数量翻倍、人工修订被模型覆盖、
 * 人停用的点被 AI 复活、模型抽风漏抽一条导致该点永久消失。四个都不会抛异常，
 * 只会让评审页上的数字和内容悄悄变。</p>
 *
 * <p><b>两阶段 API 是端到端验收抓出来的真设计错误</b>（见 §11.1 D-26）：
 * {@code mergeExtraction} 按<b>单元</b>做去重合并，{@code supersedeMissing} 按<b>整篇文档</b>
 * 做取代判定，两者时间尺度不同。曾经把它们写在同一个方法里，结果是多单元文档中
 * 单元之间互相踩——第 1 个单元处理完就把后面单元的产出标掉，ACTIVE 数随机。
 * 最后两条测试就是这个事故的回归锁。</p>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class TestPointServiceTest {

    /** 所属文档。 */
    private static final Long DOC = 900L;

    /** 来源定位。 */
    private static final String REF = "operation:createUser";

    /** 提示词版本。 */
    private static final String PROMPT_VERSION = "testpoint_extract.v1";

    @Mock
    private TestPointMapper mapper;

    private TestPointService service;

    @BeforeEach
    void setUp() {
        service = new TestPointService(mapper);
    }

    /**
     * 构造一条已有行。
     *
     * @param title    标题
     * @param category 分类
     * @param status   状态
     * @return 实体
     */
    private TestPoint row(String title, String category, String status) {
        TestPoint point = new TestPoint();
        point.setId((long) title.hashCode());
        point.setDocId(DOC);
        point.setTitle(title);
        point.setCategory(category);
        point.setStatus(status);
        point.setContentHash(TestPointHasher.hashOf(DOC, title, category));
        point.setDescription("人工修订过的描述，不该被覆盖");
        point.setRiskLevel("HIGH");
        return point;
    }

    /**
     * 构造一条模型产出。
     *
     * @param title    标题
     * @param category 分类
     * @return draft
     */
    private TestPointDraft draft(String title, String category) {
        return new TestPointDraft(title, category, "MEDIUM", "模型写的描述", REF);
    }

    @Test
    @DisplayName("④ 全新点：插入，返回新增条数")
    void insertsNewPoints() {
        when(mapper.selectByDocId(DOC)).thenReturn(new ArrayList<>());

        TestPointService.MergeOutcome merged = service.mergeExtraction(DOC,
                List.of(draft("创建用户", "FUNCTIONAL"), draft("name 超长报错", "BOUNDARY")),
                1L, null, REF, PROMPT_VERSION);

        assertThat(merged.inserted()).isEqualTo(2);
        assertThat(merged.keptHashes()).hasSize(2);
        verify(mapper, times(2)).insert(any(TestPoint.class));
        verify(mapper, never()).updateById(any(TestPoint.class));
    }

    @Test
    @DisplayName("④ 重复抽取：命中已有 ACTIVE 行时一字不动（人工修订的描述/风险等级必须活下来）")
    void keepsExistingActiveUntouched() {
        TestPoint existing = row("创建用户", "FUNCTIONAL", TestPointStatus.ACTIVE.code());
        when(mapper.selectByDocId(DOC)).thenReturn(List.of(existing));

        TestPointService.MergeOutcome merged = service.mergeExtraction(DOC,
                List.of(new TestPointDraft("创建用户", "FUNCTIONAL", "LOW", "模型这次写的另一版描述", REF)),
                1L, null, REF, PROMPT_VERSION);

        assertThat(merged.inserted()).isZero();
        verify(mapper, never()).insert(any(TestPoint.class));
        // 关键断言：一次 update 都不许有 —— 有就说明模型的值盖掉了人工修订
        verify(mapper, never()).updateById(any(TestPoint.class));
        assertThat(existing.getDescription()).isEqualTo("人工修订过的描述，不该被覆盖");
        assertThat(existing.getRiskLevel()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("人停用的点不许被 AI 复活：命中 DISABLED 行时一字不动")
    void neverRevivesDisabled() {
        TestPoint disabled = row("创建用户", "FUNCTIONAL", TestPointStatus.DISABLED.code());
        when(mapper.selectByDocId(DOC)).thenReturn(List.of(disabled));

        TestPointService.MergeOutcome merged = service.mergeExtraction(DOC,
                List.of(draft("创建用户", "FUNCTIONAL")), 1L, null, REF, PROMPT_VERSION);

        assertThat(merged.inserted()).isZero();
        verify(mapper, never()).insert(any(TestPoint.class));
        verify(mapper, never()).updateById(any(TestPoint.class));
        assertThat(disabled.getStatus()).isEqualTo(TestPointStatus.DISABLED.code());
    }

    @Test
    @DisplayName("SUPERSEDED 的点被再次抽到时复活：模型漏抽是随机的，不该把点永久埋掉")
    void revivesSuperseded() {
        TestPoint superseded = row("创建用户", "FUNCTIONAL", TestPointStatus.SUPERSEDED.code());
        when(mapper.selectByDocId(DOC)).thenReturn(List.of(superseded));

        TestPointService.MergeOutcome merged = service.mergeExtraction(DOC,
                List.of(draft("创建用户", "FUNCTIONAL")), 1L, null, REF, PROMPT_VERSION);

        assertThat(merged.inserted()).isZero();      // 复活不算新增
        verify(mapper, never()).insert(any(TestPoint.class));
        assertThat(superseded.getStatus()).isEqualTo(TestPointStatus.ACTIVE.code());
        // 复活只改状态，描述仍是人工写的那版
        assertThat(superseded.getDescription()).isEqualTo("人工修订过的描述，不该被覆盖");
    }

    @Test
    @DisplayName("④ 本轮没再产出的 ACTIVE 行置 SUPERSEDED（不是删除：用例还引用着它们）")
    void supersedesVanishedPoints() {
        TestPoint kept = row("创建用户", "FUNCTIONAL", TestPointStatus.ACTIVE.code());
        TestPoint vanished = row("name 超长报错", "BOUNDARY", TestPointStatus.ACTIVE.code());
        when(mapper.selectByDocId(DOC)).thenReturn(List.of(kept, vanished));

        TestPointService.MergeOutcome merged = service.mergeExtraction(DOC,
                List.of(draft("创建用户", "FUNCTIONAL")), 1L, null, REF, PROMPT_VERSION);
        int superseded = service.supersedeMissing(DOC, merged.keptHashes());

        assertThat(superseded).isEqualTo(1);
        assertThat(kept.getStatus()).isEqualTo(TestPointStatus.ACTIVE.code());
        assertThat(vanished.getStatus()).isEqualTo(TestPointStatus.SUPERSEDED.code());
    }

    @Test
    @DisplayName("DISABLED 的点本轮没抽到也保持 DISABLED：状态是人定的，不由抽取轮次改写")
    void leavesDisabledAloneWhenVanished() {
        TestPoint disabled = row("创建用户", "FUNCTIONAL", TestPointStatus.DISABLED.code());
        when(mapper.selectByDocId(DOC)).thenReturn(List.of(disabled));

        TestPointService.MergeOutcome merged = service.mergeExtraction(DOC,
                List.of(draft("另一个点", "EXCEPTION")), 1L, null, REF, PROMPT_VERSION);
        service.supersedeMissing(DOC, merged.keptHashes());

        assertThat(disabled.getStatus()).isEqualTo(TestPointStatus.DISABLED.code());
    }

    @Test
    @DisplayName("同批内重复只留第一条：'哪条算数'必须确定，不能随模型措辞随机")
    void dedupesWithinBatch() {
        when(mapper.selectByDocId(DOC)).thenReturn(new ArrayList<>());

        TestPointService.MergeOutcome merged = service.mergeExtraction(DOC,
                List.of(new TestPointDraft("创建用户", "FUNCTIONAL", "HIGH", "第一条", REF),
                        new TestPointDraft("  创建用户  ", "FUNCTIONAL", "LOW", "第二条，同名同分类", REF)),
                1L, null, REF, PROMPT_VERSION);

        assertThat(merged.inserted()).isEqualTo(1);
        ArgumentCaptor<TestPoint> captor = ArgumentCaptor.forClass(TestPoint.class);
        verify(mapper, times(1)).insert(captor.capture());
        // 取的是第一条：描述是"第一条"
        assertThat(captor.getValue().getDescription()).isEqualTo("第一条");
    }

    @Test
    @DisplayName("sourceRef 用服务端的值，不采信模型写的")
    void usesServerSideSourceRef() {
        when(mapper.selectByDocId(DOC)).thenReturn(new ArrayList<>());

        service.mergeExtraction(DOC,
                List.of(new TestPointDraft("创建用户", "FUNCTIONAL", "MEDIUM", "描述",
                        "operation:这个接口不存在")),   // 模型写错来源
                1L, null, REF, PROMPT_VERSION);

        ArgumentCaptor<TestPoint> captor = ArgumentCaptor.forClass(TestPoint.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getSourceRef()).isEqualTo(REF);
        assertThat(captor.getValue().getPromptVersion()).isEqualTo(PROMPT_VERSION);
    }

    @Test
    @DisplayName("回归：单元级合并不做取代 —— 后面的单元不得标掉前面单元的产出")
    void mergeNeverSupersedesAcrossUnits() {
        // 库里已有单元 1 的产出
        TestPoint fromUnit1 = row("创建用户", "FUNCTIONAL", TestPointStatus.ACTIVE.code());
        when(mapper.selectByDocId(DOC)).thenReturn(List.of(fromUnit1));

        // 单元 2 抽出一个与单元 1 无关的新点
        service.mergeExtraction(DOC, List.of(draft("name 超长报错", "BOUNDARY")),
                2L, null, "operation:updateUser", PROMPT_VERSION);

        // 关键：单元 2 落库时，单元 1 的产出必须仍是 ACTIVE。
        // 取代是文档级动作，此刻还没轮到它做 —— 提前做就是那场 ACTIVE 数随机的事故
        assertThat(fromUnit1.getStatus()).isEqualTo(TestPointStatus.ACTIVE.code());
        verify(mapper, never()).updateById(any(TestPoint.class));
    }

    @Test
    @DisplayName("supersedeMissing 等所有单元完成后统一判定：只取代本轮真正没再产出的点")
    void supersedesOnceAfterAllUnits() {
        TestPoint keptByUnit1 = row("创建用户", "FUNCTIONAL", TestPointStatus.ACTIVE.code());
        TestPoint vanished = row("旧点", "EXCEPTION", TestPointStatus.ACTIVE.code());
        when(mapper.selectByDocId(DOC)).thenReturn(List.of(keptByUnit1, vanished));

        // 两个单元各抽各的，期间谁都不许动 vanished
        TestPointService.MergeOutcome u1 = service.mergeExtraction(DOC,
                List.of(draft("创建用户", "FUNCTIONAL")), 1L, null, "operation:createUser", PROMPT_VERSION);
        TestPointService.MergeOutcome u2 = service.mergeExtraction(DOC,
                List.of(draft("新点", "BOUNDARY")), 2L, null, "operation:updateUser", PROMPT_VERSION);
        assertThat(vanished.getStatus()).isEqualTo(TestPointStatus.ACTIVE.code());

        // 全部单元完成后，把 kept 汇总成一份，取代只做这一次
        Set<String> kept = new HashSet<>(u1.keptHashes());
        kept.addAll(u2.keptHashes());
        int superseded = service.supersedeMissing(DOC, kept);

        assertThat(superseded).isEqualTo(1);
        assertThat(keptByUnit1.getStatus()).isEqualTo(TestPointStatus.ACTIVE.code());
        assertThat(vanished.getStatus()).isEqualTo(TestPointStatus.SUPERSEDED.code());
    }
}
