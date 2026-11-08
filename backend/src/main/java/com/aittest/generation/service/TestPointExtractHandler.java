package com.aittest.generation.service;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.TaskScene;
import com.aittest.common.enums.TaskStatus;
import com.aittest.doc.domain.DocSource;
import com.aittest.doc.mapper.DocSourceMapper;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.domain.GenTaskItem;
import com.aittest.generation.mapper.GenTaskItemMapper;
import com.aittest.generation.service.TaskItemHandler.ItemOutcome;
import com.aittest.testpoint.dto.ExtractUnit;
import com.aittest.testpoint.dto.TestPointExtractResult;
import com.aittest.testpoint.service.ExtractPlanner;
import com.aittest.testpoint.service.TestPointExtractService;
import com.aittest.testpoint.service.TestPointExtractService.UnitResult;
import com.aittest.testpoint.service.TestPointService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 抽点场景的 item 执行器：一个待分析单元 → 一批测试点。
 *
 * <p><b>T05 与 T06 的接缝就在这里</b>：引擎只负责"把 N 个 item 跑完"，抽取的语义
 * （去重单元级、取代文档级、失败隔离）全部仍由 {@code TestPointExtractService} 与
 * {@code TestPointService} 保证，本类<b>不重写其中任何一环</b>。</p>
 *
 * <p><b>kept_hashes 必须持久化</b>（V5）：同步路径里"本轮产出的去重键"在内存中跨单元累加，
 * 异步下每个 item 各自执行、还可能跨进程重启，收尾时的 {@code supersedeMissing}
 * 只能从库里汇总。少了它就会把没被产出的点误判成"消失了"（D-26 的同一类事故）。</p>
 *
 * <p><b>任务上下文必须透传</b>：{@code LlmRequest.taskId / taskItemId} 只用于成本台账，
 * 而 {@code LoggingLlmProvider} 只看得见请求对象。本类是唯一同时持有 {@code task} 与
 * {@code item} 的地方，因此由它调 {@code extractUnit(unit, taskId, itemId)} 三参重载；
 * T05 同步路径没有任务上下文，走单参重载传 null，语义不变。</p>
 *
 * <p><b>已知偏差（留 T07）</b>：{@code LlmRequest.attemptNo} 在异步链路恒为 1，
 * 重试次数尚未按 {@code item.attempts} 归因。改动它需要把 attempts 透进
 * {@code ExtractUnit} 或再加一层重载，跨 T04/T05/T06，收益低于改动面，本批不碰。</p>
 *
 * <p><b>{@code onTaskFinalize} 只做一次取代</b>：这是"一轮抽取"的真正边界。
 * 失败的单元用 {@code keptHashesOfUnitRef} 把它原有的点算进 {@code kept}——
 * 取代的语义是"原文里已经没有这个点了"，而一次模型超时给不出这个确认。</p>
 *
 * @author 寇豆码
 */
@Service
public class TestPointExtractHandler implements TaskItemHandler {

    private static final Logger log = LoggerFactory.getLogger(TestPointExtractHandler.class);

    private final ExtractPlanner extractPlanner;

    private final TestPointExtractService extractService;

    private final TestPointService testPointService;

    private final DocSourceMapper docSourceMapper;

    private final GenTaskItemMapper genTaskItemMapper;

    public TestPointExtractHandler(ExtractPlanner extractPlanner,
                                   TestPointExtractService extractService,
                                   TestPointService testPointService,
                                   DocSourceMapper docSourceMapper,
                                   GenTaskItemMapper genTaskItemMapper) {
        this.extractPlanner = extractPlanner;
        this.extractService = extractService;
        this.testPointService = testPointService;
        this.docSourceMapper = docSourceMapper;
        this.genTaskItemMapper = genTaskItemMapper;
    }

    @Override
    public TaskScene scene() {
        return TaskScene.TESTPOINT_EXTRACT;
    }

