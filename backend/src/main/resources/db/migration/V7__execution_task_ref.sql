-- =============================================================================
-- V7：test_case 补 junit_ref；execution_run 补 task_id
--     两列都是 T10「执行结果导入」的前置条件，且都属于"表里缺一个能对上的键"
-- =============================================================================
--
-- ① test_case.junit_ref：把「导出时的类名序号」固化到用例行上
--
-- JUnit 导出（T09 方案 A）把每一条用例包成一个类，类名恒为 GeneratedCase_NNN，
-- 其中 NNN 是「当次导出的查询结果第 N 行」的序号（见 export/support/JUnitNaming.java
-- 与 export/service/JUnitExportService.java 的写入循环）。这是刻意为之：中文标题绝不
-- 进标识符，否则会出现非法字符与重复类名。
--
-- 问题在于「第 N 行」是**一次查询的产物**，而 test_case 上没有任何列能表达它。
-- 执行结果导入（T10）拿到的是 JUnit XML 里的 classname="...GeneratedCase_007"，
-- 如果导入时按「与导出时相同的筛选条件重算一遍行号」，那么只要两次之间发生过
-- 下面任意一件事，行号就会**整体错位**：
--   * 同任务下新生成了一批用例；
--   * 有人评审把某条用例从 DRAFT 改成 ACCEPTED（statuses 过滤集合变了）；
--   * 有人删掉了 doc_id 关联（docId 过滤集合变了）。
-- 错位之后映射率可能仍是 100%，每一条结果都"成功"挂到了**错误的用例**上，
-- 且没有任何异常、没有任何日志。这正是本项目反复登记的「不许静默通过」红线
-- （对照 D-39 / D-41 / D-46）：宁可导入时映射不上（mapped_count 小于 total_count，
-- 页面上直接可见），也不能全错还显示全绿。
--
-- 因此：**在导出那一刻**把「这一次的第 N 行是哪个用例」写进 test_case.junit_ref，
-- 导入时按 (task_id, junit_ref) 精确匹配。编号一旦落库就是事实，后续新增/评审/删除
-- 都不会改变它。
--
-- 为什么不能用现有列代替：
--   * seq_no：同一 item 内从 1 开始，一个任务下有 N 个 item，seq_no 会重复，
--     根本不是全局唯一的行号；
--   * title：可能重复（不同 item 可以生成同标题用例），且会被评审编辑改写；
--   * content_hash：是 sha256(title\nsteps\nexpected)，评审编辑不回写，
--     用例被编辑后就与导出时不一致，反而会把能映射的映射不上。
--
-- 语义：同一任务只保留「最近一次导出」的编号。JunitRefWriter 每次导出先清空该任务的
-- junit_ref 再写入本批编号。清空是刻意的——上一次导出的旧编号与这一次的第 N 行是两套
-- 互不相容的编号体系，并存只会让「同一个 GeneratedCase_007」对应两条用例。
-- 代价是：导出了新文件之后用旧文件导入会全部映射不上（mapped_rate 掉到 0），
-- 这是**可见**的失败，符合纪律。
--
-- 列宽 64：GeneratedCase_NNN 是 17 字符，留足余量。允许 NULL：从未导出过的用例没有编号。
--
-- -----------------------------------------------------------------------------
-- ② execution_run.task_id：让批次记得"这批结果属于哪个任务"
--
-- 最初建表（V1:355-375）没有这一列，导入时接收的 taskId 只用于当次映射、**不落库**。
-- 后果有两个，都是硬缺口而不是"锦上添花"：
--   1. 列表/详情无法回答「这批结果属于哪个任务」。用户排查"结果和用例对不上"时，
--      第一句问的就是这个，而库里查不出来；
--   2. #42 POST /api/executions/{id}/remap（重跑用例映射）**永远做不了**——
--      重跑时不知道该拿哪个任务的用例去 junit_ref 里对，只能退化成"必须重新上传文件"，
--      那不是重跑映射，是重新导入。
--
-- 为什么是 ON DELETE SET NULL 而不是 CASCADE：
-- 执行结果是**外部跑出来的事实**（CI 跑过就是跑过），它不属于任务，只是"曾经关联过"。
-- 任务被删（gen_task 上 ON DELETE CASCADE 会带走 test_case）时把执行历史一起抹掉，
-- 等于"删了任务就查不到当时跑成什么样"，而用例侧的 case_id 本来就已经
-- 由 fk_execution_result_case ON DELETE SET NULL 处理过（明细仍在，只是不再指向用例）。
-- 同一条口径：**事实保留，引用置空**。
--
-- 允许 NULL：历史行（本迁移之前导入的批次）与"任务后来被删"的批次都没有 task_id，
-- 这是正常状态；VO 侧 taskNo 返回 null，前端显示"—"。
--
-- -----------------------------------------------------------------------------
-- Flyway validate-on-migrate=true，本文件一旦合并到任何环境就不得再改内容。
-- =============================================================================

ALTER TABLE test_case
    ADD COLUMN junit_ref VARCHAR(64) DEFAULT NULL
        COMMENT '最近一次 JUnit 导出分配的类名序号（GeneratedCase_NNN），执行结果导入时用于反查用例'
        AFTER content_hash,
    ADD KEY idx_test_case_task_junit_ref (task_id, junit_ref);

ALTER TABLE execution_run
    ADD COLUMN task_id BIGINT DEFAULT NULL
        COMMENT '关联任务（导入时指定，供重跑映射与"这批属于哪个任务"查询使用），关联 gen_task.id'
        AFTER run_no,
    ADD KEY idx_execution_run_task_id (task_id),
    ADD CONSTRAINT fk_execution_run_task FOREIGN KEY (task_id) REFERENCES gen_task (id) ON DELETE SET NULL;
