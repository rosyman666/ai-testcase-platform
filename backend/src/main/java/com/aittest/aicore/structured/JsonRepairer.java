package com.aittest.aicore.structured;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * JSON 修复器：把"模型的坏输出"修成能解析的 JSON。
 *
 * <p><b>为什么需要它</b>：即使提示词里写满"只输出 JSON、不要解释"，仍然会稳定出现四类坏输出
 * （T04 ③ 就是按这四类验收的）：</p>
 * <ol>
 *   <li><b>markdown 围栏</b>：{@code ```json ... ```}——模型习惯性包裹，属于最常见的一类；</li>
 *   <li><b>尾逗号</b>：{@code [{"a":1},]}——JavaScript 里合法，JSON 里非法；</li>
 *   <li><b>单引号 key</b>：{@code [{'a':1}]}——Python dict 的肌肉记忆；</li>
 *   <li><b>被截断</b>：输出到 {@code max_tokens} 上限时从中间断掉，最后一截是半个元素。</li>
 * </ol>
 *
 * <p><b>为什么不用现成的 {@code JSONParser} 宽松模式</b>：Jackson 的
 * {@code ALLOW_SINGLE_QUOTES} / {@code ALLOW_TRAILING_COMMA} 只能各自开关，
 * 而围栏与截断它完全不管；更关键的是——<b>宽松模式的报错信息会从"第 3 行少个引号"退化成
 * "unexpected token"</b>，而这条信息要贴进 repair 提示词回给模型（§8.6 的
 * {@code CASE_REPAIR} 变量 {@code {{validation_error}}}），报错越准，修复命中率越高。</p>
 *
 * <p><b>修复的边界（重要）</b>：这里只做<b>语法</b>修复，<b>不做语义</b>修复。字段缺失、
 * 枚举值非法、优先级写错，一律交给 {@link JsonSchemaValidator} 判定并抛
 * {@code E_SCHEMA_INVALID}——如果修复器去"猜"该填什么值，模型的质量问题就会被静默抹平，
 * 采纳率与降级率随之全部失真。</p>
 *
 * @author 寇豆码
 */
public final class JsonRepairer {

    private JsonRepairer() {
    }

    /**
     * 完整修复流水线：剥围栏 → 抽平衡片段（或补全截断）→ 语法归一化。
     *
     * <p>顺序不能换：必须先"抽/补"再"归一化"。反过来的话，归一化会在截断处留下
     * 半个单引号字符串，抽平衡片段时状态机会把后面的括号都当成字符串内容，越修越远。</p>
     *
     * @param raw 模型原始输出
     * @return 尽量合法的 JSON 文本（不保证一定合法；是否合法由调用方 parse 时判定）
     */
    public static String repair(String raw) {
        return repairWithMeta(raw).text();
    }

    /**
     * 同 {@link #repair(String)}，但把"修了什么"一并返回。
     *
     * <p>返回元信息是为了让调用方能区分<b>"模型输出很干净"</b>与<b>"模型输出是坏的、被我们修好了"</b>。
     * 这两件事在质量指标里必须分开统计：修一次能救回来不代表模型好，
     * 混在一起会让"解析失败率"永远显示 0，而实际首次通过率很低（§8.3 首次通过率 / 解析失败率）。</p>
     *
     * @param raw 模型原始输出
     * @return 修复结果与元信息
     */
    public static RepairOutcome repairWithMeta(String raw) {
        if (raw == null || raw.isBlank()) {
            return new RepairOutcome("", false, false);
        }
        String fenced = stripFence(raw);
        String body = extractJsonBody(fenced);
        // 括号配不上就说明被 max_tokens 截断了，后面走"退到最后一个完整元素"
        boolean truncated = balancedEnd(body) < 0;
        // **只有在截断时才补全**。这里踩过一次：无条件调用 completeTruncated 时，
        // 它会把"最后一个逗号之后的内容"当成半截元素切掉——而完整的 JSON 结尾也有逗号，
        // 于是尾字段被静默删除，输出仍然是合法 JSON、仍然能解析，只是少了一个字段。
        // 表现就是 schema 校验报"未找到所需属性 sourceRef"，而原文里明明有。
        String completed = truncated ? completeTruncated(body) : body;
        String text = normalize(completed);
        boolean changed = !text.strip().equals(raw.strip());
        return new RepairOutcome(text, truncated, changed);
    }

