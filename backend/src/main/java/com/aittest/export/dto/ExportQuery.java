package com.aittest.export.dto;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.CaseStatus;
import com.aittest.export.support.JUnitNaming;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * 导出端点 #35 / #36 的入参（docs/07 §3.1 / §4.1 / §4.2）。
 *
 * <p><b>这是两个端点共用的唯一入参对象</b>：`statuses` 的归一化与校验只在这里实现一次，
 * 保证「同一任务下 xlsx 与 zip 的用例条数一致」（T09-P1-2）。任何一端自行解析
 * `statuses` 都会产生两套口径，且不会报错。</p>
 *
 * <p><b>默认不是全量</b>：未传 / 空串回落 {@code ACCEPTED,EDITED}。把「空」解读成
 * 「全量」会把 DRAFT 与 REJECTED 一起导出，与 §4.1 的默认行为相反。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
public class ExportQuery {

    /** 非法值提示里列出的可选值，与 {@link CaseStatus} 四值逐字一致。 */
    private static final String STATUS_OPTIONS = "DRAFT/ACCEPTED/EDITED/REJECTED";

    /** 缺省状态集合（EDITED 算「改后采纳」，DRAFT/REJECTED 不导）。 */
    private static final List<String> DEFAULT_STATUSES = List.of("ACCEPTED", "EDITED");

    /** 任务 ID（必填）。 */
    private Long taskId;

    /** 文档 ID（可选）。 */
    private Long docId;

    /** 逗号分隔的状态白名单；未传 / 空串走默认。 */
    private String statuses;

    /** 仅 #36 生效：生成 JUnit 用例类的 Java 包名。 */
    private String packageName;

    /**
     * 校验必填的 taskId。
     *
     * @return 任务 ID
     * @throws BizException 缺失时 40002
     */
    public Long requireTaskId() {
        if (taskId == null) {
            throw new BizException(ErrorCode.E_REQUIRED_MISSING, "缺少必填参数：taskId");
        }
        return taskId;
    }

    /**
     * 归一化 `statuses`：逗号分隔 → trim → 按 {@link CaseStatus} 白名单校验 → 去重保序。
     *
     * <p><b>非法值整单 40001，不静默丢弃、不部分导出</b>；`CaseStatus.valueOf` 是大小写敏感的，
     * 因此 `accepted` 之类的写法同样被拒。空段（末尾多一个逗号）被跳过；若全是空段则回落默认，
     * 与「空串 = 默认」语义一致。</p>
     *
     * @return 归一化后的状态列表（至少 1 项，永不为 null）
     * @throws BizException 含非法值时 40001，message 列出可选值
     */
    public List<String> normalizeStatuses() {
        if (statuses == null || statuses.isBlank()) {
            return new ArrayList<>(DEFAULT_STATUSES);
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : statuses.split(",", -1)) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                normalized.add(CaseStatus.valueOf(token).name());
            } catch (IllegalArgumentException ex) {
                throw new BizException(ErrorCode.E_PARAM_INVALID,
                        "statuses 含非法值：" + token + "；可选值：" + STATUS_OPTIONS);
            }
        }
        if (normalized.isEmpty()) {
            return new ArrayList<>(DEFAULT_STATUSES);
        }
        return new ArrayList<>(normalized);
    }

    /**
     * 校验显式传入的 Java 包名；未传返回 {@code null}，由调用方用 {@code export.junitPackage} 兜底。
     *
     * <p>包名不合法的后果不是「导出失败」而是「zip 里的 .java 编译不过」，属于把参数错误
     * 推迟到用户机器上才暴露，所以必须前置成 40001。</p>
     *
     * @return 合法包名；未传返回 {@code null}
     * @throws BizException 非法包名 40001
     */
    public String requirePackageName() {
        if (packageName == null || packageName.isBlank()) {
            return null;
        }
        String candidate = packageName.trim();
        if (!JUnitNaming.isValidJavaPackageName(candidate)) {
            throw new BizException(ErrorCode.E_PARAM_INVALID,
                    "packageName 不是合法的 Java 包名：" + candidate);
        }
        return candidate;
    }

    /**
     * 生成「统计」sheet 用的生效条件回显文本（同一份归一化状态，不重解析入参）。
     *
     * @param normalizedStatuses 已归一化的状态列表
     * @return 形如 {@code statuses=ACCEPTED,EDITED; taskId=123; docId=456}
     */
    public String criteria(List<String> normalizedStatuses) {
        StringBuilder text = new StringBuilder("statuses=")
                .append(String.join(",", normalizedStatuses))
                .append("; taskId=").append(taskId);
        if (docId != null) {
            text.append("; docId=").append(docId);
        }
        return text.toString();
    }
}
