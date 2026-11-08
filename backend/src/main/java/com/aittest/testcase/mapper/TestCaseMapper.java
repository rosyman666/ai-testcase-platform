package com.aittest.testcase.mapper;

import com.aittest.testcase.domain.TestCase;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code test_case} 的数据访问：**幂等写入**是这里的核心，其余交给 {@link BaseMapper}。
 *
 * <p><b>为什么幂等要靠 {@code ON DUPLICATE KEY UPDATE} 而不是 {@code INSERT IGNORE}</b>：
 * worker 在 {@code attempts < 3} 时会重跑同一个 item，而
 * {@code uk_test_case_item_seq(task_item_id, seq_no)} 保证第二次执行必然撞键。两种写法的差别是语义级的：</p>
 * <ul>
 *   <li>{@code ON DUPLICATE KEY UPDATE}：重跑后的内容<b>覆盖</b>旧内容。这是"重新生成"该有的语义。</li>
 *   <li>{@code INSERT IGNORE}：撞键就跳过，库里留着<b>上一次</b>的内容。于是
 *       "重试成功"与"内容更新了"变成两件事，中间没有任何日志能区分。</li>
 * </ul>
 *
 * <p><b>更新列里没有 {@code status}</b>：状态是评审产物，重新生成不能把 ACCEPTED 打回 DRAFT。
 * 同理没有 {@code id}、{@code created_at}、{@code task_id}/{@code task_item_id}/{@code seq_no}
 * （它们是身份，不是内容）。</p>
 *
 * @author 寇豆码
 */
@Mapper
public interface TestCaseMapper extends BaseMapper<TestCase> {

    /**
     * 批量幂等写入：{@code INSERT ... ON DUPLICATE KEY UPDATE}。
     *
     * <p><b>一条语句写完一批</b>，不是逐条 insert：60 个接口 × 每个 1~3 条用例，
     * 逐条写就是上百次往返，且每次都要开一次行锁（T06 的 {@code gen_task_item} 死锁
     * 就是这么来的，见 D-34）。</p>
     *
     * <p><b>{@code id} 由调用方生成</b>（{@code IdWorker}，与 {@code @TableId(ASSIGN_ID)}
     * 同一套雪花算法）：绕开 {@code BaseMapper.insert} 就走不到 MP 的主键填充，
     * 而 {@code id} 是 {@code NOT NULL} 无默认值。</p>
     *
     * <p><b>新插入行的 {@code created_at}/{@code updated_at} 交给数据库默认值</b>（不在列清单里），
     * 更新分支显式写 {@code updated_at = CURRENT_TIMESTAMP(3)}：MySQL 在
     * "值没变化"时可能跳过整行更新，那样 {@code ON UPDATE} 不会触发，
     * {@code updated_at} 就停在旧时间上，看不出"这条被重生成过"。</p>
     *
     * @param cases 待写入的用例（非空；{@code id} 必须已赋值）
     * @return 影响行数（MySQL 语义：插入记 1、更新记 2、未变化记 0，<b>不要当条数用</b>）
     */
    int upsertBatch(@Param("cases") List<TestCase> cases);

    /**
     * 删掉本次 item 的"多余序号"行：重跑后条数变少时清掉尾部残留。
     *
     * <p><b>为什么要它</b>：上次产出 3 条、这次模型只给了 2 条，
     * 而 upsert 只覆盖 {@code seq_no=1,2}，第 3 条旧行会留在库里——评审页会多出一条
     * 本次根本没生成的用例，且没有任何标记说明它是上一轮的残留。</p>
     *
     * <p><b>只按 {@code task_item_id} 删，绝不按 {@code task_id}</b>：
     * 一个任务下有 N 个 item，按 task 删会把<b>其它 item 的用例一起删掉</b>。</p>
     *
     * @param taskItemId 本次执行的 item
     * @param keepSeqNo  本次产出的条数（保留 {@code seq_no <= keepSeqNo}）
     * @return 删除行数
     */
    int deleteStaleSeqNo(@Param("taskItemId") Long taskItemId,
                         @Param("keepSeqNo") int keepSeqNo);

    /**
     * 取 few-shot 素材：同一文档下<b>已被采纳</b>的历史用例，最近的 N 条。
     *
     * <p><b>为什么只取 {@code ACCEPTED}/{@code EDITED}</b>：DRAFT 是模型刚生成的、还没人看过的内容，
     * 拿它当范例等于"用模型的输出教模型"，误差会自我强化；REJECTED 更不能要。
     * 只有人工采纳过（或采纳后又改过）的才是"这篇文档里公认的好用例"。</p>
     *
     * <p><b>为什么按 {@code updated_at} 倒序</b>：同一文档的接口会改版，越近的用例越贴合当前接口；
     * 按 {@code created_at} 会让三年前的范例压过刚评审通过的那批。</p>
     *
     * <p><b>为什么只返回三列</b>：提示词里只用到 {@code title}/{@code case_type}/{@code expected}
     * （{@code steps} 动辄十条，5 条范例就能吃掉上千 token，收益远小于成本）。</p>
     *
     * <p><b>只填充部分字段</b>：返回的 {@link TestCase} 除这三列外全是 {@code null}，
     * <b>不可拿去落库</b>（{@code id} 为空会撞 NOT NULL）。用实体而不是新建 DTO 是为了
     * 复用 {@code map-underscore-to-camel-case} 的现成映射，避免为一个查询再长一个类。</p>
     *
     * @param docId 文档 ID
     * @param limit 取几条（调用方传常量，避免全表扫）
     * @return 素材（可能为空列表，不会为 {@code null}）
     */
    List<TestCase> selectFewShot(@Param("docId") Long docId, @Param("limit") int limit);
}
