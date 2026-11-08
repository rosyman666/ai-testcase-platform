package com.aittest.testcase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 编辑用例入参（{@code PUT /api/cases/{id}}）。
 *
 * <p><b>这是"编辑后采纳"的唯一入口</b>：{@code POST /{id}/review} 不接受 EDIT 动作，
 * 因为评审请求里没有内容字段，而编辑必须带内容才能写出可 diff 的快照（验收③）。
 * 本接口一个事务里做三件事：留 {@code old_content}/{@code new_content} 快照、
 * 更新用例内容、把 {@code status} 置 {@code EDITED}。</p>
 *
 * <p><b>不可改的字段一律不在本记录里出现</b>：{@code id / task_id / task_item_id /
 * test_point_id / doc_id / seq_no / status / prompt_version / model / content_hash /
 * created_at}。这不是"忘了加"，而是<b>刻意不给入口</b>：</p>
 * <ul>
 *   <li>{@code seq_no} 与 {@code task_item_id} 组成唯一键 {@code uk_test_case_item_seq}，
 *       改了必然撞键；</li>
 *   <li>{@code status} 由评审动作驱动（ACCEPT/EDIT/REJECT），PUT 若能改状态，
 *       就会出现"评审台账说他采纳了、用例却是驳回"的不一致；</li>
 *   <li>{@code prompt_version / model} 是"这条用例怎么来的"的溯源信息，
 *       人工编辑不该改写生成溯源。</li>
 * </ul>
 *
 * <p><b>所有字段可选，但"给了就不能是空"</b>：{@code null} 表示"这一项不动"；
 * 传空串表示"清空"，而 {@code title}/{@code expected} 清空会让用例不可用，
 * 因此服务端对这几个字段按"非空"校验（{@code E_PARAM_INVALID}），
 * 只有 {@code precondition} 允许空串（schema 里它没有 minLength）。</p>
 *
 * @param title        标题（{@code null} = 不改；给了必须非空）
 * @param precondition 前置条件（{@code null} = 不改；允许空串）
 * @param steps        步骤（{@code null} = 不改；给了必须非空，序号由服务端重排）
 * @param expected     预期结果（{@code null} = 不改；给了必须非空）
 * @param priority     优先级编码（{@code null} = 不改；必须是 P0/P1/P2/P3）
 * @param caseType     用例类型编码（{@code null} = 不改；必须是 {@code CaseType} 的合法值）
 * @author 寇豆码
 */
public record CaseUpdateReq(
        @Size(max = 500, message = "标题最多 500 个字符")
        String title,

        @Size(max = 500, message = "前置条件最多 500 个字符")
        String precondition,

        @Valid
        @Size(max = 30, message = "步骤最多 30 条")
        List<Step> steps,

        @Size(max = 1000, message = "预期结果最多 1000 个字符")
        String expected,

        @Size(max = 16, message = "优先级最多 16 个字符")
        String priority,

        @Size(max = 32, message = "用例类型最多 32 个字符")
        String caseType) {

    /**
     * 一步。
     *
     * <p><b>不接收 {@code no}</b>：序号由服务端按数组顺序重排 1..N。让客户端传序号会引入
     * "跳号 / 重号 / 顺序与数组不一致"三种脏数据，而它唯一的作用就是"失败在第几步"，
     * 重排是唯一能保证三者一致的做法。</p>
     *
     * @param action 动作（送进 {@code test_case.steps[].action}）
     * @param data   入参/请求体（可为空；模型不产出，人在编辑时补）
     */
    public record Step(
            @Size(max = 300, message = "单步动作最多 300 个字符")
            String action,

            @Size(max = 300, message = "单步入参最多 300 个字符")
            String data) {
    }
}