    /**
     * 抽取一个待分析单元。
     *
     * <p>单元由 {@code item.unitRef} 定位：重新规划文档、按 {@code sourceRef} 对上。
     * <b>按 ref 而不是按行 id</b>，因为重解析后行 id 会变，而 {@code operationId}
     * 字符串与 {@code seqNo} 稳定（见 {@code GenTaskItem.unitRef} 注释）。</p>
     *
     * @param task 所属任务（提供 docId 与成本台账用的 taskId）
     * @param item 待执行的子项（提供 unitRef 与 taskItemId）
     */
    @Override
    public ItemOutcome execute(GenTask task, GenTaskItem item) {
        DocSource doc = docSourceMapper.selectById(task.getDocId());
        if (doc == null) {
            throw new BizException(ErrorCode.E_NOT_FOUND, "任务关联的文档已不存在");
        }
        ExtractUnit unit = findUnit(doc, item.getUnitRef());
        // 三参重载：把任务/子项 id 透给 LlmRequest，保证 llm_call_log 可按任务归因。
        // attemptNo 恒 1 是已知偏差（留 T07，见类注释），本批不碰。
        UnitResult result = extractService.extractUnit(unit, task.getId(), item.getId());

        // 本单元产出的去重键落库，供收尾时汇总成 kept（见类注释）
        genTaskItemMapper.update(null, Wrappers.<GenTaskItem>lambdaUpdate()
                .eq(GenTaskItem::getId, item.getId())
                .set(GenTaskItem::getKeptHashes, String.join(",", result.keptHashes())));

        TestPointExtractResult r = result.result();
        if (!r.success()) {
            return ItemOutcome.failed("E_LLM_UPSTREAM",
                    r.error() == null ? "抽取失败" : r.error(),
                    0);
        }
        return ItemOutcome.ok(r.insertedCount(), 0, 0, 0, 0);
    }

    /**
     * 全部单元跑完后的<b>唯一一次</b>取代（D-26 的文档级边界）。
     *
     * <p>kept = 成功单元的产出键 ∪ 失败/跳过单元原有的点。后者必须算进来：
     * 取代的语义是"原文里已经没有这个点了"，而一次模型超时、或一次用户取消，
     * 都给不出这个确认。少了它们，{@code supersedeMissing} 会把整篇文档其余 ACTIVE
     * 测试点误置 SUPERSEDED（B1 的数据破坏）。</p>
     *
     * <p><b>CANCELED 直接返回</b>：用户取消 = 这一轮抽取根本没有做完，此时"本轮产出全集"
     * 不存在，任何取代都是无依据的。引擎侧已用 {@code markTerminal} 的状态守卫挡了一层，
     * 这里是第二道防线——两道都要有，因为收尾权竞争与取消是跨线程的。</p>
     *
     * @param task     所属任务
     * @param terminal 本任务刚落库的终态
     */
    @Override
    public void onTaskFinalize(GenTask task, TaskStatus terminal) {
        if (terminal == TaskStatus.CANCELED
                || TaskStatus.CANCELED.code().equals(task.getStatus())) {
            log.info("[TESTPOINT] 任务已取消，跳过文档级取代（无本轮产出全集）taskNo={}",
                    task.getTaskNo());
            return;
        }
        Set<String> kept = new HashSet<>();
        List<GenTaskItem> items = genTaskItemMapper.selectList(Wrappers.<GenTaskItem>lambdaQuery()
                .eq(GenTaskItem::getTaskId, task.getId()));
        for (GenTaskItem item : items) {
            if ("SUCCESS".equals(item.getStatus())) {
                if (item.getKeptHashes() != null && !item.getKeptHashes().isBlank()) {
                    for (String h : item.getKeptHashes().split(",")) {
                        if (!h.isBlank()) {
                            kept.add(h);
                        }
                    }
                }
            } else if ("FAILED".equals(item.getStatus()) || "SKIPPED".equals(item.getStatus())) {
                // 失败 / 被取消跳过的单元：保住它原有的点（没跑完 ≠ 原文里没有了）。
                // 按 unitRef（= source_ref）反查——item 只存了 ref 字符串，不存 FK
                kept.addAll(testPointService.keptHashesOfUnitRef(task.getDocId(), item.getUnitRef()));
            }
        }
        int superseded = testPointService.supersedeMissing(task.getDocId(), kept);
        log.info("[TESTPOINT] 任务收尾取代完成 taskNo={} 终态={} kept={} 取代={} 条",
                task.getTaskNo(), terminal.code(), kept.size(), superseded);
    }

    /** 按 {@code sourceRef} 找到待分析单元。 */
    private ExtractUnit findUnit(DocSource doc, String unitRef) {
        if (unitRef == null || unitRef.isBlank()) {
            throw new BizException(ErrorCode.E_PARAM_INVALID, "任务子项缺少待分析单元定位");
        }
        for (ExtractUnit unit : extractPlanner.plan(doc)) {
            if (unitRef.equals(unit.sourceRef())) {
                return unit;
            }
        }
        // 单元对不上：文档被重解析过、或 unitRef 是脏值。必须炸出来，不能静默抽错单元
        throw new BizException(ErrorCode.E_STATE_ILLEGAL,
                "找不到待分析单元：" + unitRef + "（文档可能已被重新解析）");
    }
}
