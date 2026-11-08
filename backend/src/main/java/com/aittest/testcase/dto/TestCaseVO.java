package com.aittest.testcase.dto;

import com.aittest.common.JsonUtils;
import com.aittest.testcase.domain.CaseReview;
import com.aittest.testcase.domain.TestCase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用例出参（列表与详情共用）。
 *
 * <p><b>{@code steps} 必须是对象数组，不能是 JSON 字符串</b>：库里存的是
 * {@code [{"no":1,"action":"...","data":"..."}]} 的<b>字符串</b>。原样丢给前端，
 * 前端要为表格里每一个单元格 {@code JSON.parse} 一次——纯浪费，而且一旦某条脏数据
 * 解析失败，整张表白屏（一个 case 的 steps 坏了会把整页带走）。
 * 在这里解析并降级（解析失败返回空数组 + WARN）就把故障半径限制在那一行。</p>
 *
 * <p><b>为什么列表页不返回评审历史</b>：一页 20 条用例，每条平均 2~3 次评审，
 * 一次列表查询会变成 40~60 条台账行的额外读取，而列表根本不展示历史。
 * 只有详情（{@code GET /api/cases/{id}}）才带 {@code reviews}，列表里恒为空数组。</p>
 *
 * <p><b>快照为什么也转成 JSON 对象</b>：{@code oldContent}/{@code newContent} 存的是整份用例
 * JSON 的字符串。前端做 diff 需要按字段比对，返回字符串等于把 {@code JSON.parse} 又推给前端一次。
 * 这里转成节点返回，序列化时就是内联对象。</p>
 *
 * @param id            主键
 * @param taskId        所属任务
 * @param taskItemId    所属任务子项
 * @param testPointId   关联测试点（点被删后为 null）
 * @param docId         关联文档
 * @param seqNo         同一 item 内的序号
 * @param title         标题
 * @param precondition  前置条件
 * @param steps         步骤（对象数组，已解析）
 * @param expected      预期结果
 * @param priority      优先级编码
 * @param caseType      用例类型编码
 * @param status        评审状态编码
 * @param confidence    解析保真度（1.000 正常 / 0.500 宽松降级；<b>不是内容质量分</b>）
 * @param promptVersion 生成时的提示词版本
 * @param model         生成时的模型名
 * @param contentHash   生成期内容哈希（评审编辑不回写，见 {@code CaseReviewService} 类注释）
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 * @param reviews       评审历史（按 created_at 升序；列表页为空）
 * @author 寇豆码
 */
public record TestCaseVO(Long id,
                         Long taskId,
                         Long taskItemId,
                         Long testPointId,
                         Long docId,
                         Integer seqNo,
                         String title,
                         String precondition,
                         List<Step> steps,
                         String expected,
                         String priority,
                         String caseType,
                         String status,
                         BigDecimal confidence,
                         String promptVersion,
                         String model,
                         String contentHash,
                         LocalDateTime createdAt,
                         LocalDateTime updatedAt,
                         List<ReviewItem> reviews) {

    private static final Logger log = LoggerFactory.getLogger(TestCaseVO.class);

    /** 脏 steps 打进日志的最大长度。 */
    private static final int LOG_TEXT_MAX = 120;

    /**
     * 一条可执行步骤。
     *
     * @param no     步骤序号（从 1 开始，服务端重排，不信客户端）
     * @param action 动作
     * @param data   入参/请求体（模型不产出，恒空串）
     */
    public record Step(Integer no, String action, String data) {
    }

    /**
     * 一条评审历史。
     *
     * @param id               台账主键
     * @param action           ACCEPT / EDIT / REJECT
     * @param rejectReasonCode 驳回原因字典码（非 REJECT 为 null）
     * @param reasonText       自由文本说明（可能为空串）
     * @param reviewerId       评审人（人删了为 null）
     * @param createdAt        评审时间
     * @param oldContent       编辑前快照（只有 EDIT 非 null）
     * @param newContent       编辑后快照（只有 EDIT 非 null）
     */
    public record ReviewItem(Long id,
                             String action,
                             String rejectReasonCode,
                             String reasonText,
                             Long reviewerId,
                             LocalDateTime createdAt,
                             JsonNode oldContent,
                             JsonNode newContent) {
    }

    /**
     * 实体转出参（不带评审历史，列表页用）。
     *
     * @param entity 实体
     * @return 出参
     */
    public static TestCaseVO from(TestCase entity) {
        return from(entity, List.of());
    }

    /**
     * 实体转出参（带评审历史，详情页用）。
     *
     * @param entity  实体
     * @param reviews 评审历史
     * @return 出参
     */
    public static TestCaseVO from(TestCase entity, List<ReviewItem> reviews) {
        return new TestCaseVO(entity.getId(), entity.getTaskId(), entity.getTaskItemId(),
                entity.getTestPointId(), entity.getDocId(), entity.getSeqNo(), entity.getTitle(),
                entity.getPrecondition(), parseSteps(entity.getSteps()), entity.getExpected(),
                entity.getPriority(), entity.getCaseType(), entity.getStatus(), entity.getConfidence(),
                entity.getPromptVersion(), entity.getModel(), entity.getContentHash(),
                entity.getCreatedAt(), entity.getUpdatedAt(),
                reviews == null ? List.of() : reviews);
    }

    /**
     * 台账实体转评审历史。
     *
     * @param review 台账行（可为 null）
     * @return 评审历史；{@code null} 入参返回 {@code null}
     */
    public static ReviewItem reviewItemOf(CaseReview review) {
        if (review == null) {
            return null;
        }
        return new ReviewItem(review.getId(), review.getAction(), review.getRejectReasonCode(),
                review.getReasonText(), review.getReviewerId(), review.getCreatedAt(),
                parseSnapshot(review.getOldContent()), parseSnapshot(review.getNewContent()));
    }

    /**
     * 解析 {@code test_case.steps} 的 JSON 数组字符串。
     *
     * <p><b>解析失败返回空数组而不是抛异常</b>：这是"读模型产出的二手数据"，
     * 一条脏行不该让整页列表 500。降级成"没有步骤"是可用的（人一眼能看出异常），
     * 而 500 会让用户以为整个任务都坏了。</p>
     *
     * @param json JSON 数组字符串
     * @return 步骤列表（永不 {@code null}）
     */
    public static List<Step> parseSteps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = JsonUtils.mapper().readTree(json);
            if (node == null || !node.isArray()) {
                log.warn("[CASE] steps 不是 JSON 数组，按空步骤返回：{}", abbreviate(json));
                return List.of();
            }
            List<Step> steps = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                int no = item.path("no").asInt(steps.size() + 1);
                steps.add(new Step(no, item.path("action").asText(""), item.path("data").asText("")));
            }
            return steps;
        } catch (JsonProcessingException ex) {
            log.warn("[CASE] steps 不是合法 JSON，按空步骤返回（{}）：{}",
                    ex.getOriginalMessage(), abbreviate(json));
            return List.of();
        }
    }

    /**
     * 解析快照字符串为 JSON 节点。
     *
     * @param json 快照字符串
     * @return 节点；空值/非法 JSON 返回 {@code null}
     */
    public static JsonNode parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.mapper().readTree(json);
        } catch (JsonProcessingException ex) {
            log.warn("[CASE] 评审快照不是合法 JSON（{}）：{}", ex.getOriginalMessage(), abbreviate(json));
            return null;
        }
    }

    /**
     * 截断长文本（只用于日志）。
     *
     * @param text 原文
     * @return 截断后的文本
     */
    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= LOG_TEXT_MAX ? text : text.substring(0, LOG_TEXT_MAX) + "…";
    }
}
