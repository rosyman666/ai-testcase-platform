package com.aittest.doc.service.parser;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.DocType;
import static com.aittest.doc.service.parser.DocumentParser.HEADING_MAX;
import static com.aittest.doc.service.parser.DocumentParser.appendChunked;
import static com.aittest.doc.service.parser.DocumentParser.newList;
import static com.aittest.doc.service.parser.DocumentParser.requireFitsColumn;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/**
 * Word（.docx）解析器：按标题样式切章节，段落与表格都转成纯文本。
 *
 * <p>三个容易做错的地方：</p>
 * <ol>
 *   <li><b>必须遍历 {@code getBodyElements()} 而不是只用 {@code getParagraphs()}</b>。
 *       {@code getParagraphs()} 拿不到表格，而需求文档里的字段清单、状态矩阵几乎全在表格里——
 *       漏掉表格等于把最关键的信息丢了。</li>
 *   <li><b>标题识别要看样式 id，而且有多个别名</b>：英文 Word 是 {@code Heading1}，
 *       中文 Word 常见 {@code 1}/{@code 标题 1}，还有 {@code Title}。只匹配 {@code Heading\d}
 *       会让中文文档整篇变成一个章节。</li>
 *   <li><b>本版本不做 OCR</b>：纯图片文档解析出来是空字符串，必须给出可读的中文提示，
 *       而不是"解析成功但 0 章节"。</li>
 * </ol>
 *
 * @author 寇豆码
 */
@Component
public class DocxParser implements DocumentParser {

    /** 标题样式：{@code Heading1} / {@code 标题 1} / {@code heading 2} 等。 */
    private static final Pattern HEADING_STYLE = Pattern.compile("^(?i)(?:heading|标题)\\s*([1-6])$");

    /** 文档属性（Title）样式按一级标题处理。 */
    private static final String TITLE_STYLE = "title";

    /** 首个标题之前的内容兜底章节名。 */
    private static final String DEFAULT_HEADING = "正文";

    @Override
    public DocType supports() {
        return DocType.DOCX;
    }

    /**
     * 解析 .docx。
     *
     * @param bytes 文件字节
     * @param ctx   解析上下文
     * @return 章节列表
     */
    @Override
    public ParseResult parse(byte[] bytes, ParseContext ctx) {
        List<SectionDraft> sections = newList();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String heading = null;
            int level = 1;
            int seq = 0;
            StringBuilder body = new StringBuilder(1024);

            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    int headingLevel = headingLevel(paragraph);
                    String text = paragraph.getText();
                    if (headingLevel > 0) {
                        seq = flush(sections, heading, level, body, seq);
                        heading = text == null || text.isBlank() ? "未命名章节" : text.trim();
                        level = headingLevel;
                        body.setLength(0);
                    } else if (text != null && !text.isBlank()) {
                        body.append(text.trim()).append('\n');
                    }
                } else if (element instanceof XWPFTable table) {
                    // 表格文本必须抽出来：字段清单/状态矩阵都在这里
                    String rendered = renderTable(table);
                    if (!rendered.isBlank()) {
                        body.append(rendered).append('\n');
                    }
                }
            }
            flush(sections, heading, level, body, seq);

            if (sections.isEmpty()) {
                throw new BizException(ErrorCode.E_DOC_PARSE_FAILED,
                        "Word 文件里没有可读取的文字内容（可能是纯图片文档，当前版本不支持图片识别），"
                                + "请改用文字版或 Markdown 重新上传");
            }
            return ParseResult.ofSections(sections);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[DOC-PARSE] docx 解析失败：{}", ctx == null ? "-" : ctx.brief(), e);
            throw new BizException(ErrorCode.E_DOC_PARSE_FAILED,
                    "Word 文件已损坏或不是有效的 .docx，无法读取。请用 Word 另存为 .docx 后重新上传");
        }
    }

    /**
     * 判断段落是否是标题并返回层级。
     *
     * @param paragraph 段落
     * @return 1~6 表示标题层级；0 表示正文
     */
    private int headingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null || style.isBlank()) {
            return 0;
        }
        String normalized = style.trim();
        Matcher matcher = HEADING_STYLE.matcher(normalized);
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(1));
        }
        if (TITLE_STYLE.equalsIgnoreCase(normalized)) {
            return 1;
        }
        // 中文 Word 的标题样式 id 常直接是 "1".."6"
        if (normalized.length() == 1) {
            char ch = normalized.charAt(0);
            if (ch >= '1' && ch <= '6') {
                return ch - '0';
            }
        }
        return 0;
    }

    /**
     * 表格转纯文本：单元格用 {@code |} 分隔，行用换行分隔。
     *
     * @param table 表格
     * @return 渲染后的文本
     */
    private String renderTable(XWPFTable table) {
        StringBuilder builder = new StringBuilder(256);
        for (XWPFTableRow row : table.getRows()) {
            List<XWPFTableCell> cells = row.getTableCells();
            StringBuilder line = new StringBuilder(64);
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) {
                    line.append(" | ");
                }
                String cellText = cells.get(i).getText();
                line.append(cellText == null ? "" : cellText.replaceAll("\\s+", " ").trim());
            }
            if (!line.isEmpty()) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString().trim();
    }

    /**
     * 把累积正文落成章节。
     *
     * @param out     输出
     * @param heading 当前标题
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
