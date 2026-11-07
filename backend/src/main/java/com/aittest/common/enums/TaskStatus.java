package com.aittest.common.enums;

/**
 * 异步任务状态（{@code gen_task.status}）：抽点与生成共用同一套状态机。
 *
 * @author 寇豆码
 */
public enum TaskStatus implements CodedEnum {

    /** 已创建，等待调度器认领。 */
    PENDING("PENDING", "排队中", 10),

    /** 执行中。 */
    RUNNING("RUNNING", "执行中", 20),

    /** 全部子项成功。 */
    SUCCESS("SUCCESS", "成功", 30),

    /** 部分子项失败（可重试）。 */
    PARTIAL("PARTIAL", "部分成功", 40),

    /** 全部失败。 */
    FAILED("FAILED", "失败", 50),

    /** 用户取消。 */
    CANCELED("CANCELED", "已取消", 60);

    private final String code;

    private final String label;

    private final int sortNo;

    TaskStatus(String code, String label, int sortNo) {
        this.code = code;
        this.label = label;
        this.sortNo = sortNo;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public int sortNo() {
        return sortNo;
    }

    /**
     * 是否终态（终态不允许再调度）。
     *
     * @return SUCCESS/PARTIAL/FAILED/CANCELED 返回 true
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == PARTIAL || this == FAILED || this == CANCELED;
    }

    /**
     * 是否可重试（仅 PARTIAL/FAILED，见 43001）。
     *
     * @return 可重试返回 true
     */
    public boolean isRetryable() {
        return this == PARTIAL || this == FAILED;
    }
}
