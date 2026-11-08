package com.aittest.generation.service;

import com.aittest.auth.AuthContext;
import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.IdGen;
import com.aittest.common.enums.TaskScene;
import com.aittest.common.enums.TaskStatus;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.domain.GenTaskItem;
import com.aittest.generation.mapper.GenTaskItemMapper;
import com.aittest.generation.mapper.GenTaskMapper;
import com.aittest.testpoint.domain.TestPoint;
import com.aittest.testpoint.mapper.TestPointMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 任务的创建、取消、认领与查询。
 *
 * <p><b>创建不走大事务</b>（与 {@code DocService.upload} 同一条纪律，见 §11.1 D-17）：
 * {@link IdGen#generateWithRetry} 依赖"撞键时回滚重播"，若把它包进一个大事务，
 * 事务内抛 {@code DuplicateKeyException} 后 Spring 已把事务标记 rollback-only，
 * 同事务重试必然继续失败。<b>取号与插入走自动提交</b>，只有子表批量插入这一段自己保证。</p>
 *
 * <p><b>子表批量插入的<b>唯一</b>事务边界是 {@link #itemTxTemplate}</b>：外层 {@code create()}
 * 没有环境事务，{@code insertItems} 用编程式事务模板把 N 条 INSERT 收成<b>一次提交</b>。
 * 若改用 self-invocation 的 {@code @Transactional}，同类内调用不走代理、注解不生效，
 * 60 条 INSERT 又会退化成 60 个 autocommit——既是 60 个死锁点，中途失败还会留下
 * 半截子表数据（任务已 FAILED、子表躺着一部分）。事务模板失败整体回滚，重试前无需清表。</p>
 *
 * <p><b>建任务与 dispatcher 认领会真实死锁</b>：{@code claimBatch} 在同一张
 * {@code gen_task_item} 上跑 {@code FOR UPDATE SKIP LOCKED}，建任务侧在二级索引间隙上
 * INSERT，两侧锁序交叉即 {@code Deadlock found}。InnoDB 明确要求这类错误由应用侧重试，
 * 故 {@code insertItems} 外层做<b>有界重试</b>（见 {@link #MAX_ITEM_INSERT_ATTEMPTS}）。</p>
 *
 * <p><b>任务创建失败不删主表行</b>：子表插入若失败，把任务标 {@code FAILED} 而不是物理删——
 * 删了就丢号（{@code doc_no} 的播种靠当日最大号，见 §8.2），也丢掉"为什么没建成"的现场。</p>
 *
 * @author 寇豆码
 */
@Service
public class GenTaskService {

    private static final Logger log = LoggerFactory.getLogger(GenTaskService.class);

    /** 任务级默认超时：创建后 30 分钟（防僵尸任务）。 */
    private static final int DEADLINE_MINUTES = 30;

    /** 超时回收写进 item 的错误码（非空，NOT NULL 列）。 */
    private static final String DEADLINE_ERROR_CODE = "E_TASK_DEADLINE";

    /** 超时回收写进 item 的中文说明（非空，NOT NULL 列）。 */
    private static final String DEADLINE_ERROR_MSG = "任务超过截止时间，未完成子项已置为失败";

    /**
     * 子项批量插入遇到死锁时的最大尝试次数（含首次）。
     *
     * <p>{@code 3} 次足够：死锁是"两侧锁序交叉"的瞬态现象，重试方只要不再持有对方要的锁，
     * 下一轮基本必成。再往上加只会把用户请求拖长，且真正持续的死锁重试也解不开。</p>
     */
    private static final int MAX_ITEM_INSERT_ATTEMPTS = 3;

    private final GenTaskMapper genTaskMapper;

    private final GenTaskItemMapper genTaskItemMapper;

    private final TestPointMapper testPointMapper;

    private final IdGen idGen;

    private final ProgressPublisher progressPublisher;

    /**
     * 子表批量插入的编程式事务模板（{@link #insertItems} 专用）。
     *
     * <p>用模板而不是 {@code @Transactional} 的原因见类注释：同类内 self-invocation
     * 不走代理，注解不生效。此模板是 item 插入的<b>唯一</b>事务边界。</p>
     */
    private final TransactionTemplate itemTxTemplate;

    public GenTaskService(GenTaskMapper genTaskMapper,
                          GenTaskItemMapper genTaskItemMapper,
                          TestPointMapper testPointMapper,
                          IdGen idGen,
                          ProgressPublisher progressPublisher,
                          PlatformTransactionManager txManager) {
        this.genTaskMapper = genTaskMapper;
        this.genTaskItemMapper = genTaskItemMapper;
        this.testPointMapper = testPointMapper;
        this.idGen = idGen;
        this.progressPublisher = progressPublisher;
        this.itemTxTemplate = new TransactionTemplate(txManager);
    }

    /**
     * 创建一个任务并批量插入它的 item。
     *
     * <p><b>本方法刻意不加 {@code @Transactional}</b>，理由见类注释。</p>
     *
     * @param scene     场景
     * @param docId     关联文档
     * @param provider  模型供应商
     * @param model     模型名
     * @param unitRefs  待分析单元定位（抽点场景：{@code operation:xxx} / {@code section:n}）
     * @param testPointIds 关联测试点（生成场景；与 {@code unitRefs} 二选一）
     * @return 任务 ID
     */
    public Long create(TaskScene scene, Long docId, String provider, String model,
                       List<String> unitRefs, List<Long> testPointIds) {
        List<String> refs = unitRefs == null ? List.of() : unitRefs;
        List<Long> pointIds = testPointIds == null ? List.of() : testPointIds;
        int total = Math.max(refs.size(), pointIds.size());
        if (total == 0) {
            throw new BizException(ErrorCode.E_PARAM_INVALID, "任务没有可执行的子项");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        GenTask task = idGen.generateWithRetry(IdGen.NoType.TASK,
                taskNo -> insertTask(taskNo, scene, docId, provider, model, total, now));
        try {
            insertItemsWithDeadlockRetry(task, refs, pointIds, total);
        } catch (Exception ex) {
            log.error("[TASK] 子项插入失败，任务置 FAILED taskNo={} 原因={}", task.getTaskNo(), ex.getMessage());
            genTaskMapper.markTerminal(task.getId(), TaskStatus.FAILED.code(),
                    ErrorCode.E_INTERNAL.name(), "任务子项初始化失败：" + ex.getMessage(), now);
            throw ex instanceof BizException biz ? biz
                    : new BizException(ErrorCode.E_INTERNAL, "任务创建失败");
        }
        log.info("[TASK] 任务已创建 taskNo={} scene={} items={}", task.getTaskNo(), scene.code(), total);
        return task.getId();
    }

    /**
     * 带死锁有界重试的子表批量插入（建任务 ↔ dispatcher 认领的真实死锁，见类注释）。
     *
     * <p><b>为什么捕获 {@link PessimisticLockingFailureException} 而不是 JDBC 的
     * {@code MySQLTransactionRollbackException}</b>：MyBatis-Spring 的
     * {@code MyBatisExceptionTranslator} 会把 JDBC 异常翻成 Spring 的 DataAccessException
     * 体系，{@code DeadlockLoserDataAccessException} / {@code CannotAcquireLockException}
     * 都是这个父类的子类。直接 catch 驱动层实现类既脆、又会被翻译层挡住。</p>
     *
     * <p>内层是独立事务、失败整体回滚，因此<b>重试前不需要清表</b>，也绝不加
     * "先 DELETE 再 INSERT"。重试用尽后把最后一次异常原样抛给 {@link #create} 的
     * 既有 catch（置 FAILED + 包成 {@code E_INTERNAL "任务创建失败"}）。</p>
     *
     * @param task   已插入的主表任务（提供 id / taskNo 供日志）
     * @param refs   待分析单元定位
     * @param pointIds 关联测试点
     * @param total  子项总数（日志用）
     */
    private void insertItemsWithDeadlockRetry(GenTask task, List<String> refs,
                                              List<Long> pointIds, int total) {
        int attempts = 0;
        while (true) {
            try {
                insertItems(task.getId(), refs, pointIds);
                return;
            } catch (PessimisticLockingFailureException ex) {
                if (++attempts >= MAX_ITEM_INSERT_ATTEMPTS) {
                    throw ex;
                }
                try {
                    // 有界指数退避 + 抖动：避免两侧在同一时刻再次抢同一把锁
                    Thread.sleep(50L * attempts + ThreadLocalRandom.current().nextInt(50));
                } catch (InterruptedException ie) {
                    // 不吞中断标志：置回后按失败处理，让上层 catch 走"置 FAILED"分支
                    Thread.currentThread().interrupt();
                    throw ex;
                }
                log.warn("[TASK] 子项插入死锁，第 {} 次重试 taskNo={} items={}",
                        attempts, task.getTaskNo(), total);
            }
        }
    }

    /**
     * 取消任务：{@code PENDING/RUNNING → CANCELED}，并把<b>未认领的</b> item 置 {@code SKIPPED}。
     *
     * <p><b>只停 PENDING，不动 RUNNING</b>：注释一直承诺"已经在跑的那条跑完它自己，不强杀"，
     * 但旧实现把 RUNNING 也置了 SKIPPED，既与注释矛盾，又放大了 B1 的竞态窗口——
     * 被置 SKIPPED 的 in-flight item 完成时会把状态覆盖回 SUCCESS。RUNNING 由 worker 的
     * 终态检查自己跳过，且 {@code markTerminal} 的状态守卫会让它抢不到收尾权。</p>
     *
     * <p><b>取消成功后必须推一次 SSE 终态</b>（I5）：不推的话订阅端会一直挂在
     * {@code SSE} 上直到 30 分钟超时，前端永远不知道任务已经结束。</p>
     *
     * @param taskId 任务
     * @return 被跳过的子项数
     */
    public int cancel(Long taskId) {
        GenTask task = require(taskId);
        TaskStatus from = TaskStatus.valueOf(task.getStatus());
        TaskStateMachine.assertTransition(from, TaskStatus.CANCELED);
        genTaskItemMapper.update(null, Wrappers.<GenTaskItem>lambdaUpdate()
                .eq(GenTaskItem::getTaskId, taskId)
                .eq(GenTaskItem::getStatus, "PENDING")
                .set(GenTaskItem::getStatus, "SKIPPED"));
        int skipped = countSkipped(taskId);
        // error_code 是 NOT NULL 列，"无错误"用空串——传 null 会被 MySQL 严格模式拒掉
        genTaskMapper.markTerminal(taskId, TaskStatus.CANCELED.code(), "", "用户取消",
                LocalDateTime.now(ZoneOffset.UTC));
        task.setStatus(TaskStatus.CANCELED.code());
        progressPublisher.finish(task, TaskStatus.CANCELED, "", "用户取消");
        log.info("[TASK] 任务已取消 taskNo={} 跳过子项={}", task.getTaskNo(), skipped);
        return skipped;
    }

    /**
     * 统计被跳过的子项数（{@code cancel} 用）。
     *
     * @param taskId 任务
     * @return {@code SKIPPED} 条数
     */
    private int countSkipped(Long taskId) {
        Long n = genTaskItemMapper.selectCount(Wrappers.<GenTaskItem>lambdaQuery()
                .eq(GenTaskItem::getTaskId, taskId)
                .eq(GenTaskItem::getStatus, "SKIPPED"));
        return n == null ? 0 : n.intValue();
    }

    /**
     * 认领一批可执行的子项：<b>必须在事务里</b>，否则 {@code FOR UPDATE SKIP LOCKED} 的行锁
     * 在语句结束即释放，等于没用，并发下两个 worker 会拿到同一批 id（验收②）。
     *
     * <p>{@code nowUtc} 由应用侧算好传给 SQL，<b>SQL 里不许求当前时间</b>（验收⑧）。</p>
     *
     * <p><b>顺带把任务置 RUNNING（I6）</b>：状态机定义了 {@code PENDING→RUNNING}，但旧实现
     * 从来没有落地，导致 {@code started_at} 恒 NULL。这里用带 {@code status='PENDING'} 守卫的
     * 条件更新，只在首次认领时写一次，且不会把已取消的任务"复活"。</p>
     *
     * @param limit    本次最多认领条数
     * @param workerId worker 标识（排障用）
     * @return 认领到的 item id
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Long> claimBatch(int limit, String workerId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Long> ids = genTaskItemMapper.claimPending(limit, now);
        if (ids.isEmpty()) {
            return ids;
        }
        int marked = genTaskItemMapper.markRunning(ids, workerId, now);
        if (marked != ids.size()) {
            // 竞态探针：正常情况下 SKIP LOCKED 保证不会有人抢同一批行
            log.warn("[TASK] 认领竞态：期望标记 RUNNING {} 条，实际 {} 条 worker={} —— 请检查事务边界",
                    ids.size(), marked, workerId);
        }
        // 先把 gen_task 置 RUNNING 的 taskIds 算出来（本事务已持有这些 item 行的排他锁，
        // 普通 MVCC 快照读不加锁），再用显式 taskIds 更新 gen_task。
        // 不在 UPDATE gen_task 里塞 gen_task_item 子查询：那会对子表再加一层共享锁，
        // 与建任务侧的 INSERT 形成锁序交叉，正是死锁的另一半根因。
        List<Long> taskIds = genTaskItemMapper.selectTaskIdsByItemIds(ids);
        if (!taskIds.isEmpty()) {
            genTaskMapper.markRunningByItemIds(taskIds, now);
        }
        return ids;
    }

    /**
     * 超时回收（I7）：把 deadline 已过、仍非终态任务下<b>所有未收尾</b>的 item 置 FAILED。
     *
     * <p><b>由 dispatcher 每 60s 节流调用，不在 500ms 轮询里扫全表</b>。worker 崩溃时
     * RUNNING item 永停，{@code maybeFinalize} 因 {@code running > 0} 永不收尾；
     * 同理，退避中 / 未认领的 PENDING item 也会让 {@code pending > 0} 卡住收尾。
     * 两种状态都必须在这里一并终态，否则任务永远停在 PENDING/RUNNING 且 SSE 等不到
     * done/error，回收器每 60s 空转。</p>
     *
     * <p>置完 item 后<b>必须补计数</b>：这些 item 没走 {@code onItemDone}，
     * 不补 {@code done_items/failed_items} 会让进度条永远差一截。最后返回的 taskId
     * 由调用方逐个交给 {@code GenTaskWorker.finalizeIfDone} 做收尾判定——
     * 收尾判定放在事务外，避免长事务包住 {@code supersedeMissing}。</p>
     *
     * @param nowUtc 当前时刻（UTC，应用侧算）
     * @return 受影响的任务 id（调用方应对每个 id 调一次 {@code finalizeIfDone}）
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Long> recycleExpiredTasks(LocalDateTime nowUtc) {
        List<Long> taskIds = genTaskMapper.selectExpiredTaskIds(nowUtc);
        if (taskIds.isEmpty()) {
            return taskIds;
        }
        int totalFailed = 0;
        for (Long taskId : taskIds) {
            int failedRunning = genTaskItemMapper.failRunningItemsOfTask(taskId, nowUtc,
                    DEADLINE_ERROR_CODE, DEADLINE_ERROR_MSG);
            int failedPending = genTaskItemMapper.failPendingItemsOfTask(taskId, nowUtc,
                    DEADLINE_ERROR_CODE, DEADLINE_ERROR_MSG);
            int failed = failedRunning + failedPending;
            if (failed > 0) {
                genTaskMapper.bumpCounters(taskId, failed, 0, failed, 0);
                totalFailed += failed;
            }
        }
        log.warn("[TASK] 超时回收：{} 个任务的未收尾子项已置 FAILED，共 {} 条",
                taskIds.size(), totalFailed);
        return taskIds;
    }

    /**
     * 查询任务实体。
     *
     * @param taskId 任务
     * @return 实体
     */
    public GenTask require(Long taskId) {
        GenTask task = genTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.E_NOT_FOUND, "任务不存在");
        }
        return task;
    }

    /**
     * 插入任务主表（供 {@link IdGen#generateWithRetry} 调用）。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>——撞键重试必须让每次插入真正提交/回滚。</p>
     *
     * <p><b>createdBy 落库（I9）</b>：SSE 订阅要做归属隔离。历史行 {@code created_by} 全为
     * NULL，所以这里允许空（非 Web 线程 / 未登录时返回 null），订阅侧按"非空且不等于当前
     * 用户才拒绝"处理，历史任务不会被误锁死。</p>
     */
    private GenTask insertTask(String taskNo, TaskScene scene, Long docId,
                               String provider, String model, int total, LocalDateTime now) {
        GenTask task = new GenTask();
        task.setTaskNo(taskNo);
        task.setScene(scene.code());
        task.setDocId(docId);
        task.setProvider(provider == null || provider.isBlank() ? "mock" : provider);
        task.setModel(model == null ? "" : model);
        task.setStatus(TaskStatus.PENDING.name());
        task.setTotalItems(total);
        task.setDoneItems(0);
        task.setSuccessItems(0);
        task.setFailedItems(0);
        task.setAcceptedCases(0);
        task.setProducedItems(0);
        task.setProgress(0);
        task.setRetryRound(0);
        task.setErrorCode("");
        task.setErrorMsg("");
        task.setCreatedBy(currentUserIdOrNull());
        task.setDeadlineAt(now.plusMinutes(DEADLINE_MINUTES));
        genTaskMapper.insert(task);
        return task;
    }

    /** 当前登录用户 ID（未登录 / 非 Web 线程返回 null，{@code created_by} 允许为空）。 */
    private static Long currentUserIdOrNull() {
        try {
            return AuthContext.requireUserId();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * 批量插入子项（<b>在独立事务内</b>，一次提交，失败整体回滚）。
     *
     * <p>事务边界由 {@link #itemTxTemplate} 提供，理由见类注释。外层
     * {@link #create} 没有环境事务，因此这里不会与取号的自动提交混在一起。</p>
     *
     * <p>{@code unit_ref} 取 NULL 而非空串：MySQL 唯一索引把多个 NULL 视为互不冲突，
     * 用例生成场景的 item 不填 unit_ref 时不会互相撞键（见 V5 迁移脚本）。</p>
     */
    private void insertItems(Long taskId, List<String> unitRefs, List<Long> testPointIds) {
        List<GenTaskItem> items = new ArrayList<>();
        if (!unitRefs.isEmpty()) {
            for (String ref : unitRefs) {
                items.add(newPendingItem(taskId, ref, null));
            }
        } else {
            for (Long pointId : testPointIds) {
                TestPoint point = testPointMapper.selectById(pointId);
                if (point == null) {
                    throw new BizException(ErrorCode.E_NOT_FOUND, "测试点不存在：" + pointId);
                }
                items.add(newPendingItem(taskId, "testpoint:" + pointId, pointId));
            }
        }
        itemTxTemplate.executeWithoutResult(status -> {
            for (GenTaskItem item : items) {
                genTaskItemMapper.insert(item);
            }
        });
    }

    /**
     * 构造一条初始 {@code PENDING} 状态的子项（字段默认值集中在这一处）。
     *
     * @param taskId      所属任务
     * @param unitRef     待分析单元定位（抽点：{@code operation:xxx}/{@code section:n}；
     *                    生成：{@code testpoint:<id>}）
     * @param testPointId 关联测试点（抽点场景为 null）
     * @return 未落库的子项实体
     */
    private static GenTaskItem newPendingItem(Long taskId, String unitRef, Long testPointId) {
        GenTaskItem item = new GenTaskItem();
        item.setTaskId(taskId);
        item.setTestPointId(testPointId);
        item.setUnitRef(unitRef);
        item.setStatus("PENDING");
        item.setAttempts(0);
        item.setCaseCount(0);
        item.setDegraded(0);
        item.setErrorCode("");
        item.setErrorMsg("");
        item.setPromptTokens(0);
        item.setCompletionTokens(0);
        item.setCostMicro(0L);
        item.setLatencyMs(0);
        item.setNextRunAt(LocalDateTime.now(ZoneOffset.UTC));
        item.setWorkerId("");
        return item;
    }
}
