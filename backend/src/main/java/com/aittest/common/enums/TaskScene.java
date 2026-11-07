package com.aittest.common.enums;

/**
 * 任务场景（{@code gen_task.scene}）：让两类异步任务复用同一套状态机、调度器与 SSE 推送。
 *
 * @author 寇豆码
 */
public enum TaskScene implements CodedEnum {

    /** 从文档/接口抽取测试点。 */
    TESTPOINT_EXTRACT("TESTPOINT_EXTRACT", "测试点抽取", 10),

    /** 从测试点生成用例。 */
    CASE_GENERATE("CASE_GENERATE", "用例生成", 20);

    private final String code;

    private final String label;

    private final int sortNo;

    TaskScene(String code, String label, int sortNo) {
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
}
