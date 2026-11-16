package com.aittest.execution.dto;

/**
 * 导入结果（{@code POST /api/executions/import}）。
 *
 * <p><b>{@code mappedCount} 一定要返回，而且不合格也不阻断导入</b>：它是本功能唯一能暴露
 * "结果跑的是不是我们导出的那批用例"的信号。JUnit 类名是 {@code GeneratedCase_NNN}
 * （导出时的第 N 行），若中途新增/评审/删关联过，编号会整体错位——那时 {@code mappedCount}
 * 会明显小于 {@code total}，用户在页面上立刻看得见；反过来若为了"好看"把它做成 100%，
 * 每一行都会挂到错误的用例上而没有任何异常。</p>
 *
 * <p><b>为什么单独再返回 {@code runNo}</b>：前端导入成功后直接跳详情页需要 id，
 * 而人（客服/排障）记的是单号。两个都给，免得为了一个号再查一次列表。</p>
 *
 * @param runId       批次主键
 * @param runNo       批次单号（{@code R+yyyyMMdd+6位序号}）
 * @param taskId      所属任务主键（导入时必填；任务被删后批次上会置 NULL）
 * @param taskNo      所属任务单号（{@code T+yyyyMMdd+6位序号}），前端 toast 可用
 *                    「已导入到任务 T20261116-000004」
 * @param total       结果总数
 * @param passed      通过数
 * @param failed      失败数
 * @param skipped     跳过数
 * @param mappedCount 映射到用例的结果条数
 * @author 寇豆码
 */
public record ExecutionImportResult(Long runId,
                                    String runNo,
                                    Long taskId,
                                    String taskNo,
                                    Integer total,
                                    Integer passed,
                                    Integer failed,
                                    Integer skipped,
                                    Integer mappedCount) {
}
