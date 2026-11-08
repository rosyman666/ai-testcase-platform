-- =============================================================================
-- V6：gen_task 补 produced_items；gen_task_item.unit_ref 对齐到 VARCHAR(200)
--
-- ## ① produced_items：把「本轮产出」与「评审采纳」拆成两列（I1）
-- V1 把 accepted_cases 定义为「本任务已采纳用例数（评审后回写）」，是 T07 的采纳率
-- 分母口径。但 T06 的 ProgressPublisher.bumpCounters 每条 item 都会传一个 produced，
-- 而 V1~V5 的 SQL 从未把它写进任何列 —— 于是 GenTaskVO.produced / SSE 快照的 produced
-- 恒为 0。两种看起来都能"修好"的改法都是错的：
--   * 直接累加进 accepted_cases：本轮产出 ≠ 评审采纳，会污染 T07 的采纳率分母；
--   * 复用 accepted_cases 当产出列：语义彻底反转，且 T07 回写时会覆盖掉产出。
-- 因此新增独立列，由 bumpCounters 以 = col + n 原子累加。
--
-- ## ② unit_ref VARCHAR(64) → VARCHAR(200)（I2）
-- V5 给 unit_ref 定了 64 字符。但 ExtractPlanner.operationRef() 在没有 operationId
-- 时的 fallback 是 "operation:" + METHOD + " " + path，而 V1 的 api_operation.path
-- 是 VARCHAR(500)、test_point.source_ref 是 VARCHAR(200)、operation_id 是 VARCHAR(200)。
-- 一个没有 operationId 的 OpenAPI 会生成超长 ref，写入 gen_task_item.unit_ref 直接 1406
-- (Data too long for column) → 任务创建失败。这里与 source_ref / operation_id 对齐到 200。
--
-- 注意：unit_ref 上有唯一索引 uk_gen_task_item_task_unit (task_id, unit_ref)。
-- MySQL 8 / utf8mb4_0900_ai_ci 的索引前缀上限是 3072 字节，200*4 + 8 = 808 字节，
-- 远低于上限，所以改列宽不需要先 DROP INDEX；MODIFY COLUMN 会原地重建该索引。
--
-- ## ③ 幂等与历史数据
-- 两列都给 DEFAULT，历史行自动补 0 / 保持原值，不需要回填脚本。
-- Flyway validate-on-migrate=true，本文件一旦合并就不得再改内容。
-- =============================================================================

ALTER TABLE gen_task
    ADD COLUMN produced_items INT NOT NULL DEFAULT 0
        COMMENT '本轮产出条数（抽点=测试点数，生成=用例数），由 bumpCounters 累加；与 accepted_cases（评审采纳数）语义分离'
        AFTER failed_items;

ALTER TABLE gen_task_item
    MODIFY COLUMN unit_ref VARCHAR(200) DEFAULT NULL
        COMMENT '待分析单元定位：operation:<operationId> / section:<seqNo> / testpoint:<id>；无 operationId 时可为 operation:<METHOD> <path>';