    /**
     * 修复结果。
     *
     * @param text      修复后的文本
     * @param truncated 是否发生了"截断补全"（<b>这条为真时应置 {@code degraded}=1</b>）
     * @param changed   修复后与原文本是否有差异（为真说明模型输出本身不合法）
     */
    public record RepairOutcome(String text, boolean truncated, boolean changed) {
    }

    /**
     * 去掉 markdown 围栏。
     *
     * <p>只处理"围栏在最外层"这一种形状（{@code ```json} 开头、{@code ```} 结尾）。
     * 中间夹着围栏的情况交给 {@link #extractJsonBody} 的括号扫描兜住，
     * 不必在这里写正则——正则版围栏剥离在"JSON 里本身有 {@code ```} 字符串"时会误伤。</p>
     *
     * @param raw 原始文本
     * @return 去掉围栏后的文本
     */
    static String stripFence(String raw) {
        String text = raw.strip();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) {
            // 只有一行 ```：整体剥掉首尾三个反引号
            return text.replace("```", "").strip();
        }
        String body = text.substring(firstLineEnd + 1);
        int closing = body.lastIndexOf("```");
        if (closing >= 0) {
            body = body.substring(0, closing);
        }
        return body.strip();
    }

    /**
     * 从"可能带解说文字"的文本里抽出 JSON 主体。
     *
     * <p>先找第一个 {@code &#123;} 或 {@code [}，然后做<b>字符串感知</b>的括号配对；配对成功就截到
     * 配对处（丢掉后面的"以上是生成结果，希望有帮助"），配对失败说明被截断，
     * 走 {@link #completeTruncated(String)} 补全。</p>
     *
     * <p>为什么必须"字符串感知"：{@code {"expr":"a}b"}} 里的 {@code }} 在字符串内部，
     * 用裸计数配对会提前收尾，得到一个少一半的对象，而报错会是"意外的结束符"，
     * 让人以为是模型没输出完——排障方向就错了。</p>
     *
     * @param text 待处理文本
     * @return JSON 主体（可能仍是截断的，由后续步骤补全）
     */
    static String extractJsonBody(String text) {
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return text.strip();
        }
        String body = text.substring(start);
        int end = balancedEnd(body);
        return end > 0 ? body.substring(0, end) : body;
    }

    /**
     * 找到第一个容器的配对结束位置。
     *
     * @param body 以 {@code &#123;} 或 {@code [} 开头的文本
     * @return 结束位置（exclusive）；未配对（被截断）返回 {@code -1}
     */
    static int balancedEnd(String body) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        char quote = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quote) {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"', '\'' -> {
                    inString = true;
                    quote = c;
                }
                case '{' -> stack.push('}');
                case '[' -> stack.push(']');
                case '}', ']' -> {
                    if (stack.isEmpty()) {
                        return i;
                    }
                    stack.pop();
                    if (stack.isEmpty()) {
                        return i + 1;
                    }
                }
                default -> {
                    // 普通字符：忽略
                }
            }
        }
        return -1;
    }

    /**
     * 补全被截断的 JSON：退回到<b>最后一个完整元素</b>，再把剩下的容器闭合上。
     *
     * <p><b>为什么不是"直接补 {@code ]}}"</b>：截断点常常落在一个元素的中间
     * （{@code {"cases":[{...},{"title":"登}），直接补括号会得到一个
     * 语法合法但<b>内容残缺</b>的对象，schema 校验还能通过——于是"半个用例"被当成
     * 正常结果入库，只有人工评审时才会发现标题被截了一半。</p>
     *
     * <p>做法：扫描时记录"安全点"（容器刚闭合处、以及元素分隔逗号处）与<b>当时的括号栈快照</b>，
     * 最后取最靠后的安全点，用它的快照闭合。这样输出一定是"若干个完整元素"，
     * 损失的是最后半个元素——**而这正是 {@code degraded} 该置 1 的场景**。</p>
     *
     * @param text 可能是截断的 JSON
     * @return 补全后的文本；连一个安全点都没有时原样返回（由 parse 报错）
     * @implNote <b>只允许对"括号不配平"的文本调用</b>（{@link #balancedEnd(String)} 返回 {@code -1}）。
     *         对完整 JSON 调用会把它截短——因为"最后一个安全点"通常是最后一个字段前的逗号。
     */
    static String completeTruncated(String text) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        char quote = 0;
        int lastSafeEnd = -1;
        String lastSafeClosers = null;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quote) {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"', '\'' -> {
                    inString = true;
                    quote = c;
                }
                case '{' -> stack.push('}');
                case '[' -> stack.push(']');
                case '}', ']' -> {
                    if (!stack.isEmpty()) {
                        stack.pop();
                    }
                    if (!stack.isEmpty()) {
                        lastSafeEnd = i + 1;
                        lastSafeClosers = closers(stack);
                    }
                }
                case ',' -> {
                    // **只有"数组里的元素分隔逗号"才是安全点**。
                    // 对象内部的逗号（stack 顶是 '}'）切下去会留下半个对象，补完括号后
                    // 语法合法、schema 却缺字段——那正是"半个用例被当成完整用例入库"的成因。
                    if (!stack.isEmpty() && stack.peek() == ']') {
                        int trimmed = trimEnd(text, i);
                        if (trimmed > 0) {
                            lastSafeEnd = trimmed;
                            lastSafeClosers = closers(stack);
                        }
                    }
                }
                default -> {
                    // 普通字符：忽略
                }
            }
        }
        if (lastSafeEnd < 0 || lastSafeClosers == null) {
            return text;
        }
        return text.substring(0, lastSafeEnd) + lastSafeClosers;
    }

    /**
     * 语法归一化：单引号转双引号、裸 key 加引号、丢弃尾逗号。
     *
     * <p>三者放在<b>一次扫描</b>里做，而不是三个正则依次替换。正则版的做法在
     * {@code {"note":"a,] b"}} 这种"字符串里恰好出现 {@code ,]}"的输入上会把引号里的内容改掉，
     * 改完还是合法 JSON，只是内容静默变了——这类错误比解析失败难查得多。</p>
     *
     * @param text 待归一化文本
     * @return 归一化后的文本
     */
    static String normalize(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        boolean inString = false;
        boolean escaped = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    out.append(c);
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    out.append(c);
                    escaped = true;
                    continue;
                }
                if (c == quote) {
                    out.append('"');
                    inString = false;
                    continue;
                }
                // 单引号字符串里出现的双引号必须转义，否则转成双引号后句子就断了
                if (quote == '\'' && c == '"') {
                    out.append("\\\"");
                    continue;
                }
                out.append(c);
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
                out.append('"');
                continue;
            }
            if (c == ',') {
                // 尾逗号：后面只有空白 + 闭合符 → 直接丢掉这个逗号
                int next = nextNonWhitespace(text, i + 1);
                if (next >= 0 && (text.charAt(next) == '}' || text.charAt(next) == ']')) {
                    continue;
                }
                out.append(c);
                continue;
            }
            if (isIdentifierStart(c)) {
                int end = i;
                while (end < text.length() && isIdentifierPart(text.charAt(end))) {
                    end++;
                }
                String identifier = text.substring(i, end);
                int next = nextNonWhitespace(text, end);
                if (next >= 0 && text.charAt(next) == ':') {
                    // 裸 key：{"a":1} 写成 {a:1}
                    out.append('"').append(identifier).append('"');
                } else {
                    // true / false / null / 数字后缀等：原样输出
                    out.append(identifier);
                }
                i = end - 1;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * 从 {@code from} 起找第一个非空白字符。
     *
     * @param text 文本
     * @param from 起始下标
     * @return 下标；找不到返回 {@code -1}
     */
    private static int nextNonWhitespace(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 把 {@code end} 之前的尾部空白回退掉。
     *
     * @param text 文本
     * @param end  结束下标（exclusive）
     * @return 回退后的结束下标
     */
    private static int trimEnd(String text, int end) {
        int i = end;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    /**
     * 括号栈 → 需要补的闭合串（<b>内层在前</b>）。
     *
     * <p>{@link ArrayDeque#push} 把元素压到头部，迭代顺序正好是"栈顶→栈底"，
     * 也就是内层→外层，与闭合顺序一致，不需要再反转。</p>
     *
     * @param stack 括号栈（元素是待补的闭合字符）
     * @return 闭合串，如 {@code "}]"}
     */
    private static String closers(Deque<Character> stack) {
        StringBuilder sb = new StringBuilder(stack.size());
        for (Character closer : stack) {
            sb.append(closer);
        }
        return sb.toString();
    }

    /**
     * 是否可作为标识符首字符。
     *
     * @param c 字符
     * @return 是则 true
     */
    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    /**
     * 是否可作为标识符后续字符。
     *
     * @param c 字符
     * @return 是则 true
     */
    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '-';
    }
}
