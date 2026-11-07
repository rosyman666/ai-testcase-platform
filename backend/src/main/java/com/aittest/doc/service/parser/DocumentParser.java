package com.aittest.doc.service.parser;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.DocType;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文档解析器接口：一种 {@link DocType} 一个实现，由 {@link ParserRegistry} 按类型路由。
 *
 * <p>设计要点：</p>
 * <ol>
 *   <li><b>解析器只做"切分"，不碰数据库</b>。返回纯数据（{@link ParseResult}），
 *       落库由 {@code DocService} 在一个事务里做——这样"先删子表再插入"的顺序约束
 *       只有一处实现，不会散落到 4 个解析器里各写一遍。</li>
 *   <li><b>失败必须抛带中文文案的 {@link BizException}</b>（{@code 42001}）。
 *       {@code DocService} 捕获后写进 {@code parse_error} 供用户看；堆栈只进日志。</li>
 *   <li><b>长度超限在这里拦，不交给数据库</b>。本机 {@code sql_mode} 含
 *       {@code STRICT_TRANS_TABLES}，超长插入会抛 {@code ERROR 1406} 并整行被拒，
 *       最终被兜底处理器映射成 {@code 50000 系统内部错误}——用户看到"系统内部错误"，
 *       实际只是某个接口路径太长。所以校验必须发生在这里（见 D-18/Q4 裁决）。</li>
 * </ol>
 *
 * @author 寇豆码
 */
public interface DocumentParser {

    /** 解析器日志（接口字段隐式 {@code public static final}）。 */
    Logger log = LoggerFactory.getLogger(DocumentParser.class);

    /** {@code api_operation.path} 的列宽上限，超过即整篇解析失败（不跳过该 operation）。 */
    int PATH_MAX = 500;

    /** {@code doc_section.heading} 的列宽上限，同样是硬失败。 */
    int HEADING_MAX = 500;

    /** {@code api_operation.summary} 列宽：展示字段，超长截断 + WARN，不失败。 */
    int SUMMARY_MAX = 500;

    /** {@code api_operation.tags} 列宽：展示字段，同上。 */
    int TAGS_MAX = 500;

    /** {@code api_operation.operation_id} 列宽：同上。 */
    int OPERATION_ID_MAX = 200;

    /** 单个 schema 片段的最大字符数，超过则截断并标记（详情 VO 也据此提示）。 */
    int SCHEMA_MAX = 20_000;

    /** 单章节正文的最大字符数，超过则二次切块（避免单个 section 过大拖慢后续提示词拼装）。 */
    int SECTION_CONTENT_MAX = 8_000;

    /** 纯文本切块的目标长度。 */
    int TEXT_CHUNK_TARGET = 2_000;

    /**
     * 本解析器支持的文档类型。
     *
     * @return 文档类型
     */
    DocType supports();

    /**
     * 解析文件内容。
     *
     * @param bytes 文件字节
     * @param ctx   解析上下文（携带 docId / 文件名，用于日志定位）
     * @return 切分结果（章节或接口操作，另一类为空列表）
     * @throws BizException 解析失败（{@code 42001}）或输入不合规（{@code 42001}，文案为中文）
     */
    ParseResult parse(byte[] bytes, ParseContext ctx);

    /** 解析上下文。 */
    record ParseContext(Long docId, String fileName, DocType docType) {

        /**
         * 便于日志拼接的短描述。
         *
         * @return 形如 {@code docId=123, file=x.md}
         */
        public String brief() {
            return "docId=" + docId + ", file=" + fileName;
        }
    }

    /** 章节草稿。 */
    record SectionDraft(String heading, int level, int seqNo, String content) {
    }

    /** 接口操作草稿。 */
    record OperationDraft(
            String method,
            String path,
            String operationId,
            String summary,
            String tags,
            String requestSchema,
            String responseSchema,
            String paramSchema,
            int seqNo) {
    }

    /** 解析结果：纯文本文档只有 sections，OpenAPI 文档只有 operations。 */
    record ParseResult(List<SectionDraft> sections, List<OperationDraft> operations) {

        /**
         * 只含章节的结果。
         *
         * @param sections 章节
         * @return 解析结果
         */
        public static ParseResult ofSections(List<SectionDraft> sections) {
            return new ParseResult(sections == null ? List.of() : sections, List.of());
        }

        /**
         * 只含接口操作的结果。
         *
         * @param operations 接口操作
         * @return 解析结果
         */
        public static ParseResult ofOperations(List<OperationDraft> operations) {
            return new ParseResult(List.of(), operations == null ? List.of() : operations);
        }

