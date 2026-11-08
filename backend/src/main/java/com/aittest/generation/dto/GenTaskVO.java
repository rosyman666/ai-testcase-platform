package com.aittest.generation.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 任务出参（列表与详情共用）。
 *
 * <p><b>为什么不用实体直出</b>（§4.4 硬规则）：{@code GenTask} 上有 {@code created_by}、
 * {@code error_msg} 这类不该外泄的字段，而"今天没有敏感字段"不是判据——
 * 判据的输入会变（T06 今天单用户，加多用户那天就有了），这个变化不会触发任何人回来复审。
 * 显式 VO 多写几行，但泄露是<b>编译期不可能发生</b>。</p>
 *
 * <p><b>时间统一输出 {@code Instant} 且固定 3 位毫秒 + {@code Z}</b>：
 * {@code Instant.toString()} 的小数位随精度浮动，前端 {@code dayjs} 无法稳定解析
 * （见 CHANGELOG「{@code Instant} 序列化出 9 位纳秒」）。</p>
 *
 * @param taskId    任务 ID（字符串，Long 全局序列化为字符串防精度丢失）
 * @param taskNo    业务单号
 * @param scene     场景
 * @param status    状态
 * @param progress  进度 0-100
 * @param done      已完成 item 数
 * @param total     item 总数
 * @param success   成功 item 数
 * @param failed    失败 item 数
 * @param produced  本轮产出条数（抽点=测试点数，生成=用例数），读 {@code gen_task.produced_items}（V6）
 * @param provider  模型供应商
 * @param model     模型名
 * @param docId     关联文档
 * @param errorCode 任务级错误码
 * @param errorMsg  任务级错误说明
 * @param startedAt 开始时间
 * @param finishedAt 结束时间
 * @param createdAt 创建时间
 * @author 寇豆码
 */
public record GenTaskVO(String taskId,
                        String taskNo,
                        String scene,
                        String status,
                        int progress,
                        int done,
                        int total,
                        int success,
                        int failed,
                        int produced,
                        String provider,
                        String model,
                        String docId,
                        String errorCode,
                        String errorMsg,
                        Instant startedAt,
                        Instant finishedAt,
                        Instant createdAt) {

    private static final DateTimeFormatter MS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    /**
     * 由实体组装。
     *
     * @param t 实体
     * @return VO
     */
    public static GenTaskVO of(com.aittest.generation.domain.GenTask t) {
        return new GenTaskVO(
                String.valueOf(t.getId()),
                t.getTaskNo(),
                t.getScene(),
                t.getStatus(),
                t.getProgress() == null ? 0 : t.getProgress(),
                t.getDoneItems() == null ? 0 : t.getDoneItems(),
                t.getTotalItems() == null ? 0 : t.getTotalItems(),
                t.getSuccessItems() == null ? 0 : t.getSuccessItems(),
                t.getFailedItems() == null ? 0 : t.getFailedItems(),
                // V6：produced 读本轮产出列，不再复用 accepted_cases（评审采纳数，T07 语义）
                t.getProducedItems() == null ? 0 : t.getProducedItems(),
                t.getProvider(),
                t.getModel(),
                t.getDocId() == null ? null : String.valueOf(t.getDocId()),
                t.getErrorCode(),
                t.getErrorMsg(),
                toInstant(t.getStartedAt()),
                toInstant(t.getFinishedAt()),
                toInstant(t.getCreatedAt()));
    }

    /** UTC 的 LocalDateTime → Instant（列本身存的就是 UTC，见 §3.2.1.1）。 */
    private static Instant toInstant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }
}
