-- =============================================================================
-- V5：gen_task_item 补 unit_ref —— 抽点场景下"这条 item 对应哪个待分析单元"
--
-- ## 为什么 V1 的表结构不够
-- V1 的 gen_task_item 只预留了 test_point_id，注释写"抽点场景为 NULL"。可是 worker
-- 拿到一条 item 时必须知道**该抽哪个接口/哪个章节**，否则只能把整篇文档重抽一遍——
-- 那就退化成了 T05 之前的同步语义，异步引擎等于白建。
--
-- ## 为什么用 unit_ref 而不是再加 operation_id / section_id 两个外键
-- 1. **sourceRef 已经是单元的规范身份**（T05 验收③ 要求"sourceRef 能反查回
--    operation/section"，取值 operation:<operationId> / section:<seqNo>）。
--    再加两列外键就是同一个概念的第二套表达，两边漂移时无从仲裁。
-- 2. 它对两种场景都成立：抽点用 operation:createUser / section:3，
--    用例生成用 testpoint:<id>（此时 test_point_id 才是外键真源，unit_ref 只做索引）。
-- 3. 重解析后 operation 的自增/雪花 id 会变，但 operationId 字符串与 seq_no 是稳定的，
--    按 unit_ref 重新对上单元比按外键更耐受重解析。
--
-- ## 为什么可空 + 唯一索引
-- 取 NULL 而不是 ''：MySQL 的唯一索引把多个 NULL 视为互不冲突（与 V1 的
-- uk_gen_task_item_task_point 对 test_point_id 的处理一致），于是"用例生成场景的
-- item 不填 unit_ref"不会互相撞键。写 '' 的话同一任务下第二条就 1062 了。
-- =============================================================================
ALTER TABLE gen_task_item
    ADD COLUMN unit_ref VARCHAR(64) DEFAULT NULL
        COMMENT '待分析单元定位：operation:<operationId> / section:<seqNo> / testpoint:<id>；用例生成场景可为 NULL' AFTER test_point_id,
    ADD COLUMN kept_hashes TEXT DEFAULT NULL
        COMMENT '本单元本轮产出的去重键（content_hash，逗号分隔）；任务收尾时汇总成 kept 交给 supersedeMissing（D-26）' AFTER unit_ref,
    ADD UNIQUE KEY uk_gen_task_item_task_unit (task_id, unit_ref);
