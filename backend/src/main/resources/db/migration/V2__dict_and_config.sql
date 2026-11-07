-- =============================================================================
-- V2__dict_and_config.sql —— 2 张字典表 + 字典种子数据 + 配置类种子
--
-- 为什么要字典表（而不是前端硬编码枚举）：
--   1. 驳回原因 / 失败分类都是**统计维度**，必须归一化成 code，自由文本永不进 GROUP BY；
--   2. 字典可运营（停用、排序、改文案）而不发版；
--   3. 历史标注数据不因文案变更而失真（code 是主键，label 只是展示）。
--
-- 依赖顺序：本脚本必须先于 case_review / execution_result 的外键约束，
-- 因此这两个指向字典的外键在 V1 中留空，在此补加。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. reject_reason：驳回原因字典（case_review.reject_reason_code 的取值来源）
-- -----------------------------------------------------------------------------
CREATE TABLE reject_reason (
    code     VARCHAR(32) NOT NULL COMMENT '字典码（英文大写下划线，主键，统计只按它聚合）',
    label    VARCHAR(64) NOT NULL COMMENT '中文文案（仅用于展示，可改）',
    category VARCHAR(32) NOT NULL DEFAULT 'OTHER' COMMENT '归并大类：CONTENT/ALIGNMENT/COVERAGE/DATA/FORMAT/OTHER',
    sort_no  INT         NOT NULL DEFAULT 0 COMMENT '排序号（升序）',
    enabled  TINYINT     NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用（前端可选）0 停用（历史数据仍可读）',
    PRIMARY KEY (code),
    KEY idx_reject_reason_enabled_sort (enabled, sort_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='驳回原因字典';

-- -----------------------------------------------------------------------------
-- 2. failure_category：失败分类字典（execution_result.failure_category 的取值来源）
-- -----------------------------------------------------------------------------
CREATE TABLE failure_category (
    code     VARCHAR(32) NOT NULL COMMENT '字典码（主键，统计只按它聚合）',
    label    VARCHAR(64) NOT NULL COMMENT '中文文案',
    severity VARCHAR(16) NOT NULL DEFAULT 'MEDIUM' COMMENT '严重度：HIGH/MEDIUM/LOW（用于看板排序与告警）',
    sort_no  INT         NOT NULL DEFAULT 0 COMMENT '排序号（升序）',
    enabled  TINYINT     NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 0 停用',
    PRIMARY KEY (code),
    KEY idx_failure_category_enabled_sort (enabled, sort_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='失败分类字典';

-- -----------------------------------------------------------------------------
-- 3. 补加 V1 中延后的两个字典外键
--    ON DELETE SET NULL：字典项被删除时保留业务记录（标注数据不能因字典调整而丢失）
-- -----------------------------------------------------------------------------
ALTER TABLE case_review
    ADD CONSTRAINT fk_case_review_reject_reason
        FOREIGN KEY (reject_reason_code) REFERENCES reject_reason (code) ON DELETE SET NULL;

ALTER TABLE execution_result
    ADD CONSTRAINT fk_execution_result_failure_category
        FOREIGN KEY (failure_category) REFERENCES failure_category (code) ON DELETE SET NULL;

-- -----------------------------------------------------------------------------
-- 4. 驳回原因种子数据
--    口径见架构文档 §8.3：前端只能从字典选，统计 GROUP BY code
-- -----------------------------------------------------------------------------
INSERT INTO reject_reason (code, label, category, sort_no, enabled)
VALUES ('EXPECT_UNCLEAR', '预期结果不明确', 'CONTENT', 10, 1),
       ('EXPECT_WRONG', '预期结果错误', 'CONTENT', 20, 1),
       ('STEP_INCOMPLETE', '步骤不完整/不可执行', 'CONTENT', 30, 1),
       ('PRECONDITION_MISSING', '前置条件缺失', 'CONTENT', 40, 1),
       ('NOT_ALIGNED', '与需求不符', 'ALIGNMENT', 50, 1),
       ('SCENE_MISSING', '场景覆盖不足', 'COVERAGE', 60, 1),
       ('DUPLICATE', '与其他用例重复', 'COVERAGE', 70, 1),
       ('OUT_OF_SCOPE', '超出本次需求范围', 'ALIGNMENT', 80, 1),
       ('DATA_UNREALISTIC', '测试数据不合理', 'DATA', 90, 1),
       ('PRIORITY_WRONG', '优先级不合理', 'FORMAT', 100, 1),
       ('FORMAT_INVALID', '格式或表达不规范', 'FORMAT', 110, 1),
       ('OTHER', '其他原因', 'OTHER', 999, 1);

-- -----------------------------------------------------------------------------
-- 5. 失败分类种子数据
--    规则归因覆盖：TimeoutException/TIMEOUT、AssertionError/ASSERTION、NPE/ClassNotFound/ENV、
--    HTTP 4xx-5xx/DEPENDENCY、数据构造问题/DATA，无法判定归 UNKNOWN
-- -----------------------------------------------------------------------------
INSERT INTO failure_category (code, label, severity, sort_no, enabled)
VALUES ('TIMEOUT', '超时', 'HIGH', 10, 1),
       ('ASSERTION', '断言失败', 'HIGH', 20, 1),
       ('ENV', '环境问题', 'MEDIUM', 30, 1),
       ('DEPENDENCY', '依赖服务异常', 'HIGH', 40, 1),
       ('DATA', '测试数据问题', 'MEDIUM', 50, 1),
       ('UNKNOWN', '未归类', 'LOW', 999, 1);

-- -----------------------------------------------------------------------------
-- 6. 模型单价种子数据（微元 / 每 1M token）
--    1 元 = 1_000_000 微元；mock 单价为 0，保证离线演示时成本看板不出现假数据
-- -----------------------------------------------------------------------------
INSERT INTO model_price (id, model, input_price_micro, output_price_micro, currency, effective_from, enabled)
VALUES (1, 'mock-model', 0, 0, 'CNY', '2024-01-01', 1),
       (2, 'deepseek-chat', 1000000, 2000000, 'CNY', '2024-01-01', 1),
       (3, 'qwen-plus', 800000, 2000000, 'CNY', '2024-01-01', 1),
       (4, 'gpt-4o-mini', 11000000, 44000000, 'CNY', '2024-01-01', 1);
