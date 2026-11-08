package com.aittest.generation.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 创建任务入参。
 *
 * <p>{@code unitRefs} 与 {@code testPointIds} <b>二选一</b>：抽点场景传前者
 * （{@code operation:xxx} / {@code section:n}），生成场景传后者。
 * 两者都传或都不传都会被 {@code 40001} 挡住——不是"随便挑一个用"，
 * 因为那会让"这条 item 到底对应什么"变成运行期才暴露的歧义。</p>
 *
 * @param scene        场景：{@code TESTPOINT_EXTRACT} / {@code CASE_GENERATE}
 * @param docId        关联文档 ID
 * @param provider     模型供应商（缺省 {@code mock}）
 * @param model        模型名（缺省空串）
 * @param unitRefs     待分析单元定位（抽点场景）
 * @param testPointIds 关联测试点（生成场景）
 * @author 寇豆码
 */
public record GenTaskCreateReq(@NotBlank(message = "场景不能为空") String scene,

                               Long docId,

                               String provider,

                               String model,

                               @Size(max = 200, message = "单个任务最多 200 个子项")
                               List<String> unitRefs,

                               @Size(max = 200, message = "单个任务最多 200 个子项")
                               List<Long> testPointIds) {

    /**
     * 校验二选一约束。
     *
     * @throws jakarta.validation.ConstraintViolation 不满足时由 Bean Validation 抛出；
     *         这里额外补一条两难情形（都传/都不传），因为它跨两个字段，单字段注解表达不了
     */
    @AssertTrue(message = "unitRefs 与 testPointIds 必须二选一")
    public boolean isExactlyOneGroup() {
        boolean hasUnits = unitRefs != null && !unitRefs.isEmpty();
        boolean hasPoints = testPointIds != null && !testPointIds.isEmpty();
        return hasUnits ^ hasPoints;
    }
}
