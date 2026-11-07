package com.aittest.doc.service.parser;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.DocType;
import static com.aittest.doc.service.parser.DocumentParser.HEADING_MAX;
import static com.aittest.doc.service.parser.DocumentParser.TEXT_CHUNK_TARGET;
import static com.aittest.doc.service.parser.DocumentParser.appendChunked;
import static com.aittest.doc.service.parser.DocumentParser.decodeText;
import static com.aittest.doc.service.parser.DocumentParser.newList;
import static com.aittest.doc.service.parser.DocumentParser.requireFitsColumn;
import static com.aittest.doc.service.parser.DocumentParser.stripBom;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 纯文本解析器：按空行分段，再把段落聚合到目标长度切成章节。
 *
 * <p>为什么不"一段一章节"：需求文档里一句话一段很常见，一段一章节会切出几百个 20 字的碎片，
 * 后续按章节调模型时每片都带一遍系统提示词，成本直接翻倍。所以先按空行分段（保留作者意图），
 * 再聚合到 {@link DocumentParser#TEXT_CHUNK_TARGET} 字符切块。</p>
 *
 * <p>章节标题取该块第一行的前 60 字符——纯文本没有标题，但列表页需要给人看的东西，
 * 全叫"第 N 段"会没法扫读。</p>
 *
 * @author 寇豆码
 */
@Component
public class TxtParser implements DocumentParser {

    /** 段落分隔：一个或多个空行。 */
    private static final String PARAGRAPH_SPLIT = "\\r?\\n\\s*\\r?\\n";

    /** 标题截取长度（够扫读，又不会把标题列塞满）。 */
    private static final int TITLE_PREVIEW = 60;

    @Override
    public DocType supports() {
        return DocType.TXT;
    }

    /**
     * 解析纯文本。
     *
     * @param bytes 文件字节
     * @param ctx   解析上下文
     * @return 章节列表
     */
    @Override
    public ParseResult parse(byte[] bytes, ParseContext ctx) {
        String text = stripBom(decodeText(bytes, ctx));
        if (text.isBlank()) {
            throw new BizException(ErrorCode.E_DOC_PARSE_FAILED, "文件内容为空，没有可解析的正文");
        }
        List<SectionDraft> sections = newList();
        StringBuilder chunk = new StringBuilder(TEXT_CHUNK_TARGET + 256);
        int seq = 0;
        for (String paragraph : text.split(PARAGRAPH_SPLIT)) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (chunk.length() > 0 && chunk.length() + trimmed.length() > TEXT_CHUNK_TARGET) {
                seq = flush(sections, chunk, seq);
            }
            if (chunk.length() > 0) {
                chunk.append("\n\n");
            }
            chunk.append(trimmed);
        }
        seq = flush(sections, chunk, seq);

        if (sections.isEmpty()) {
            appendChunked("正文", 1, text.trim(), 1, sections);
        }
        return ParseResult.ofSections(sections);
    }

    /**
     * 把累积的段落落成章节。
     *
     * @param out   输出
     * @param chunk 缓冲
     * @param seq   当前顺序号
     * @return 更新后的顺序号
     */
    private int flush(List<SectionDraft> out, StringBuilder chunk, int seq) {
        String content = chunk.toString().trim();
        chunk.setLength(0);
        if (content.isEmpty()) {
            return seq;
        }
        String title = firstLine(content, TITLE_PREVIEW);
        requireFitsColumn(title, HEADING_MAX, "章节标题", "第 " + (seq + 1) + " 个章节");
        int before = out.size();
        appendChunked(title, 1, content, seq + 1, out);
        return seq + (out.size() - before);
    }

    /**
     * 取首行并截断，作为章节标题。
     *
     * @param content 正文
     * @param max     最大长度
     * @return 标题
     */
    private String firstLine(String content, int max) {
        int lineEnd = content.indexOf('\n');
        String line = (lineEnd < 0 ? content : content.substring(0, lineEnd)).trim();
        if (line.isEmpty()) {
            return "正文";
        }
        return line.length() <= max ? line : line.substring(0, max) + "…";
    }
}
