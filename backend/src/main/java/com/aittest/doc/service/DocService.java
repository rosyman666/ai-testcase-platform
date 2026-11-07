package com.aittest.doc.service;

import com.aittest.auth.AuthContext;
import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.IdGen;
import com.aittest.common.PageQuery;
import com.aittest.common.PageResult;
import com.aittest.common.enums.DocType;
import com.aittest.common.enums.ParseStatus;
import com.aittest.doc.domain.ApiOperation;
import com.aittest.doc.domain.DocSection;
import com.aittest.doc.domain.DocSource;
import com.aittest.doc.dto.ApiOperationVO;
import com.aittest.doc.dto.DocDetailVO;
import com.aittest.doc.dto.DocSectionVO;
import com.aittest.doc.dto.DocSourceVO;
import com.aittest.doc.mapper.ApiOperationMapper;
import com.aittest.doc.mapper.DocSectionMapper;
import com.aittest.doc.mapper.DocSourceMapper;
import com.aittest.doc.service.parser.DocumentParser;
import com.aittest.doc.service.parser.ParserRegistry;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档接入与解析服务（T03 核心）。
 *
 * <p><b>四个必须遵守的约束，改代码前先读</b>：</p>
 * <ol>
 *   <li><b>扩展名白名单判据只能来自 {@link DocType}</b>。这里不写任何逐字面量的
 *       {@code ext.equals(...)} 串判断，否则白名单就有了第二份拷贝，下次加格式必漏一处。
 *       （验收做法：全仓 grep 扩展名字面量，除 {@code DocType} 外应为 0 行。）</li>
 *   <li><b>查重必须走 {@link DocSourceMapper#selectByFileHash}</b>（自定义 SQL，
 *       带 {@code file_hash <> ''}、按 {@code deleted ASC, updated_at DESC} 排序、{@code LIMIT 1}）。
 *       用 {@code LambdaQueryWrapper} 会被 MP 自动注入 {@code deleted = 0}，
 *       于是"删了再传同一文件"永远命中不了旧行（§3.2.2）。</li>
 *   <li><b>子表先物理删再插</b>，且放在一个事务里。反过来会撞
 *       {@code uk_api_operation_doc_method_path}。</li>
 *   <li><b>取号 + 插入不能在同一个大事务里</b>。{@link IdGen#generateWithRetry} 依赖
 *       "撞键后重试"能真正重新执行插入，而 Spring 在事务内抛出 {@code DuplicateKeyException}
 *       后会把该事务标记 rollback-only，同事务重试必然继续失败。因此这里**不给 {@code upload}
 *       加 {@code @Transactional}**：取号与插入走自动提交，只有"替换子表"这一段用
 *       {@link TransactionTemplate} 显式开事务。</li>
 * </ol>
 *
 * <p><b>上传与重解析的失败语义不同</b>（Q3 裁决）：{@code upload} 的意图是"接收并保存文件"，
 * 解析失败只影响文档状态，所以返回 {@code code=0} + {@code parseStatus=FAILED}；
 * {@code reparse} 的意图就是"再解析一次"，失败即请求失败，抛 {@code 42001}。</p>
 *
 * @author 寇豆码
 */
@Service
public class DocService {

    private static final Logger log = LoggerFactory.getLogger(DocService.class);

    /** 内容指纹取 SHA-256 的**前 32 位**（§3.2：够用且省索引空间）。 */
    private static final int HASH_LENGTH = 32;

    /** {@code doc_source.name} 列宽。 */
    private static final int NAME_MAX = 255;

    /** {@code doc_source.parse_error} 列宽。 */
    private static final int PARSE_ERROR_MAX = 1000;

    /** 详情内嵌章节/接口的上限（全量请走分页接口）。 */
    private static final int DETAIL_EMBED_LIMIT = 200;

    /** 落盘根目录（相对系统临时目录）。 */
    private static final String STORAGE_ROOT = "ai-testcase";

    /** 落盘子目录。 */
    private static final String STORAGE_DOCS = "docs";

    /** 兜底的用户可读解析失败文案（不把堆栈/英文异常抛给用户）。 */
    private static final String GENERIC_PARSE_ERROR = "文件解析失败，请检查文件是否损坏后重新上传";

    private final DocSourceMapper docSourceMapper;

    private final DocSectionMapper docSectionMapper;

    private final ApiOperationMapper apiOperationMapper;

    private final ParserRegistry parserRegistry;

    private final IdGen idGen;

    /** 显式事务模板：只用于"删子表 + 批量插入"这一段。 */
    private final TransactionTemplate transactionTemplate;

    /**
     * 构造注入。
     *
     * @param docSourceMapper     文档 Mapper
     * @param docSectionMapper    章节 Mapper
     * @param apiOperationMapper  接口操作 Mapper
     * @param parserRegistry      解析器注册表
     * @param idGen                单号生成器
     * @param transactionManager  事务管理器（用于构造 {@link TransactionTemplate}）
     */
    public DocService(DocSourceMapper docSourceMapper,
            DocSectionMapper docSectionMapper,
            ApiOperationMapper apiOperationMapper,
            ParserRegistry parserRegistry,
            IdGen idGen,
            PlatformTransactionManager transactionManager) {
        this.docSourceMapper = docSourceMapper;
        this.docSectionMapper = docSectionMapper;
        this.apiOperationMapper = apiOperationMapper;
        this.parserRegistry = parserRegistry;
        this.idGen = idGen;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 上传结果（控制器直接返回，字段与 §4.2 第 7 号端点的出参对齐）。 */
    public record UploadResult(
            Long docId,
            String docNo,
            String parseStatus,
            int sectionCount,
            int operationCount,
            boolean reused) {
    }

    /**
     * 上传并解析文档。
     *
     * <p>命中同 hash 的记录时按四态矩阵处理（这是 §3.2.2 的细化，覆盖了原文没写的 PENDING 态）：</p>
     * <table>
     *   <caption>查重命中后的动作</caption>
     *   <tr><th>命中行状态</th><th>动作</th><th>reused</th></tr>
     *   <tr><td>deleted=1（任意状态）</td><td>恢复 deleted=0 + 重新解析，doc_no 不变</td><td>true</td></tr>
     *   <tr><td>deleted=0 + PARSED</td><td>秒回，不解析</td><td>true</td></tr>
     *   <tr><td>deleted=0 + FAILED</td><td>原地重新解析</td><td>true</td></tr>
     *   <tr><td>deleted=0 + PENDING</td><td>不重复解析，直接返回当前状态</td><td>true</td></tr>
     * </table>
     *
     * <p>PENDING 态必须"不动"：两个并发解析会交错执行"先删子表再插入"，
     * 后者会把前者插入的行删掉，症状要到 T05 抽点时才表现为数据缺失。</p>
     *
     * @param file 上传文件
     * @return 上传结果
     */
    public UploadResult upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.E_DOC_UNSUPPORTED, "上传的文件为空，请重新选择");
        }
        String fileName = normalizeFileName(file.getOriginalFilename());
        DocType docType = DocType.ofExtension(extensionOf(fileName));
        if (docType == null) {
            // 42002：判据只来自 DocType 的扩展名白名单，这里不做第二份判断
            throw new BizException(ErrorCode.E_DOC_UNSUPPORTED);
        }
        byte[] bytes = readBytes(file);
        if (bytes.length == 0) {
            throw new BizException(ErrorCode.E_DOC_PARSE_FAILED, "文件内容为空，没有可解析的正文");
        }
        String fileHash = sha256Prefix(bytes);

        DocSource existing = docSourceMapper.selectByFileHash(fileHash);
        if (existing != null) {
            boolean restored = existing.getDeleted() != null && existing.getDeleted() == 1;
            ParseStatus status = parseStatusOf(existing);
            if (!restored && status == ParseStatus.PARSED) {
                log.info("[DOC-UPLOAD] 命中缓存秒回 docId={} docNo={}", existing.getId(), existing.getDocNo());
                return toResult(existing, true);
            }
            if (!restored && status == ParseStatus.PENDING) {
                log.warn("[DOC-UPLOAD] 命中解析中的文档，不重复解析 docId={}", existing.getId());
                return toResult(existing, true);
            }
            if (restored) {
                // §3.2.2 步骤 3：恢复原记录，doc_no 保持不变
                docSourceMapper.restoreById(existing.getId());
                log.info("[DOC-UPLOAD] 恢复软删文档 docId={} docNo={}", existing.getId(), existing.getDocNo());
            }
            return parseAndPersist(existing.getId(), docType, bytes, fileName, true);
        }

        String storagePath = store(bytes, docType);
        Long docId = idGen.generateWithRetry(IdGen.NoType.DOC, docNo -> insertDocSource(docNo, fileName, docType,
                bytes, fileHash, storagePath));
        log.info("[DOC-UPLOAD] 新建文档 docId={} type={} size={}", docId, docType.code(), bytes.length);
        return parseAndPersist(docId, docType, bytes, fileName, false);
    }

    /**
     * 分页查询文档列表。
     *
     * @param query       分页与排序
     * @param keyword     文件名/单号模糊搜索
     * @param docType     文档类型过滤（编码）
     * @param parseStatus 解析状态过滤（编码）
     * @return 分页结果
     */
    public PageResult<DocSourceVO> list(PageQuery query, String keyword, String docType, String parseStatus) {
        LambdaQueryWrapper<DocSource> wrapper = Wrappers.lambdaQuery();
        if (StringUtils.isNotBlank(keyword)) {
            String trimmed = keyword.trim();
            wrapper.and(w -> w.like(DocSource::getName, trimmed).or().like(DocSource::getDocNo, trimmed));
        }
        if (StringUtils.isNotBlank(docType)) {
            String normalized = normalizeEnumFilter(docType, DocType.class);
            // 无法识别的过滤值选择"忽略"而不是"返回空页"：见 normalizeEnumFilter 注释
            if (normalized != null) {
                wrapper.eq(DocSource::getDocType, normalized);
            }
        }
        if (StringUtils.isNotBlank(parseStatus)) {
            String normalized = normalizeEnumFilter(parseStatus, ParseStatus.class);
            if (normalized != null) {
                wrapper.eq(DocSource::getParseStatus, normalized);
            }
        }
        applySort(wrapper, query);

        Page<DocSource> page = new Page<>(query.pageOrDefault(), query.sizeOrDefault());
        IPage<DocSource> result = docSourceMapper.selectPage(page, wrapper);
        List<DocSourceVO> records = result.getRecords().stream().map(DocSourceVO::from).toList();
        return PageResult.of(records, result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    /**
     * 文档详情（含章节树与接口列表的预览）。
     *
     * @param id 文档 ID
     * @return 详情
     */
    public DocDetailVO detail(Long id) {
        DocSource entity = requireDoc(id);
        List<DocSection> sections = docSectionMapper.selectList(
                Wrappers.<DocSection>lambdaQuery()
                        .eq(DocSection::getDocId, id)
                        .orderByAsc(DocSection::getSeqNo)
                        .last("LIMIT " + DETAIL_EMBED_LIMIT));
        List<ApiOperation> operations = apiOperationMapper.selectList(
                Wrappers.<ApiOperation>lambdaQuery()
                        .eq(ApiOperation::getDocId, id)
                        .orderByAsc(ApiOperation::getSeqNo)
                        .last("LIMIT " + DETAIL_EMBED_LIMIT));
        return DocDetailVO.of(entity, sections, operations);
    }

    /**
     * 分页查询章节（正文全文）。
     *
     * @param docId 文档 ID
     * @param query 分页
     * @return 分页结果
     */
    public PageResult<DocSectionVO> sections(Long docId, PageQuery query) {
        requireDoc(docId);
        Page<DocSection> page = new Page<>(query.pageOrDefault(), query.sizeOrDefault());
        IPage<DocSection> result = docSectionMapper.selectPage(page,
                Wrappers.<DocSection>lambdaQuery()
                        .eq(DocSection::getDocId, docId)
                        .orderByAsc(DocSection::getSeqNo));
        return PageResult.of(result.getRecords().stream().map(DocSectionVO::fromFull).toList(),
                result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    /**
     * 分页查询接口操作（**不带** schema 三兄弟，见 §4.4）。
     *
     * @param docId 文档 ID
     * @param query 分页
     * @return 分页结果
     */
    public PageResult<ApiOperationVO> operations(Long docId, PageQuery query) {
        requireDoc(docId);
        Page<ApiOperation> page = new Page<>(query.pageOrDefault(), query.sizeOrDefault());
        IPage<ApiOperation> result = apiOperationMapper.selectPage(page,
                Wrappers.<ApiOperation>lambdaQuery()
                        .eq(ApiOperation::getDocId, docId)
                        .orderBy(true, query.isDesc(), ApiOperation::getSeqNo));
        return PageResult.of(result.getRecords().stream().map(ApiOperationVO::fromList).toList(),
                result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    /**
     * 重新解析（用户主动触发，失败即请求失败 → {@code 42001}）。
     *
     * @param id 文档 ID
     * @return 解析结果
     */
    public UploadResult reparse(Long id) {
        DocSource entity = requireDoc(id);
        DocType docType = DocType.ofCode(entity.getDocType());
        if (docType == null) {
            throw new BizException(ErrorCode.E_DOC_UNSUPPORTED,
                    "该文档的类型 " + entity.getDocType() + " 已不受支持，请重新上传");
        }
        byte[] bytes = readStoredFile(entity);
        try {
            DocumentParser.ParseResult parsed = parserRegistry.parse(docType,
                    bytes, new DocumentParser.ParseContext(id, entity.getName(), docType));
            persistChildren(id, parsed);
            updateParseState(id, ParseStatus.PARSED, "", parsed);
            return new UploadResult(id, entity.getDocNo(), ParseStatus.PARSED.code(),
                    parsed.sections().size(), parsed.operations().size(), true);
        } catch (BizException e) {
            updateParseState(id, ParseStatus.FAILED, e.getMessage(), null);
            log.warn("[DOC-REPARSE] 解析失败 docId={} 原因={}", id, e.getMessage());
            throw new BizException(ErrorCode.E_DOC_PARSE_FAILED, e.getMessage());
        } catch (RuntimeException e) {
            updateParseState(id, ParseStatus.FAILED, GENERIC_PARSE_ERROR, null);
            log.error("[DOC-REPARSE] 解析异常 docId={}", id, e);
            throw new BizException(ErrorCode.E_DOC_PARSE_FAILED, GENERIC_PARSE_ERROR);
        }
    }

    /**
     * 删除文档。
     *
     * <p>默认软删（只置 {@code deleted=1}，可被"重传同一文件"恢复）。{@code force=true} 时
     * 物理删除主表与子表并清理落盘文件——<b>会永久丢号</b>：{@code doc_no} 生成靠"当日最大号 + 1"，
     * 硬删掉最大号后重启播种值会回退，后续可能复用该号。需要保留追溯链时请用软删。</p>
     *
     * @param id    文档 ID
     * @param force 是否物理删除
     */
    public void delete(Long id, boolean force) {
        DocSource entity = requireDoc(id);
        if (force) {
            transactionTemplate.executeWithoutResult(status -> {
                docSectionMapper.deleteByDocId(id);
                apiOperationMapper.deleteByDocId(id);
                docSourceMapper.physicalDeleteById(id);
            });
            deleteStoredFile(entity);
            log.warn("[DOC-DELETE] 物理删除 docId={} docNo={}（该单号可能被复用）", id, entity.getDocNo());
            return;
        }
        docSourceMapper.deleteById(id);
        log.info("[DOC-DELETE] 软删 docId={} docNo={}", id, entity.getDocNo());
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 解析并落库（上传路径专用：失败只改状态，不抛异常）。
     *
     * @param docId    文档 ID
     * @param docType  文档类型
     * @param bytes    文件字节
     * @param fileName 文件名
     * @param reused   是否为复用已有记录
     * @return 结果
     */
    private UploadResult parseAndPersist(Long docId, DocType docType, byte[] bytes, String fileName,
            boolean reused) {
        try {
            DocumentParser.ParseResult parsed = parserRegistry.parse(docType, bytes,
                    new DocumentParser.ParseContext(docId, fileName, docType));
            persistChildren(docId, parsed);
            updateParseState(docId, ParseStatus.PARSED, "", parsed);
            log.info("[DOC-UPLOAD] 解析完成 docId={} sections={} operations={}",
                    docId, parsed.sections().size(), parsed.operations().size());
            return new UploadResult(docId, docNoOf(docId), ParseStatus.PARSED.code(),
                    parsed.sections().size(), parsed.operations().size(), reused);
        } catch (BizException e) {
            // 业务级解析失败（格式不对/内容为空/超长等）：用户可读文案直接进 parse_error
            updateParseState(docId, ParseStatus.FAILED, e.getMessage(), null);
            log.warn("[DOC-UPLOAD] 解析失败 docId={} 原因={}", docId, e.getMessage());
            return new UploadResult(docId, docNoOf(docId), ParseStatus.FAILED.code(), 0, 0, reused);
        } catch (RuntimeException e) {
            // 未预期异常：日志留堆栈，用户只看中文兜底文案
            updateParseState(docId, ParseStatus.FAILED, GENERIC_PARSE_ERROR, null);
            log.error("[DOC-UPLOAD] 解析异常 docId={}", docId, e);
            return new UploadResult(docId, docNoOf(docId), ParseStatus.FAILED.code(), 0, 0, reused);
        }
    }

    /**
     * 在一个事务里"先物理删子表、再插入"，并断言覆盖语义。
     *
     * <p>{@code api_operation} 有 {@code uk(doc_id, method, path)}，顺序反过来必撞键；
     * {@code doc_section} 没有唯一键，但统一按"先删后插"可以避免两张表两套逻辑。</p>
     *
     * @param docId  文档 ID
     * @param parsed 解析结果
     */
    private void persistChildren(Long docId, DocumentParser.ParseResult parsed) {
        transactionTemplate.executeWithoutResult(status -> {
            docSectionMapper.deleteByDocId(docId);
            apiOperationMapper.deleteByDocId(docId);

            for (DocumentParser.SectionDraft draft : parsed.sections()) {
                DocSection entity = new DocSection();
                entity.setDocId(docId);
                entity.setHeading(draft.heading());
                entity.setLevel(draft.level());
                entity.setSeqNo(draft.seqNo());
                entity.setContent(draft.content());
                docSectionMapper.insert(entity);
            }
            for (DocumentParser.OperationDraft draft : parsed.operations()) {
                ApiOperation entity = new ApiOperation();
                entity.setDocId(docId);
                entity.setMethod(draft.method());
                entity.setPath(draft.path());
                entity.setOperationId(draft.operationId());
                entity.setSummary(draft.summary());
                entity.setTags(draft.tags());
                entity.setRequestSchema(draft.requestSchema());
                entity.setResponseSchema(draft.responseSchema());
                entity.setParamSchema(draft.paramSchema());
                entity.setSeqNo(draft.seqNo());
                apiOperationMapper.insert(entity);
            }
        });
    }

    /**
     * 回写解析状态与统计。
     *
     * @param docId  文档 ID
     * @param status 状态
     * @param error  失败原因（成功传空串）
     * @param parsed 解析结果（为 null 时计数归零）
     */
    private void updateParseState(Long docId, ParseStatus status, String error,
            DocumentParser.ParseResult parsed) {
        DocSource patch = new DocSource();
        patch.setId(docId);
        patch.setParseStatus(status.code());
        patch.setParseError(truncate(error == null ? "" : error, PARSE_ERROR_MAX));
        patch.setSectionCount(parsed == null ? 0 : parsed.sections().size());
        patch.setOperationCount(parsed == null ? 0 : parsed.operations().size());
        docSourceMapper.updateById(patch);
    }

    /**
     * 插入文档主表记录（供 {@link IdGen#generateWithRetry} 调用）。
     *
     * <p>这里不设置 {@code createdAt}/{@code updatedAt}：由 {@code AuditMetaObjectHandler} 填充。
     * 也**刻意不加 {@code @Transactional}**——撞键重试必须让每次插入真正提交/回滚。</p>
     *
     * @param docNo       业务单号
     * @param fileName    文件名
     * @param docType     文档类型
     * @param bytes       文件字节
     * @param fileHash    内容指纹
     * @param storagePath 落盘路径
     * @return 新记录 ID
     */
    private Long insertDocSource(String docNo, String fileName, DocType docType, byte[] bytes, String fileHash,
            String storagePath) {
        DocSource entity = new DocSource();
        entity.setDocNo(docNo);
        entity.setName(truncate(fileName, NAME_MAX));
        entity.setDocType(docType.code());
        entity.setFileSize((long) bytes.length);
        entity.setFileHash(fileHash);
        entity.setStoragePath(storagePath);
        entity.setParseStatus(ParseStatus.PENDING.code());
        entity.setParseError("");
        entity.setSectionCount(0);
        entity.setOperationCount(0);
        entity.setCreatedBy(currentUserId());
        docSourceMapper.insert(entity);
        return entity.getId();
    }

    /**
     * 按 ID 取文档，不存在或已删除时抛 40401。
     *
     * @param id 文档 ID
     * @return 文档实体
     */
    private DocSource requireDoc(Long id) {
        DocSource entity = id == null ? null : docSourceMapper.selectById(id);
        if (entity == null) {
            DocSource deleted = id == null ? null : docSourceMapper.selectByIdIgnoreDeleted(id);
            if (deleted != null) {
                throw new BizException(ErrorCode.E_NOT_FOUND, "文档已删除，请重新上传同一文件以恢复");
            }
            throw BizException.notFound("文档");
        }
        return entity;
    }

    /**
     * 读取单号（用于返回体；失败时给空串，不让"查单号失败"掩盖解析结果）。
     *
     * @param docId 文档 ID
     * @return 单号
     */
    private String docNoOf(Long docId) {
        DocSource entity = docSourceMapper.selectById(docId);
        return entity == null || entity.getDocNo() == null ? "" : entity.getDocNo();
    }

    /**
     * 把实体转成返回结果。
     *
     * @param entity 实体
     * @param reused 是否复用
     * @return 结果
     */
    private UploadResult toResult(DocSource entity, boolean reused) {
        return new UploadResult(entity.getId(), entity.getDocNo(), entity.getParseStatus(),
                entity.getSectionCount() == null ? 0 : entity.getSectionCount(),
                entity.getOperationCount() == null ? 0 : entity.getOperationCount(), reused);
    }

    /**
     * 读取上传流。
     *
     * @param file 上传文件
     * @return 字节
     */
    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            // 读上传流失败属基础设施问题（临时目录不可写等），用 50000 而不是 42001
            log.error("[DOC-UPLOAD] 读取上传文件失败 name={}", file.getOriginalFilename(), e);
            throw new BizException(ErrorCode.E_INTERNAL, "文件读取失败，请稍后重试");
        }
    }

    /**
     * 落盘原始文件（便于后续 {@code reparse} 不必让用户重传）。
     *
     * <p>目录取系统临时目录下的 {@code ai-testcase/docs/yyyyMMdd}：不污染工作区、不进 git；
     * 代价是机器重启后可能被清理（此时 {@code reparse} 会提示"原始文件已丢失，请重新上传"）。
     * 正式部署应改用可配置的持久目录，归 T14。</p>
     *
     * @param bytes   文件字节
     * @param docType 文档类型
     * @return 落盘绝对路径
     */
    private String store(byte[] bytes, DocType docType) {
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), STORAGE_ROOT, STORAGE_DOCS,
                    LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE));
            Files.createDirectories(dir);
            Path target = dir.resolve(UUID.randomUUID().toString().replace("-", "") + "." + docType.extension());
            Files.write(target, bytes);
            return target.toAbsolutePath().toString();
        } catch (IOException e) {
            // 磁盘满/权限问题 → 50000，不是"解析失败"
            log.error("[DOC-UPLOAD] 落盘失败", e);
            throw new BizException(ErrorCode.E_INTERNAL, "文件保存失败，请检查服务端磁盘空间或权限");
        }
    }

    /**
     * 读取已落盘的原始文件。
     *
     * @param entity 文档实体
     * @return 字节
     */
    private byte[] readStoredFile(DocSource entity) {
        String path = entity.getStoragePath();
        if (StringUtils.isBlank(path)) {
            throw new BizException(ErrorCode.E_DOC_PARSE_FAILED, "原始文件已丢失，请重新上传该文件后再解析");
        }
        Path file = Path.of(path);
        if (!Files.isReadable(file)) {
            throw new BizException(ErrorCode.E_DOC_PARSE_FAILED, "原始文件已丢失，请重新上传该文件后再解析");
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            log.error("[DOC-REPARSE] 读取原始文件失败 path={}", path, e);
            throw new BizException(ErrorCode.E_INTERNAL, "原始文件读取失败，请稍后重试");
        }
    }

    /**
     * 删除落盘文件（尽力而为，失败只记日志）。
     *
     * @param entity 文档实体
     */
    private void deleteStoredFile(DocSource entity) {
        if (StringUtils.isBlank(entity.getStoragePath())) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(entity.getStoragePath()));
        } catch (IOException e) {
            log.warn("[DOC-DELETE] 删除落盘文件失败 path={}（不影响主流程）", entity.getStoragePath(), e);
        }
    }

    /**
     * 排序：只接受白名单列，其余回落 {@code created_at}。
     *
     * @param wrapper 查询条件
     * @param query   分页入参
     */
    private void applySort(LambdaQueryWrapper<DocSource> wrapper, PageQuery query) {
        String column = query.sortColumnOrDefault("created_at");
        boolean desc = query.isDesc();
        switch (column) {
            case "updated_at" -> wrapper.orderBy(true, desc, DocSource::getUpdatedAt);
            case "doc_no" -> wrapper.orderBy(true, desc, DocSource::getDocNo);
            case "file_size" -> wrapper.orderBy(true, desc, DocSource::getFileSize);
            case "parse_status" -> wrapper.orderBy(true, desc, DocSource::getParseStatus);
            default -> wrapper.orderBy(true, desc, DocSource::getCreatedAt);
        }
        // 同一时间戳（批量插入常见）下保持稳定顺序，便于翻页
        wrapper.orderByDesc(DocSource::getId);
    }

    /**
     * 过滤参数归一化：前端可能传 {@code OPENAPI_JSON} / {@code openapi-json} / {@code openapi_json}。
     *
     * <p>未知取值直接忽略（不报错）——列表过滤是"友好降级"场景，传错一个枚举值
     * 就整页 40001 的体验很差。</p>
     *
     * @param raw   原始值
     * @param clazz 枚举类型
     * @param <E>   枚举类型
     * @return 枚举编码；无法识别返回 null
     */
    private <E extends Enum<E> & com.aittest.common.enums.CodedEnum> String normalizeEnumFilter(String raw,
            Class<E> clazz) {
        String normalized = raw.trim().replace('-', '_');
        for (E constant : clazz.getEnumConstants()) {
            if (constant.code().equalsIgnoreCase(normalized) || constant.name().equalsIgnoreCase(normalized)) {
                return constant.code();
            }
        }
        log.debug("[DOC-LIST] 忽略未知的过滤值 {}={}", clazz.getSimpleName(), raw);
        return null;
    }

    /**
     * 解析状态（未知编码按 PENDING 处理，避免历史脏数据导致 NPE）。
     *
     * @param entity 文档实体
     * @return 状态
     */
    private ParseStatus parseStatusOf(DocSource entity) {
        for (ParseStatus status : ParseStatus.values()) {
            if (status.code().equalsIgnoreCase(entity.getParseStatus())) {
                return status;
            }
        }
        return ParseStatus.PENDING;
    }

    /**
     * 取内容指纹（SHA-256 前 32 位，小写十六进制）。
     *
     * @param bytes 文件字节
     * @return 指纹
     */
    private String sha256Prefix(byte[] bytes) {
        String hex = DigestUtils.sha256Hex(bytes).toLowerCase(Locale.ROOT);
        return hex.length() <= HASH_LENGTH ? hex : hex.substring(0, HASH_LENGTH);
    }

    /**
     * 文件名归一化：去掉浏览器可能带上的路径部分，并截断到列宽。
     *
     * @param original 原始文件名
     * @return 归一化后的文件名
     */
    private String normalizeFileName(String original) {
        if (StringUtils.isBlank(original)) {
            return "unnamed";
        }
        String name = original.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        return truncate(name, NAME_MAX);
    }

    /**
     * 取小写扩展名（不含点；无扩展名返回空串）。
     *
     * @param fileName 文件名
     * @return 扩展名
     */
    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 当前登录用户 ID（未登录/非 Web 线程返回 null，{@code created_by} 允许为空）。
     *
     * @return 用户 ID 或 null
     */
    private Long currentUserId() {
        try {
            return AuthContext.requireUserId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 按字符数截断。
     *
     * @param value 原值
     * @param max   上限
     * @return 截断结果
     */
    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
