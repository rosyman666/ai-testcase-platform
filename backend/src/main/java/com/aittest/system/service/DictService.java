package com.aittest.system.service;

import com.aittest.common.BizException;
import com.aittest.common.CodeLabel;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.CasePriority;
import com.aittest.common.enums.CaseStatus;
import com.aittest.common.enums.CaseType;
import com.aittest.common.enums.CodedEnum;
import com.aittest.common.enums.DocType;
import com.aittest.common.enums.ExecutionStatus;
import com.aittest.common.enums.ParseStatus;
import com.aittest.common.enums.RiskLevel;
import com.aittest.common.enums.TaskScene;
import com.aittest.common.enums.TaskStatus;
import com.aittest.common.enums.TestPointStatus;
import com.aittest.common.enums.TriageSource;
import com.aittest.system.domain.FailureCategory;
import com.aittest.system.domain.RejectReason;
import com.aittest.system.mapper.FailureCategoryMapper;
import com.aittest.system.mapper.RejectReasonMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * 字典服务：把「数据库字典表」与「代码枚举」归一化成同一个 {@link CodeLabel} 结构。
 *
 * <p>归一化的价值：前端只需要一套 {@code {code,label}} 渲染逻辑，不必知道某个字典
 * 来自 {@code reject_reason} 表还是来自某个 enum；将来某天把枚举也迁到数据库时，
 * 前端零改动。</p>
 *
 * @author 寇豆码
 */
@Service
public class DictService {

    /** 驳回原因字典类型标识。 */
    public static final String TYPE_REJECT_REASONS = "reject-reasons";

    /** 失败分类字典类型标识。 */
    public static final String TYPE_FAILURE_CATEGORIES = "failure-categories";

    private final RejectReasonMapper rejectReasonMapper;

    private final FailureCategoryMapper failureCategoryMapper;

    public DictService(RejectReasonMapper rejectReasonMapper, FailureCategoryMapper failureCategoryMapper) {
        this.rejectReasonMapper = rejectReasonMapper;
        this.failureCategoryMapper = failureCategoryMapper;
    }

    /**
     * 驳回原因字典（按 sort_no 升序）。
     *
     * @param enabledOnly 是否只返回启用的项（前端表单传 true，历史数据回显传 false）
     * @return 字典项
     */
    public List<CodeLabel> rejectReasons(boolean enabledOnly) {
        List<RejectReason> rows = rejectReasonMapper.selectList(Wrappers.<RejectReason>lambdaQuery()
                .eq(enabledOnly, RejectReason::getEnabled, Boolean.TRUE)
                .orderByAsc(RejectReason::getSortNo));
        return rows.stream()
                .map(row -> new CodeLabel(row.getCode(), row.getLabel(), safeSortNo(row.getSortNo())))
                .toList();
    }

    /**
     * 失败分类字典（按 sort_no 升序）。
     *
     * @param enabledOnly 是否只返回启用的项
     * @return 字典项
     */
    public List<CodeLabel> failureCategories(boolean enabledOnly) {
        List<FailureCategory> rows = failureCategoryMapper.selectList(Wrappers.<FailureCategory>lambdaQuery()
                .eq(enabledOnly, FailureCategory::getEnabled, Boolean.TRUE)
                .orderByAsc(FailureCategory::getSortNo));
        return rows.stream()
                .map(row -> new CodeLabel(row.getCode(), row.getLabel(), safeSortNo(row.getSortNo())))
                .toList();
    }

    /**
     * 按类型取字典（{@code GET /api/dict/{type}} 的统一入口）。
     *
     * <p>类型名同时接受中划线、下划线与大写形式（{@code reject-reasons} /
     * {@code reject_reasons} / {@code REJECT_REASONS}），避免前端因命名风格不同而 404。</p>
     *
     * @param type        字典类型
     * @param enabledOnly 是否只返回启用项（仅对数据库字典生效）
     * @return 字典项
     */
    public List<CodeLabel> byType(String type, boolean enabledOnly) {
        String normalized = normalize(type);
        return switch (normalized) {
            case TYPE_REJECT_REASONS -> rejectReasons(enabledOnly);
            case TYPE_FAILURE_CATEGORIES -> failureCategories(enabledOnly);
            case "doc-types" -> ofEnum(DocType.values());
            case "parse-statuses" -> ofEnum(ParseStatus.values());
            case "case-types" -> ofEnum(CaseType.values());
            case "priorities" -> ofEnum(CasePriority.values());
            case "case-statuses" -> ofEnum(CaseStatus.values());
            case "task-statuses" -> ofEnum(TaskStatus.values());
            case "task-scenes" -> ofEnum(TaskScene.values());
            case "risk-levels" -> ofEnum(RiskLevel.values());
            case "test-point-statuses" -> ofEnum(TestPointStatus.values());
            case "execution-statuses" -> ofEnum(ExecutionStatus.values());
            case "triage-sources" -> ofEnum(TriageSource.values());
            default -> throw new BizException(ErrorCode.E_PARAM_INVALID, "不支持的字典类型：" + type);
        };
    }

    /**
     * 枚举数组转字典项（按 sortNo 升序）。
     *
     * @param values 枚举值
     * @return 字典项
     */
    public List<CodeLabel> ofEnum(CodedEnum[] values) {
        return Arrays.stream(values)
                .map(CodeLabel::of)
                .sorted(Comparator.comparingInt(CodeLabel::sortNo))
                .toList();
    }

    /**
     * 类型名归一化：下划线转中划线、转小写、去空白。
     *
     * @param type 原始类型名
     * @return 归一化后的类型名
     */
    private String normalize(String type) {
        if (type == null || type.isBlank()) {
            throw new BizException(ErrorCode.E_REQUIRED_MISSING, "缺少字典类型");
        }
        return type.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * 排序号兜底（DB 里为 NULL 时按 0 处理，避免拆箱 NPE）。
     *
     * @param sortNo 排序号
     * @return 非空排序号
     */
    private int safeSortNo(Integer sortNo) {
        return sortNo == null ? 0 : sortNo;
    }
}
