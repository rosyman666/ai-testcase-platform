-- =============================================================================
-- V1__schema.sql —— 15 张业务表 DDL
--
-- 全局约定（与架构文档 §3 / §8 严格一致）：
--   1. 主键 BIGINT，无自增，由 MyBatis-Plus 雪花算法 ASSIGN_ID 生成；
--   2. 枚举一律存 VARCHAR（不存 ordinal，避免枚举顺序变更导致历史数据错乱）；
--   3. 金额一律存 BIGINT 微元（cost_micro：1 元 = 1_000_000 微元），避免浮点误差；
--   4. 时间字段 datetime(3)，库内统一存 UTC，由应用层（MetaObjectHandler）自动填充；
--   5. 2 张字典表（reject_reason / failure_category）在 V2 中创建并灌种子数据，
--      指向字典的列（reject_reason_code / failure_category）因此以逻辑关联 + 索引表达。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. app_user：应用用户（单用户模式，仅用于登录；主表预留 created_by）
-- -----------------------------------------------------------------------------
CREATE TABLE app_user (
    id            BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    username      VARCHAR(64)  NOT NULL COMMENT '登录名',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希（不存明文）',
    display_name  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '显示名',
    enabled       TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 0 禁用',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='应用用户';

-- -----------------------------------------------------------------------------
-- 2. doc_source：需求文档主表
--    file_hash 用于重复上传秒回（同内容直接复用解析结果，省一次 LLM 费用）
--    deleted 为软删标记（DELETE /api/docs/{id} 走软删），由 MyBatis-Plus 逻辑删除接管
-- -----------------------------------------------------------------------------
CREATE TABLE doc_source (
    id              BIGINT        NOT NULL COMMENT '主键（雪花 ID）',
    doc_no          VARCHAR(32)   NOT NULL COMMENT '业务单号：D+yyyyMMdd+6位序号',
    name            VARCHAR(255)  NOT NULL COMMENT '原始文件名',
    doc_type        VARCHAR(20)   NOT NULL COMMENT '文档类型：MD/TXT/DOCX/OPENAPI_JSON',
    file_size       BIGINT        NOT NULL DEFAULT 0 COMMENT '文件字节数',
    file_hash       VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '内容 SHA-256（前 32 位），用于秒回与去重',
    storage_path    VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '落盘相对路径',
    parse_status    VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '解析状态：PENDING/PARSED/FAILED',
    parse_error     VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '解析失败原因（面向用户的中文，不是堆栈）',
    section_count   INT           NOT NULL DEFAULT 0 COMMENT '切分出的章节数',
    operation_count INT           NOT NULL DEFAULT 0 COMMENT '切分出的接口操作数',
    created_by      BIGINT                 DEFAULT NULL COMMENT '上传人，关联 app_user.id',
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '软删标记：0 正常 1 已删除',
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_source_doc_no (doc_no),
    KEY idx_doc_source_created_at (created_at),
    KEY idx_doc_source_file_hash (file_hash),
    KEY idx_doc_source_parse_status (parse_status),
    CONSTRAINT fk_doc_source_created_by FOREIGN KEY (created_by) REFERENCES app_user (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='需求文档（解析入口）';

-- -----------------------------------------------------------------------------
-- 3. doc_section：纯文本文档（MD/TXT/DOCX）的章节切分结果
--    这是"两级切分"的第一级：文档 → 章节；章节再作为一次 LLM 调用的上下文单位
-- -----------------------------------------------------------------------------
CREATE TABLE doc_section (
    id         BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    doc_id     BIGINT       NOT NULL COMMENT '所属文档，关联 doc_source.id',
    heading    VARCHAR(500) NOT NULL DEFAULT '' COMMENT '章节标题（保留原始层级文本）',
    level      INT          NOT NULL DEFAULT 1 COMMENT '标题层级（1=一级标题）',
    seq_no     INT          NOT NULL DEFAULT 0 COMMENT '同一文档内的顺序号（从 1 开始）',
    content    MEDIUMTEXT COMMENT '章节正文（纯文本，超长章节在 service 层二次切块）',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_doc_section_doc_seq (doc_id, seq_no),
    CONSTRAINT fk_doc_section_doc FOREIGN KEY (doc_id) REFERENCES doc_source (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='文档章节（切分单位一）';

-- -----------------------------------------------------------------------------
-- 4. api_operation：OpenAPI 文档的接口操作
--    (doc_id, method, path) 唯一确定一个接口，这是"按接口维度切分调用"的物理载体
-- -----------------------------------------------------------------------------
CREATE TABLE api_operation (
    id              BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    doc_id          BIGINT       NOT NULL COMMENT '所属文档，关联 doc_source.id',
    method          VARCHAR(10)  NOT NULL COMMENT 'HTTP 方法（大写）：GET/POST/PUT/PATCH/DELETE...',
    path            VARCHAR(500) NOT NULL COMMENT '接口路径（OpenAPI paths 的 key）',
    operation_id    VARCHAR(200) NOT NULL DEFAULT '' COMMENT 'OpenAPI operationId',
    summary         VARCHAR(500) NOT NULL DEFAULT '' COMMENT '接口摘要',
    tags            VARCHAR(500) NOT NULL DEFAULT '' COMMENT 'OpenAPI tags，逗号分隔',
    request_schema  MEDIUMTEXT COMMENT '请求体 schema（已裁剪的 JSON 片段，$ref 浅解析 ≤3 层）',
    response_schema MEDIUMTEXT COMMENT '响应体 schema（已裁剪）',
    param_schema    MEDIUMTEXT COMMENT 'path/query/header 参数 schema（已裁剪）',
    seq_no          INT          NOT NULL DEFAULT 0 COMMENT '同一文档内的顺序号（从 1 开始）',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_operation_doc_method_path (doc_id, method, path),
    KEY idx_api_operation_doc_seq (doc_id, seq_no),
    CONSTRAINT fk_api_operation_doc FOREIGN KEY (doc_id) REFERENCES doc_source (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='接口操作（切分单位二）';

-- -----------------------------------------------------------------------------
-- 5. test_point：测试点（结构化上下文的终点、用例生成的原点）
--    content_hash = sha256(doc_id + title + category)，保证重复抽取幂等
-- -----------------------------------------------------------------------------
CREATE TABLE test_point (
    id             BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    doc_id         BIGINT       NOT NULL COMMENT '所属文档，关联 doc_source.id',
    operation_id   BIGINT                DEFAULT NULL COMMENT '来源接口（OpenAPI 文档），关联 api_operation.id',
    section_id     BIGINT                DEFAULT NULL COMMENT '来源章节（纯文本文档），关联 doc_section.id',
    title          VARCHAR(500) NOT NULL COMMENT '测试点标题',
    category       VARCHAR(32)  NOT NULL COMMENT '分类：FUNCTIONAL/BOUNDARY/EXCEPTION/SECURITY/PERFORMANCE/COMPATIBILITY',
    risk_level     VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM' COMMENT '风险等级：HIGH/MEDIUM/LOW',
    description    MEDIUMTEXT COMMENT '测试点描述（要验证什么、为什么值得验证）',
    source_ref     VARCHAR(200) NOT NULL DEFAULT '' COMMENT '来源定位：可反查回 operation（METHOD /path）或 section（heading）',
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED/SUPERSEDED',
    prompt_version VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '抽取所用的提示词版本，便于回溯模板质量',
    content_hash   VARCHAR(64)  NOT NULL COMMENT 'sha256(doc_id + title + category)，幂等去重键',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_test_point_doc_hash (doc_id, content_hash),
    KEY idx_test_point_doc_status (doc_id, status),
    KEY idx_test_point_operation (operation_id),
    KEY idx_test_point_section (section_id),
    CONSTRAINT fk_test_point_doc FOREIGN KEY (doc_id) REFERENCES doc_source (id) ON DELETE CASCADE,
    CONSTRAINT fk_test_point_operation FOREIGN KEY (operation_id) REFERENCES api_operation (id) ON DELETE SET NULL,
    CONSTRAINT fk_test_point_section FOREIGN KEY (section_id) REFERENCES doc_section (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='测试点';

-- -----------------------------------------------------------------------------
-- 6. prompt_template：提示词模板（版本化，is_active=1 的唯一版本生效）
-- -----------------------------------------------------------------------------
CREATE TABLE prompt_template (
    id            BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    code          VARCHAR(64)  NOT NULL COMMENT '模板编码：testpoint_extract/case_generate/case_repair/failure_triage',
    version       INT          NOT NULL DEFAULT 1 COMMENT '版本号（同 code 自增）',
    scene         VARCHAR(32)  NOT NULL COMMENT '场景：TESTPOINT_EXTRACT/CASE_GENERATE/CASE_REPAIR/FAILURE_TRIAGE',
    name          VARCHAR(200) NOT NULL DEFAULT '' COMMENT '模板名称（人读）',
    content       MEDIUMTEXT COMMENT '模板正文（含 {{var}} 占位符）',
    output_schema VARCHAR(255) NOT NULL DEFAULT '' COMMENT '输出 schema 路径：classpath:schema/xxx.schema.json',
    model_hint    VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '推荐模型（非强制）',
    is_active     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否生效：1 生效（同 code 唯一）0 历史版本',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_prompt_template_code_version (code, version),
    KEY idx_prompt_template_code_active (code, is_active),
    KEY idx_prompt_template_scene (scene)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='提示词模板（版本化）';

-- -----------------------------------------------------------------------------
-- 7. gen_task：异步任务主表
--    scene 区分 TESTPOINT_EXTRACT / CASE_GENERATE，两类任务复用同一套状态机与调度器
--    progress 为冗余字段（0-100），避免列表页聚合子表
-- -----------------------------------------------------------------------------
CREATE TABLE gen_task (
    id             BIGINT        NOT NULL COMMENT '主键（雪花 ID）',
    task_no        VARCHAR(32)   NOT NULL COMMENT '业务单号：T+yyyyMMdd+6位序号',
    scene          VARCHAR(32)   NOT NULL COMMENT '场景：TESTPOINT_EXTRACT/CASE_GENERATE',
    doc_id         BIGINT                 DEFAULT NULL COMMENT '关联文档，关联 doc_source.id',
    template_id    BIGINT                 DEFAULT NULL COMMENT '所用模板，关联 prompt_template.id',
    provider       VARCHAR(32)   NOT NULL DEFAULT 'mock' COMMENT '模型供应商：mock/openai-compatible',
    model          VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '模型名',
    status         VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/RUNNING/SUCCESS/PARTIAL/FAILED/CANCELED',
    total_items    INT           NOT NULL DEFAULT 0 COMMENT 'item 总数',
    done_items     INT           NOT NULL DEFAULT 0 COMMENT '已完成 item 数（含成功与失败）',
    success_items  INT           NOT NULL DEFAULT 0 COMMENT '成功 item 数',
    failed_items   INT           NOT NULL DEFAULT 0 COMMENT '失败 item 数',
    accepted_cases INT           NOT NULL DEFAULT 0 COMMENT '本任务已采纳用例数（评审后回写）',
    progress       INT           NOT NULL DEFAULT 0 COMMENT '进度百分比 0-100（冗余字段，供列表页直读）',
    retry_round    INT           NOT NULL DEFAULT 0 COMMENT '已重试轮次',
    error_code     VARCHAR(32)   NOT NULL DEFAULT '' COMMENT '任务级错误码（如 E_TASK_DEADLINE）',
    error_msg      VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '任务级错误说明',
    deadline_at    DATETIME(3)            DEFAULT NULL COMMENT '任务级超时时间（默认创建后 30 分钟），防僵尸任务',
    started_at     DATETIME(3)            DEFAULT NULL COMMENT '开始执行时间（UTC）',
    finished_at    DATETIME(3)            DEFAULT NULL COMMENT '进入终态时间（UTC）',
    created_by     BIGINT                 DEFAULT NULL COMMENT '创建人，关联 app_user.id',
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_gen_task_task_no (task_no),
    KEY idx_gen_task_status_created (status, created_at),
    KEY idx_gen_task_scene_status (scene, status),
    KEY idx_gen_task_doc (doc_id),
    CONSTRAINT fk_gen_task_doc FOREIGN KEY (doc_id) REFERENCES doc_source (id) ON DELETE SET NULL,
    CONSTRAINT fk_gen_task_template FOREIGN KEY (template_id) REFERENCES prompt_template (id) ON DELETE SET NULL,
    CONSTRAINT fk_gen_task_created_by FOREIGN KEY (created_by) REFERENCES app_user (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='异步任务（抽点/生成共用状态机）';

-- -----------------------------------------------------------------------------
-- 8. gen_task_item：任务子项（一个测试点 / 一个接口操作 = 一个 item）
--    idx(status, next_run_at) 是"数据库当队列"的认领索引，配合 FOR UPDATE SKIP LOCKED：
--      SELECT id FROM gen_task_item WHERE status='PENDING' AND next_run_at<=NOW()
--      ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
-- -----------------------------------------------------------------------------
CREATE TABLE gen_task_item (
    id                BIGINT        NOT NULL COMMENT '主键（雪花 ID）',
    task_id           BIGINT        NOT NULL COMMENT '所属任务，关联 gen_task.id',
    test_point_id     BIGINT                 DEFAULT NULL COMMENT '关联测试点，关联 test_point.id（抽点场景为 NULL）',
    status            VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/RUNNING/SUCCESS/FAILED/SKIPPED',
    attempts          INT           NOT NULL DEFAULT 0 COMMENT '已尝试次数（上限 3）',
    case_count        INT           NOT NULL DEFAULT 0 COMMENT '本条 item 产出的用例数',
    degraded          TINYINT       NOT NULL DEFAULT 0 COMMENT '是否走了宽松解析降级：1 是（用例置信度 0.5）',
    error_code        VARCHAR(32)   NOT NULL DEFAULT '' COMMENT '错误码',
    error_msg         VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '错误说明（面向用户的中文）',
    prompt_tokens     INT           NOT NULL DEFAULT 0 COMMENT '累计 prompt token',
    completion_tokens INT           NOT NULL DEFAULT 0 COMMENT '累计 completion token',
    cost_micro        BIGINT        NOT NULL DEFAULT 0 COMMENT '累计成本（微元，1 元 = 1_000_000）',
    latency_ms        INT           NOT NULL DEFAULT 0 COMMENT '耗时（毫秒）',
    next_run_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次可被认领时间（失败退避）',
    worker_id         VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '认领该 item 的 worker 标识（排障用）',
    started_at        DATETIME(3)            DEFAULT NULL COMMENT '开始执行时间（UTC）',
    finished_at       DATETIME(3)            DEFAULT NULL COMMENT '进入终态时间（UTC）',
    created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_gen_task_item_task_point (task_id, test_point_id),
    KEY idx_gen_task_item_claim (status, next_run_at),
    KEY idx_gen_task_item_task_status (task_id, status),
    CONSTRAINT fk_gen_task_item_task FOREIGN KEY (task_id) REFERENCES gen_task (id) ON DELETE CASCADE,
    CONSTRAINT fk_gen_task_item_test_point FOREIGN KEY (test_point_id) REFERENCES test_point (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='任务子项（队列消费单位）';

-- -----------------------------------------------------------------------------
-- 9. test_case：测试用例
--    status = DRAFT/ACCEPTED/EDITED/REJECTED；指标分母只认后三者（不含 DRAFT）
-- -----------------------------------------------------------------------------
CREATE TABLE test_case (
    id             BIGINT        NOT NULL COMMENT '主键（雪花 ID）',
    task_id        BIGINT        NOT NULL COMMENT '所属任务，关联 gen_task.id',
    task_item_id   BIGINT        NOT NULL COMMENT '所属任务子项，关联 gen_task_item.id',
    test_point_id  BIGINT                 DEFAULT NULL COMMENT '关联测试点，关联 test_point.id',
    doc_id         BIGINT                 DEFAULT NULL COMMENT '关联文档，关联 doc_source.id',
    seq_no         INT           NOT NULL COMMENT '同一 item 内的用例序号（从 1 开始）',
    title          VARCHAR(500)  NOT NULL COMMENT '用例标题',
    precondition   MEDIUMTEXT COMMENT '前置条件（纯文本）',
    steps          MEDIUMTEXT COMMENT '测试步骤（JSON 数组字符串：[{"no":1,"action":"...","data":"..."}]）',
    expected       MEDIUMTEXT COMMENT '预期结果',
    priority       VARCHAR(16)   NOT NULL DEFAULT 'P2' COMMENT '优先级：P0/P1/P2/P3',
    case_type      VARCHAR(32)   NOT NULL DEFAULT 'FUNCTIONAL' COMMENT '用例类型：FUNCTIONAL/BOUNDARY/EXCEPTION/SECURITY/PERFORMANCE/COMPATIBILITY',
    status         VARCHAR(20)   NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/ACCEPTED/EDITED/REJECTED',
    confidence     DECIMAL(4, 3)          DEFAULT NULL COMMENT '模型自评置信度 0.000-1.000（降级解析固定 0.500）',
    prompt_version VARCHAR(32)   NOT NULL DEFAULT '' COMMENT '生成时所用模板版本',
    model          VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '生成时所用模型',
    content_hash   VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '内容哈希，用于同一 item 内查重',
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_test_case_item_seq (task_item_id, seq_no),
    KEY idx_test_case_task_status (task_id, status),
    KEY idx_test_case_test_point (test_point_id),
    KEY idx_test_case_doc (doc_id),
    KEY idx_test_case_content_hash (content_hash),
    CONSTRAINT fk_test_case_task FOREIGN KEY (task_id) REFERENCES gen_task (id) ON DELETE CASCADE,
    CONSTRAINT fk_test_case_task_item FOREIGN KEY (task_item_id) REFERENCES gen_task_item (id) ON DELETE CASCADE,
    CONSTRAINT fk_test_case_test_point FOREIGN KEY (test_point_id) REFERENCES test_point (id) ON DELETE SET NULL,
    CONSTRAINT fk_test_case_doc FOREIGN KEY (doc_id) REFERENCES doc_source (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='测试用例';

-- -----------------------------------------------------------------------------
-- 10. case_review：评审记录
--     只追加不更新，是标注数据的唯一真源；用例当前状态 = 最后一条 review 的投影
--     reject_reason_code 的外键在 V2 建完字典表后补加
-- -----------------------------------------------------------------------------
CREATE TABLE case_review (
    id                 BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    case_id            BIGINT       NOT NULL COMMENT '关联用例，关联 test_case.id',
    action             VARCHAR(16)  NOT NULL COMMENT '评审动作：ACCEPT/EDIT/REJECT',
    reject_reason_code VARCHAR(32)           DEFAULT NULL COMMENT '驳回原因字典码，关联 reject_reason.code（驳回时必填）',
    reason_text        VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '自由文本补充说明（永不参与聚合）',
    old_content        MEDIUMTEXT COMMENT '编辑前内容快照（JSON），仅 EDIT 时有值',
    new_content        MEDIUMTEXT COMMENT '编辑后内容快照（JSON），仅 EDIT 时有值',
    reviewer_id        BIGINT                DEFAULT NULL COMMENT '评审人，关联 app_user.id',
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_case_review_case_created (case_id, created_at),
    KEY idx_case_review_reject_reason (reject_reason_code),
    CONSTRAINT fk_case_review_case FOREIGN KEY (case_id) REFERENCES test_case (id) ON DELETE CASCADE,
    CONSTRAINT fk_case_review_reviewer FOREIGN KEY (reviewer_id) REFERENCES app_user (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用例评审记录（标注真源，只追加）';

-- -----------------------------------------------------------------------------
-- 11. llm_call_log：模型调用台账（每次 HTTP 调用一行，含失败）
--     失败也记账：上游 500 时 tokens 可能已消耗，不记成本会失真
--     task 被删除时把外键置空，保证成本历史不随业务数据消失
-- -----------------------------------------------------------------------------
CREATE TABLE llm_call_log (
    id                BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    task_id           BIGINT                DEFAULT NULL COMMENT '关联任务，关联 gen_task.id',
    task_item_id      BIGINT                DEFAULT NULL COMMENT '关联任务子项，关联 gen_task_item.id',
    trace_id          VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '链路 traceId，与响应头 X-Trace-Id 一致',
    scene             VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '调用场景：TESTPOINT_EXTRACT/CASE_GENERATE/CASE_REPAIR/FAILURE_TRIAGE',
    provider          VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '模型供应商',
    model             VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '模型名',
    prompt_tokens     INT          NOT NULL DEFAULT 0 COMMENT 'prompt token 数',
    completion_tokens INT          NOT NULL DEFAULT 0 COMMENT 'completion token 数',
    cost_micro        BIGINT       NOT NULL DEFAULT 0 COMMENT '本次调用成本（微元）',
    latency_ms        INT          NOT NULL DEFAULT 0 COMMENT '耗时（毫秒）',
    attempt_no        INT          NOT NULL DEFAULT 1 COMMENT '第几次尝试（修复式重试会 >1）',
    status            VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT '调用结果：SUCCESS/FAILED',
    error_code        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '失败错误码，如 E_LLM_PARSE_EXHAUSTED',
    degraded          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否降级调用：1 是',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_llm_call_log_task (task_id),
    KEY idx_llm_call_log_created_at (created_at),
    KEY idx_llm_call_log_model_created (model, created_at),
    KEY idx_llm_call_log_trace (trace_id),
    CONSTRAINT fk_llm_call_log_task FOREIGN KEY (task_id) REFERENCES gen_task (id) ON DELETE SET NULL,
    CONSTRAINT fk_llm_call_log_task_item FOREIGN KEY (task_item_id) REFERENCES gen_task_item (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='模型调用台账（成本与质量真源）';

-- -----------------------------------------------------------------------------
-- 12. model_price：模型单价表（每 1M token 的微元数）
--     单价可变 → 用生效日期做版本，历史成本不因调价而失真
-- -----------------------------------------------------------------------------
CREATE TABLE model_price (
    id                 BIGINT      NOT NULL COMMENT '主键（雪花 ID）',
    model              VARCHAR(64) NOT NULL COMMENT '模型名（与 llm_call_log.model 对应）',
    input_price_micro  INT         NOT NULL DEFAULT 0 COMMENT '输入单价：每 1M token 的微元数',
    output_price_micro INT         NOT NULL DEFAULT 0 COMMENT '输出单价：每 1M token 的微元数',
    currency           VARCHAR(8)  NOT NULL DEFAULT 'CNY' COMMENT '币种',
    effective_from     DATE        NOT NULL COMMENT '生效日期',
    enabled            TINYINT     NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 0 停用',
    created_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_price_model_from (model, effective_from),
    KEY idx_model_price_model_enabled (model, enabled)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='模型单价（按生效日期版本化）';

-- -----------------------------------------------------------------------------
-- 13. execution_run：执行批次（一次导入 = 一批）
-- -----------------------------------------------------------------------------
CREATE TABLE execution_run (
    id            BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    run_no        VARCHAR(32)  NOT NULL COMMENT '业务单号：R+yyyyMMdd+6位序号',
    name          VARCHAR(200) NOT NULL DEFAULT '' COMMENT '批次名称',
    source_type   VARCHAR(16)  NOT NULL COMMENT '结果来源格式：JUNIT_XML/CSV',
    file_name     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '原始文件名',
    total_count   INT          NOT NULL DEFAULT 0 COMMENT '结果总数',
    passed_count  INT          NOT NULL DEFAULT 0 COMMENT '通过数',
    failed_count  INT          NOT NULL DEFAULT 0 COMMENT '失败数',
    skipped_count INT          NOT NULL DEFAULT 0 COMMENT '跳过数',
    mapped_count  INT          NOT NULL DEFAULT 0 COMMENT '成功映射到用例的结果数（映射率分子）',
    duration_ms   BIGINT       NOT NULL DEFAULT 0 COMMENT '批次总耗时（毫秒）',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_execution_run_run_no (run_no),
    KEY idx_execution_run_created_at (created_at),
    KEY idx_execution_run_source_type (source_type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='执行批次';

-- -----------------------------------------------------------------------------
-- 14. execution_result：单条执行结果
--     case_id 允许 NULL（映射不上就空着，逼着暴露映射率）
--     failure_category 的外键在 V2 建完字典表后补加
-- -----------------------------------------------------------------------------
CREATE TABLE execution_result (
    id               BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    run_id           BIGINT       NOT NULL COMMENT '所属批次，关联 execution_run.id',
    class_name       VARCHAR(500) NOT NULL DEFAULT '' COMMENT '测试类全名',
    case_name        VARCHAR(500) NOT NULL DEFAULT '' COMMENT '测试方法名',
    status           VARCHAR(16)  NOT NULL COMMENT '结果：PASSED/FAILED/SKIPPED',
    duration_ms      BIGINT       NOT NULL DEFAULT 0 COMMENT '单条耗时（毫秒）',
    failure_message  MEDIUMTEXT COMMENT '失败断言消息（Excel/页面展示用，页面只截前 500 字）',
    stack_trace      MEDIUMTEXT COMMENT '堆栈',
    case_id          BIGINT                DEFAULT NULL COMMENT '映射到的用例，关联 test_case.id',
    failure_category VARCHAR(32)           DEFAULT NULL COMMENT '失败分类字典码，关联 failure_category.code',
    triage_source    VARCHAR(16)  NOT NULL DEFAULT 'RULE' COMMENT '归因来源：RULE/LLM/MANUAL',
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_execution_result_run_status (run_id, status),
    KEY idx_execution_result_run_category (run_id, failure_category),
    KEY idx_execution_result_case (case_id),
    CONSTRAINT fk_execution_result_run FOREIGN KEY (run_id) REFERENCES execution_run (id) ON DELETE CASCADE,
    CONSTRAINT fk_execution_result_case FOREIGN KEY (case_id) REFERENCES test_case (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='执行结果明细';

-- -----------------------------------------------------------------------------
-- 15. metric_snapshot：指标物化快照
--     MVP 不物化（全部实时聚合），表先建好但不写入；后续接大流量时由 @Scheduled 落数据
-- -----------------------------------------------------------------------------
CREATE TABLE metric_snapshot (
    id                BIGINT      NOT NULL COMMENT '主键（雪花 ID）',
    stat_date         DATE        NOT NULL COMMENT '统计日期（按用户本地日期 Asia/Shanghai）',
    doc_id            BIGINT               DEFAULT NULL COMMENT '关联文档（可空：不按文档分组时为 NULL）',
    task_id           BIGINT               DEFAULT NULL COMMENT '关联任务（可空：不按任务分组时为 NULL）',
    generated_cases   INT         NOT NULL DEFAULT 0 COMMENT '生成用例数（含 DRAFT）',
    accepted_cases    INT         NOT NULL DEFAULT 0 COMMENT '采纳数（ACCEPTED）',
    edited_cases      INT         NOT NULL DEFAULT 0 COMMENT '编辑后采纳数（EDITED）',
    rejected_cases    INT         NOT NULL DEFAULT 0 COMMENT '驳回数（REJECTED）',
    total_cost_micro  BIGINT      NOT NULL DEFAULT 0 COMMENT '总成本（微元）',
    avg_latency_ms    INT         NOT NULL DEFAULT 0 COMMENT '平均生成耗时（毫秒）',
    created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_metric_snapshot_stat_date (stat_date),
    KEY idx_metric_snapshot_task (task_id),
    KEY idx_metric_snapshot_doc (doc_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='指标物化快照（MVP 只建表不写入）';
