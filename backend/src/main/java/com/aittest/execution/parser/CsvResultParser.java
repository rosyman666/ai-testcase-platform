package com.aittest.execution.parser;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.ExecutionSourceType;
import com.aittest.common.enums.ExecutionStatus;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * CSV 执行结果解析器（首行表头 + 最小 RFC4180）。
 *
 * <p><b>为什么不引第三方 CSV 库</b>：需要支持的东西只有三件——引号、引号内的逗号与换行、
 * UTF-8 BOM。一个 120 行的状态机足够，而多一个依赖意味着多一份传递依赖与
 * CVE 面（本项目已因 easyexcel + poi 带了足够多的依赖）。</p>
 *
 * <p><b>列名归一化</b>：表头先去空白、转小写、<b>删掉所有非字母数字字符</b>再比对，
 * 因此 {@code class_name} / {@code className} / {@code Class Name} / {@code classname}
 * 全部归一到 {@code classname}。之所以做这么宽：这份文件是人工或脚本整理的，
 * 让人为了导入去改表头，他就会改用"手改一份差不多能进的"数据——那才是最坏的结果。</p>
 *
 * <p><b>status 的非法取值<b>直接报错</b></b>：{@code PASSED / FAILED / SKIPPED} 之外的值
 * 一律抛 {@link ErrorCode#E_RESULT_PARSE_FAILED}，且消息带<b>行号</b>。
 * 绝不"猜一个差不多的状态"——把 {@code ERROR} 猜成 {@code PASSED} 会让通过率凭空变好，
 * 而且没有任何痕迹。{@code duration_ms} 的非数字同理报错（猜 0 会让总耗时静默失真）。</p>
 *
 * <p><b>行号口径</b>：报的是<b>记录起始的物理行号</b>（表头是第 1 行）。引号内换行会让
 * 物理行号与记录序号分叉，此时按物理行号报——用户在编辑器里跳转到的正是那一行。</p>
 *
 * @author 寇豆码
 */
@Component
public class CsvResultParser implements ResultParser {

    /** 字段分隔符。 */
    private static final char SEPARATOR = ',';

    /** 引号字符。 */
    private static final char QUOTE = '"';

    /** UTF-8 BOM 字符（读成 char 时是 U+FEFF）。 */
    private static final char BOM = '\uFEFF';

    /** 回退缓冲长度（最多需要回退 1 个字符，留 2 保证安全）。 */
    private static final int PUSHBACK_SIZE = 2;

    /** 表头列名（已归一化）：类名。 */
    private static final String[] COL_CLASS = {"classname"};

    /** 表头列名（已归一化）：方法名。 */
    private static final String[] COL_CASE = {"casename", "name"};

    /** 表头列名（已归一化）：状态。 */
    private static final String[] COL_STATUS = {"status", "result"};

    /** 表头列名（已归一化）：耗时毫秒。 */
    private static final String[] COL_DURATION = {"durationms", "timems"};

    /** 表头列名（已归一化）：失败消息。 */
    private static final String[] COL_MESSAGE = {"failuremessage", "message"};

    /** 表头列名（已归一化）：堆栈。 */
    private static final String[] COL_STACK = {"stacktrace"};

    /**
     * 表头列名（已归一化）：异常类名（可选列）。
     *
     * <p>没有这一列不算错：CSV 多半是人手整理的，多要求一列就等于要求他改文件；
     * 缺列时从 {@code stack_trace} 首行推断（{@link FailureTypes#firstLineType}）。</p>
     */
    private static final String[] COL_TYPE = {"failuretype", "type"};

    @Override
    public ExecutionSourceType sourceType() {
        return ExecutionSourceType.CSV;
    }

    /**
     * 解析 CSV。
     *
     * @param in 文件输入流（调用方负责关闭）
     * @return 解析结果（可能为空列表）
     * @throws IOException 读流失败
     */
    @Override
    public List<ParsedResult> parse(InputStream in) throws IOException {
        CsvRecordReader records = new CsvRecordReader(
                new PushbackReader(new InputStreamReader(in, StandardCharsets.UTF_8), PUSHBACK_SIZE));
        List<ParsedResult> results = new ArrayList<>();
        ColumnIndex columns = null;
        List<String> record;
        while ((record = records.next()) != null) {
            if (isBlankRecord(record)) {
                continue;
            }
            if (columns == null) {
                // 表头行：剥掉 BOM 再归一化，避免第一列列名变成 "\uFEFFclass_name" 后匹配不上
                record.set(0, stripBom(record.get(0)));
                columns = ColumnIndex.of(record);
                if (columns.status < 0) {
                    throw new BizException(ErrorCode.E_RESULT_PARSE_FAILED,
                            "CSV 表头缺少 status 列（可选列名：status / result）");
                }
                continue;
            }
            results.add(toResult(record, columns, records.recordLine()));
        }
        if (columns == null) {
            throw new BizException(ErrorCode.E_RESULT_PARSE_FAILED, "CSV 文件为空，缺少表头行");
        }
        return results;
    }

    /**
     * 一行记录 → 解析结果。
     *
     * @param record 字段列表
     * @param columns 列下标
     * @param line 该记录的起始物理行号
     * @return 解析结果
     */
    private ParsedResult toResult(List<String> record, ColumnIndex columns, int line) {
        String rawStatus = field(record, columns.status);
        ExecutionStatus status = parseStatus(rawStatus);
        if (status == null) {
            throw new BizException(ErrorCode.E_RESULT_PARSE_FAILED,
                    "第 " + line + " 行 status 取值不合法：" + rawStatus
                            + "（可选值：" + allowedStatuses() + "）");
        }
        String rawStack = field(record, columns.stackTrace);
        // 异常类名：failure_type 列优先，缺列时从 stack_trace 首行推断；固化进堆栈首行
        String failureType = FailureTypes.resolve(field(record, columns.failureType), rawStack);
        return new ParsedResult(
                field(record, columns.className),
                field(record, columns.caseName),
                status.code(),
                parseDuration(field(record, columns.duration), line),
                field(record, columns.message),
                FailureTypes.withHeader(failureType, rawStack),
                failureType);
    }

    /**
     * 取某列的值（下标越界或空返回空串，<b>绝不返回 null</b>）。
     *
     * <p>行短了不算错：CSV 的尾列经常被省略（比如全是通过、没有消息与堆栈），
     * 为这个报错会让用户以为文件坏了。</p>
     *
     * @param record 字段列表
     * @param index 列下标（负数表示无此列）
     * @return 非 null 的值（已 trim）
     */
    private static String field(List<String> record, int index) {
        if (index < 0 || index >= record.size()) {
            return "";
        }
        String value = record.get(index);
        return value == null ? "" : value.trim();
    }

    /**
     * 校验并解析状态。
     *
     * @param raw 原始文本（大小写不敏感）
     * @return 状态；不合法返回 {@code null}
     */
    private static ExecutionStatus parseStatus(String raw) {
        String normalized = raw.toUpperCase(Locale.ROOT);
        for (ExecutionStatus item : ExecutionStatus.values()) {
            if (item.code().equals(normalized)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 解析耗时（毫秒）。空值按 0；非数字<b>报错并带行号</b>。
     *
     * @param raw 原始文本
     * @param line 行号（错误消息用）
     * @return 毫秒（非负）
     */
    private static long parseDuration(String raw, int line) {
        if (raw.isEmpty()) {
            return 0L;
        }
        try {
            long value = Long.parseLong(raw);
            return Math.max(value, 0L);
        } catch (NumberFormatException ex) {
            throw new BizException(ErrorCode.E_RESULT_PARSE_FAILED,
                    "第 " + line + " 行 duration_ms 不是整数：" + raw);
        }
    }

    /**
     * 合法状态列表（拼进错误消息，让用户不用去翻文档）。
     *
     * @return 形如 {@code PASSED/FAILED/SKIPPED}
     */
    private static String allowedStatuses() {
        StringBuilder allowed = new StringBuilder();
        for (ExecutionStatus item : ExecutionStatus.values()) {
            if (allowed.length() > 0) {
                allowed.append('/');
            }
            allowed.append(item.code());
        }
        return allowed.toString();
    }

    /**
     * 去掉行首 BOM。
     *
     * @param value 原始文本
     * @return 去掉 BOM 的文本
     */
    private static String stripBom(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.charAt(0) == BOM ? value.substring(1) : value;
    }

    /**
     * 判断是不是空行（只有一个空白字段）。
     *
     * <p>空行必须跳过而不是报"status 不合法"：从 Excel 另存 CSV 时文件尾部常有一个空行。</p>
     *
     * @param record 字段列表
     * @return 空行返回 true
     */
    private static boolean isBlankRecord(List<String> record) {
        if (record.isEmpty()) {
            return true;
        }
        for (String value : record) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 表头列下标映射。
     *
     * <p>缺省下标为 -1，{@link #field} 遇到 -1 返回空串——因此 {@code message} /
     * {@code stack_trace} 这类可选列不存在时不会报错，而 {@code status} 缺失在
     * {@link #of} 调用方处单独判。</p>
     *
     * @param className 类名列下标
     * @param caseName 方法名列下标
     * @param status 状态列下标
     * @param duration 耗时列下标
     * @param message 失败消息列下标
     * @param stackTrace 堆栈列下标
     * @param failureType 异常类名列下标（可选列，缺列 -1，由堆栈首行推断）
     */
    private record ColumnIndex(int className, int caseName, int status, int duration,
                               int message, int stackTrace, int failureType) {

        /**
         * 由表头行解析列下标。
         *
         * @param header 表头字段
         * @return 列下标映射
         */
        static ColumnIndex of(List<String> header) {
            return new ColumnIndex(indexOf(header, COL_CLASS),
                    indexOf(header, COL_CASE),
                    indexOf(header, COL_STATUS),
                    indexOf(header, COL_DURATION),
                    indexOf(header, COL_MESSAGE),
                    indexOf(header, COL_STACK),
                    indexOf(header, COL_TYPE));
        }

        /**
         * 按别名找列下标。
         *
         * @param header 表头字段
         * @param aliases 归一化后的别名
         * @return 下标；没找到返回 -1
         */
        private static int indexOf(List<String> header, String... aliases) {
            Set<String> wanted = new HashSet<>(Arrays.asList(aliases));
            for (int index = 0; index < header.size(); index++) {
                if (wanted.contains(normalizeName(header.get(index)))) {
                    return index;
                }
            }
            return -1;
        }

        /**
         * 列名归一化：去空白、转小写、删掉所有非字母数字字符。
         *
         * @param name 原始列名
         * @return 归一化列名
         */
        private static String normalizeName(String name) {
            if (name == null || name.isEmpty()) {
                return "";
            }
            StringBuilder normalized = new StringBuilder(name.length());
            for (int index = 0; index < name.length(); index++) {
                char ch = name.charAt(index);
                if (Character.isLetterOrDigit(ch)) {
                    normalized.append(Character.toLowerCase(ch));
                }
            }
            return normalized.toString();
        }
    }

    /**
     * 最小 RFC4180 记录读取器（支持引号、引号内逗号与换行、{@code ""} 转义）。
     *
     * <p>逐字符推进而不是"先 {@code readLine()} 再 split"：引号内的换行属于字段内容，
     * 按行切会把它切断成两条记录，然后第一条的 status 变成后半截乱码——
     * 表现是"导入成功但通过率不对"，最难发现的一类错。</p>
     */
    private static final class CsvRecordReader {

        /** 底层读器（支持回退 1 个字符，用于 {@code ""} 与 CRLF 的前瞻）。 */
        private final PushbackReader reader;

        /** 下一条记录将要开始的物理行号（从 1 开始）。 */
        private int line = 1;

        /** 最近一次 {@link #next()} 返回记录的起始行号。 */
        private int recordLine = 1;

        /** 是否已到流尾。 */
        private boolean endOfStream = false;

        CsvRecordReader(PushbackReader reader) {
            this.reader = reader;
        }

        /**
         * 最近一次返回的记录起始行号。
         *
         * @return 行号（从 1 开始）
         */
        int recordLine() {
            return recordLine;
        }

        /**
         * 读下一条记录。
         *
         * @return 字段列表；流尾返回 {@code null}
         * @throws IOException 读流失败
         */
        List<String> next() throws IOException {
            if (endOfStream) {
                return null;
            }
            recordLine = line;
            List<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean inQuotes = false;
            boolean touched = false;
            while (true) {
                int read = reader.read();
                if (read < 0) {
                    endOfStream = true;
                    if (!touched) {
                        return null;
                    }
                    fields.add(field.toString());
                    return fields;
                }
                touched = true;
                char ch = (char) read;
                if (inQuotes) {
                    if (ch == QUOTE) {
                        int lookahead = reader.read();
                        if (lookahead == QUOTE) {
                            // "" 是字段内的一个引号
                            field.append(QUOTE);
                        } else {
                            inQuotes = false;
                            if (lookahead >= 0) {
                                reader.unread(lookahead);
                            }
                        }
                    } else {
                        if (ch == '\n') {
                            line++;
                        }
                        field.append(ch);
                    }
                    continue;
                }
                switch (ch) {
                    case QUOTE -> inQuotes = true;
                    case SEPARATOR -> {
                        fields.add(field.toString());
                        field.setLength(0);
                    }
                    case '\r' -> {
                        int lookahead = reader.read();
                        if (lookahead >= 0 && lookahead != '\n') {
                            reader.unread(lookahead);
                        }
                        line++;
                        fields.add(field.toString());
                        return fields;
                    }
                    case '\n' -> {
                        line++;
                        fields.add(field.toString());
                        return fields;
                    }
                    default -> field.append(ch);
                }
            }
        }
    }
}