        /**
         * 是否什么都没切出来（用于判"解析成功但没有内容"）。
         *
         * @return 两类都为空返回 true
         */
        public boolean isEmpty() {
            return sections.isEmpty() && operations.isEmpty();
        }
    }

    /**
     * 校验列宽，超限直接判解析失败。
     *
     * <p>为什么是"失败"而不是"截断"：{@code path} 参与唯一键
     * {@code uk_api_operation_doc_method_path}，截断会让两条不同接口撞成一个；
     * 而 {@code ParseStatus} 没有 PARTIAL，跳过某个 operation 又会让"少接口"这件事
     * 静默转嫁给下游（T05 少抽点、指标分母偏小）。所以宁可整篇失败、让用户改文档。</p>
     *
     * @param value     待校验值
     * @param max       列宽上限
     * @param fieldName 字段中文名（进错误文案）
     * @param where     定位信息（如"第 7 个接口"）
     */
    static void requireFitsColumn(String value, int max, String fieldName, String where) {
        if (value != null && value.length() > max) {
            throw new BizException(ErrorCode.E_DOC_PARSE_FAILED,
                    where + "：" + fieldName + " 长度 " + value.length() + " 超过 " + max
                            + " 字符上限，请裁剪后重新上传");
        }
    }

    /**
     * 展示类字段的截断（截断不影响语义，但要留痕，且不能交给数据库决定）。
     *
     * @param value     原值
     * @param max       上限
     * @param fieldName 字段名
     * @param ctx       解析上下文
     * @return 截断后的值
     */
    static String truncateForColumn(String value, int max, String fieldName, ParseContext ctx) {
        if (value == null || value.length() <= max) {
            return value;
        }
        log.warn("[DOCX-COLS] 字段超长已截断：{} field={} 原长={} 上限={}",
                ctx == null ? "-" : ctx.brief(), fieldName, value.length(), max);
        return value.substring(0, max);
    }

    /**
     * 把超长正文按目标长度二次切块，避免单章节过大。
     *
     * @param heading 原始标题
     * @param level   标题层级
     * @param content 正文
     * @param seqFrom 起始顺序号
     * @param out     输出列表
     */
    static void appendChunked(String heading, int level, String content, int seqFrom, List<SectionDraft> out) {
        String safeHeading = heading == null || heading.isBlank() ? "正文" : heading.trim();
        if (content == null) {
            content = "";
        }
        if (content.length() <= SECTION_CONTENT_MAX) {
            out.add(new SectionDraft(safeHeading, level, seqFrom, content));
            return;
        }
        int part = 1;
        int seq = seqFrom;
        for (int from = 0; from < content.length(); from += SECTION_CONTENT_MAX) {
            int to = Math.min(from + SECTION_CONTENT_MAX, content.length());
            out.add(new SectionDraft(safeHeading + "（续 " + part + "）", level, seq++, content.substring(from, to)));
            part++;
        }
    }

    /**
     * 严格 UTF-8 解码，失败时回退 GBK（需求文档常见 GBK 来源），两者都不行才报错。
     *
     * <p>不用 {@code new String(bytes, UTF_8)} 直接解：那样非法字节会被静默替换成
     * {@code U+FFFD}，用户拿到一整篇乱码而且不知道原因。</p>
     *
     * @param bytes 文件字节
     * @param ctx   解析上下文
     * @return 文本
     */
    static String decodeText(byte[] bytes, ParseContext ctx) {
        String utf8 = tryDecode(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return utf8;
        }
        String gbk = tryDecode(bytes, Charset.forName("GBK"));
        if (gbk != null) {
            log.warn("[DOC-ENC] 文件不是 UTF-8，已按 GBK 解码：{}", ctx == null ? "-" : ctx.brief());
            return gbk;
        }
        throw new BizException(ErrorCode.E_DOC_PARSE_FAILED,
                "文件编码无法识别（既不是 UTF-8 也不是 GBK），请另存为 UTF-8 后重新上传");
    }

    /**
     * 按指定字符集严格解码。
     *
     * @param bytes   字节
     * @param charset 字符集
     * @return 解码结果；非法字节返回 null
     */
    private static String tryDecode(byte[] bytes, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * 过滤 CSV/文本里的 BOM 头（Windows 记事本另存的文件几乎都带）。
     *
     * @param text 文本
     * @return 去掉 BOM 的文本
     */
    static String stripBom(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    /**
     * 新建一个可变列表（便于解析器内部累积后返回不可变副本）。
     *
     * @param <T> 元素类型
     * @return 空列表
     */
    static <T> List<T> newList() {
        return new ArrayList<>();
    }
}
