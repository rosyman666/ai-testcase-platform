-- -----------------------------------------------------------------------------
-- V4：修正 doc_source.storage_path 的列注释，使其与实现一致
--
-- 为什么不直接改 V1__schema.sql：
--   Flyway 对已应用的迁移做**逐字节 CRC32 校验**（application.yml 里
--   `validate-on-migrate: true`）。改动 V1 的任何一个字符——包括注释里的一个字——
--   都会让所有已建过库的环境在下次启动时报
--   `Migration checksum mismatch`，应用直接起不来。
--   所以已上线的迁移文件**一个字符都不许改**，修正只能靠新迁移覆盖。
--
-- 为什么只改 COMMENT：
--   纯元数据变更，MySQL 8 走 INSTANT 算法，不重建表、不扫数据。
--   同时它也把"落盘路径到底存什么"这件事写回了表结构本身——
--   下一个读 DDL 的人不会再次被"落盘相对路径"这句旧注释误导成绝对路径。
--
-- 为什么不加 CHECK 约束：
--   `CHECK (storage_path NOT LIKE '%:%' AND storage_path NOT LIKE '/%')` 能挡住
--   以后有人手写绝对路径，但 T03 的历史行（21 行）全是 D:\ 绝对路径，
--   加约束前必须先迁移这些行；而这批是验收测试数据、随后即清空，
--   为它们写一次数据迁移不划算。约束留给"确实需要长期防"的时点（见 §9 退役条件）。
-- -----------------------------------------------------------------------------

ALTER TABLE doc_source
    MODIFY COLUMN storage_path VARCHAR(500) NOT NULL DEFAULT ''
        COMMENT '落盘路径：相对 storage.dir 根，形如 docs/yyyyMMdd/uuid.md；历史行可能为绝对路径（双模式读取）';
