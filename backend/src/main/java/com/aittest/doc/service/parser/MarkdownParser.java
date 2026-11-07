package com.aittest.doc.service.parser;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.DocType;
import static com.aittest.doc.service.parser.DocumentParser.HEADING_MAX;
import static com.aittest.doc.service.parser.DocumentParser.appendChunked;
import static com.aittest.doc.service.parser.DocumentParser.decodeText;
import static com.aittest.doc.service.parser.DocumentParser.newList;
import static com.aittest.doc.service.parser.DocumentParser.requireFitsColumn;
import static com.aittest.doc.service.parser.DocumentParser.stripBom;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Markdown 解析器：按 ATX 标题（{@code #} / {@code ##} …）切章节。
 *
 * <p>三个必须处理的真实情况：</p>
 * <ol>
 *   <li><b>代码块里的 {@code #} 不是标题</b>。用 {@code ```}/{@code ~~~} 围栏状态机跳过围栏内的行，
 *       否则一段 shell 注释就能把文档切成几十个章节。</li>
 *   <li><b>首个标题之前的内容</b>（前言/文档说明）不能丢：兜底成一个"正文"章节。</li>
 *   <li><b>层级跳跃</b>（{@code #} 直接到 {@code ###}）要保留原始层级，不要归一化——
 *       后续提示词拼装依赖真实层级还原文档结构。</li>
 * </ol>
 *
 * @author 寇豆码
 */
@Component
public class MarkdownParser implements DocumentParser {

    /** ATX 标题：1~6 个 # + 空白 + 标题文本。 */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    /** 代码围栏起始/结束行。 */
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~).*$");

    /** 标题为空时的兜底名称。 */
    private static final String DEFAULT_HEADING = "正文";

    @Override
    public DocType supports() {
        return DocType.MD;
    }

    /**
     * 解析 Markdown。
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
        String[] lines = text.split("\r?\n", -1);

        String heading = null;
        int level = 1;
        int seq = 0;
        boolean inFence = false;
        StringBuilder body = new StringBuilder(text.length() / 2 + 16);

        for (String line : lines) {
            if (FENCE.matcher(line).matches()) {
                inFence = !inFence;
                body.append(line).append('\n');
                continue;
            }
            if (!inFence) {
                Matcher matcher = HEADING.matcher(line);
                if (matcher.matches()) {
                    seq = flush(sections, heading, level, body, seq);
                    heading = matcher.group(2).trim();
                    level = matcher.group(1).length();
                    body.setLength(0);
                    continue;
                }
            }
            body.append(line).append('\n');
        }
        flush(sections, heading, level, body, seq);

        if (sections.isEmpty()) {
            // 整篇没有任何标题：当成一个章节，别让用户上传完看到"解析成功但 0 章节"
            appendChunked(DEFAULT_HEADING, 1, text.trim(), 1, sections);
        }
        return ParseResult.ofSections(sections);
    }

    /**
     * 把累积的正文落成一个（或几个）章节。
     *
     * @param out     输出
     * @param heading 当前标题（null 表示首个标题之前的前言）
     * @param level   层级
     * @param body    正文缓冲
     * @param seq     当前顺序号
     * @return 更新后的顺序号
     */
    private int flush(List<SectionDraft> out, String heading, int level, StringBuilder body, int seq) {
        String content = body.toString().trim();
        if (heading == null && content.isEmpty()) {
            return seq;
        }
        String title = heading == null || heading.isBlank() ? DEFAULT_HEADING : heading;
        requireFitsColumn(title, HEADING_MAX, "章节标题", "第 " + (seq + 1) + " 个章节");
        int before = out.size();
        appendChunked(title, level, content, seq + 1, out);
        return seq + (out.size() - before);
    }
}
