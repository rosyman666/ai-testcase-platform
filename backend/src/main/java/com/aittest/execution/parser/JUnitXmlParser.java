package com.aittest.execution.parser;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.ExecutionSourceType;
import com.aittest.common.enums.ExecutionStatus;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

/**
 * JUnit XML 结果解析器（<b>StAX 流式</b>，禁用 DOM）。
 *
 * <p><b>为什么必须流式</b>：验收要求 50MB 的 JUnit XML 不 OOM。DOM 会把整棵树建成对象，
 * 一个 50MB 的结果文件（几万条 testcase + 大段 stack trace）在 DOM 下轻松吃掉数倍堆内存，
 * 而这条链路是"用户随手传一个 CI 产物"的入口，OOM 会打挂整个进程（不是只挂这个请求）。
 * StAX 逐事件推进，内存占用只与单条 {@code <testcase>} 的 stack trace 大小相关。</p>
 *
 * <p><b>安全开关</b>：DTD 与外部实体一律关闭。这个文件来自外部，开启后一个精心构造的
 * {@code <!ENTITY xxe SYSTEM "file:///etc/passwd">} 就能读到服务器本地文件。</p>
 *
 * <p><b>结构容错</b>：只认 {@code <testcase>}，不依赖根元素是 {@code <testsuites>} 还是
 * 单个 {@code <testsuite>}——CI 工具这两种都会产出，且 {@code <testsuites>} 下可以嵌多个
 * {@code <testsuite>}。只要 testcase 出现就记一条，因此多层嵌套天然不漏。</p>
 *
 * <p><b>状态映射</b>（JUnit 的语义 → 本项目的三值）：</p>
 * <ul>
 *   <li>子元素 {@code <failure>}：断言失败 → {@code FAILED}，{@code message} 属性进
 *       {@code failureMessage}，元素文本进 {@code stackTrace}；</li>
 *   <li>子元素 {@code <error>}：异常/超时等"没跑完" → 也归 {@code FAILED}。
 *       对"这条用例没通过"这个结论，断言失败与异常没有任何区别，分成两个状态只会让
 *       通过率的分母出现第三种口径；</li>
 *   <li>子元素 {@code <skipped>}：跳过 → {@code SKIPPED}；</li>
 *   <li>都没有：{@code PASSED}。</li>
 * </ul>
 *
 * <p><b>异常类名</b>：{@code <failure type="java.lang.AssertionError">} 的 {@code type} 属性
 * 优先，缺失时从元素正文首行推断（{@link FailureTypes}），最后前置进 {@code stackTrace}
 * 首行——归因靠它区分"断言失败"与"环境不可达"，正文关键词做不到这件事。</p>
 *
 * @author 寇豆码
 */
@Component
public class JUnitXmlParser implements ResultParser {

    /** testcase 元素名。 */
    private static final String ELEMENT_TESTCASE = "testcase";

    /** 失败（断言不通过）元素名。 */
    private static final String ELEMENT_FAILURE = "failure";

    /** 错误（异常/超时）元素名。 */
    private static final String ELEMENT_ERROR = "error";

    /** 跳过元素名。 */
    private static final String ELEMENT_SKIPPED = "skipped";

    /** 类名属性。 */
    private static final String ATTR_CLASSNAME = "classname";

    /** 方法名属性。 */
    private static final String ATTR_NAME = "name";

    /** 耗时属性（<b>秒</b>，double）。 */
    private static final String ATTR_TIME = "time";

    /** 失败消息属性。 */
    private static final String ATTR_MESSAGE = "message";

    /**
     * 异常全限定名属性（{@code <failure type="java.lang.AssertionError">}）。
     *
     * <p>这个属性是归因阶段最可靠的输入：正文在不同 writer 下差异极大（有的从
     * {@code at ...} 开始），只有 {@code type} 是稳定的异常类名。</p>
     */
    private static final String ATTR_TYPE = "type";

    /** UTF-8 BOM 长度。 */
    private static final int BOM_LENGTH = 3;

    /** 毫秒换算系数。 */
    private static final double MILLIS_PER_SECOND = 1000d;

    /** 线程安全的解析器工厂（构造期建一次，不每次 parse 都建）。 */
    private final XMLInputFactory inputFactory;

    public JUnitXmlParser() {
        this.inputFactory = createInputFactory();
    }

    @Override
    public ExecutionSourceType sourceType() {
        return ExecutionSourceType.JUNIT_XML;
    }

    /**
     * 流式解析 JUnit XML。
     *
     * @param in 文件输入流（调用方负责关闭）
     * @return 解析结果（可能为空列表）
     * @throws IOException 读流失败
     */
    @Override
    public List<ParsedResult> parse(InputStream in) throws IOException {
        List<ParsedResult> results = new ArrayList<>();
        XMLStreamReader reader = null;
        try {
            reader = inputFactory.createXMLStreamReader(skipBom(in));
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT
                        && ELEMENT_TESTCASE.equals(reader.getLocalName())) {
                    results.add(readTestCase(reader));
                }
            }
        } catch (XMLStreamException ex) {
            // 格式问题必须落到 44001 并带行号：抛裸异常会被兜底处理器映射成 50000，
            // 用户看到"系统内部错误"，而真正的原因是他上传的文件不合法。
            throw new BizException(ErrorCode.E_RESULT_PARSE_FAILED,
                    "JUnit XML 解析失败" + describeLocation(ex));
        } finally {
            closeQuietly(reader);
        }
        return results;
    }

    /**
     * 读一个 {@code <testcase>} 元素（进入时位于其 START_ELEMENT）。
     *
     * <p>用相对深度推进，直到 testcase 自己的 END_ELEMENT：这样 {@code <system-out>}、
     * {@code <properties>} 等未关心的子元素只会让深度 +1，不会被打断。</p>
     *
     * @param reader 位于 testcase START_ELEMENT 的 reader
     * @return 解析结果
     * @throws XMLStreamException XML 不合法
     */
    private ParsedResult readTestCase(XMLStreamReader reader) throws XMLStreamException {
        String className = attribute(reader, ATTR_CLASSNAME);
        String caseName = attribute(reader, ATTR_NAME);
        long durationMs = parseMillis(attribute(reader, ATTR_TIME));

        // 默认通过：只有出现 failure/error/skipped 子元素才降级。
        // 反过来（默认 FAILED，找不到标记就当通过）会让"文件根本没问题但结构不认识"的情况
        // 静默变成 100% 通过，正是要挡的事故。
        String status = ExecutionStatus.PASSED.code();
        String failureMessage = "";
        String stackTrace = "";
        // type 属性的原值（空串表示文件里没写）；多个 failure 元素时保留第一个
        String declaredType = "";

        int depth = 1;
        while (depth > 0 && reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String element = reader.getLocalName();
                if (ELEMENT_FAILURE.equals(element) || ELEMENT_ERROR.equals(element)) {
                    status = ExecutionStatus.FAILED.code();
                    if (declaredType.isEmpty()) {
                        declaredType = attribute(reader, ATTR_TYPE);
                    }
                    String message = attribute(reader, ATTR_MESSAGE);
                    if (!message.isEmpty()) {
                        failureMessage = message;
                    }
                    String text = readElementText(reader);
                    if (!text.isEmpty()) {
                        stackTrace = text;
                    }
                    // readElementText 已推进到该子元素的 END_ELEMENT，深度不增不减
                } else if (ELEMENT_SKIPPED.equals(element)) {
                    // 已经判失败就不要被 skipped 覆盖：两者并存属于畸形文件，
                    // "没通过"是更保守也更可解释的结论
                    if (!ExecutionStatus.FAILED.code().equals(status)) {
                        status = ExecutionStatus.SKIPPED.code();
                    }
                    readElementText(reader);
                } else {
                    depth++;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        // 异常类名：type 属性优先，缺失时从正文首行兜出来；仍没有则为 null（不报错）。
        // 固化进 stackTrace 首行后，归因规则只读库里那一列就能拿到可靠 FQCN。
        String failureType = FailureTypes.resolve(declaredType, stackTrace);
        String stackWithType = FailureTypes.withHeader(failureType, stackTrace);
        return new ParsedResult(className, caseName, status, durationMs, failureMessage,
                stackWithType, failureType);
    }

    /**
     * 读当前元素（含嵌套子元素）的全部文本，返回到它的 END_ELEMENT 为止。
     *
     * @param reader 位于目标元素 START_ELEMENT 的 reader
     * @return 去掉首尾空白的文本（自闭合元素返回空串）
     * @throws XMLStreamException XML 不合法
     */
    private static String readElementText(XMLStreamReader reader) throws XMLStreamException {
        StringBuilder text = new StringBuilder();
        int depth = 1;
        while (depth > 0 && reader.hasNext()) {
            int event = reader.next();
            switch (event) {
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA,
                     XMLStreamConstants.SPACE, XMLStreamConstants.ENTITY_REFERENCE ->
                        text.append(reader.getText());
                case XMLStreamConstants.START_ELEMENT -> depth++;
                case XMLStreamConstants.END_ELEMENT -> depth--;
                default -> {
                    // COMMENT / PROCESSING_INSTRUCTION / DTD 等与文本无关，忽略
                }
            }
        }
        return text.toString().trim();
    }

    /**
     * 取属性值（不存在或为空返回空串，<b>绝不返回 null</b>）。
     *
     * @param reader 位于 START_ELEMENT 的 reader
     * @param name   属性本地名
     * @return 非 null 的属性值（已 trim）
     */
    private static String attribute(XMLStreamReader reader, String name) {
        for (int index = 0; index < reader.getAttributeCount(); index++) {
            if (name.equals(reader.getAttributeLocalName(index))) {
                String value = reader.getAttributeValue(index);
                return value == null ? "" : value.trim();
            }
        }
        return "";
    }

    /**
     * 把 JUnit 的秒（double）换算成毫秒。
     *
     * <p>{@code 0.123} → {@code 123}。解析失败、负数、NaN 一律按 0：
     * 耗时是<b>指标</b>而不是判定依据，为它中断整批导入不划算；而 status 的非法取值
     * 在 CSV 侧是直接报错的——两者的严格度不同是有意的。</p>
     *
     * @param seconds 秒数字符串（可为空串）
     * @return 毫秒（非负）
     */
    private static long parseMillis(String seconds) {
        if (seconds == null || seconds.isEmpty()) {
            return 0L;
        }
        try {
            double value = Double.parseDouble(seconds);
            if (Double.isNaN(value) || Double.isInfinite(value) || value < 0d) {
                return 0L;
            }
            return Math.round(value * MILLIS_PER_SECOND);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * 跳过 UTF-8 BOM（Windows 上导出的文件常带）。
     *
     * <p>不跳过时 {@code <?xml} 前的 {@code EF BB BF} 会让部分解析器直接报
     * "Content is not allowed in prolog"，而用户的文件在编辑器里看起来完全正常。</p>
     *
     * @param in 原始流
     * @return 跳过 BOM 后的流
     * @throws IOException 读流失败
     */
    private static InputStream skipBom(InputStream in) throws IOException {
        PushbackInputStream stream = new PushbackInputStream(in, BOM_LENGTH);
        byte[] head = new byte[BOM_LENGTH];
        int read = stream.read(head, 0, BOM_LENGTH);
        if (read > 0) {
            boolean isBom = read == BOM_LENGTH
                    && (head[0] & 0xFF) == 0xEF
                    && (head[1] & 0xFF) == 0xBB
                    && (head[2] & 0xFF) == 0xBF;
            if (!isBom) {
                stream.unread(head, 0, read);
            }
        }
        return stream;
    }

    /**
     * 构造关闭了 DTD / 外部实体的 StAX 工厂。
     *
     * <p>{@code setProperty} 在部分实现上会抛 {@code IllegalArgumentException}（属性不支持），
     * 因此逐个容错设置：安全属性设不上时宁可让解析继续（这是可用的降级），
     * 但绝不因为"设不上安全属性"就整条导入链路 500。</p>
     *
     * @return 工厂
     */
    private static XMLInputFactory createInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        setPropertyQuietly(factory, XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        setPropertyQuietly(factory, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        // CDATA 与普通文本合并，避免 stack trace 被切成多段后丢失换行结构
        setPropertyQuietly(factory, XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        setPropertyQuietly(factory, XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
        return factory;
    }

    /**
     * 设置工厂属性，不支持时忽略。
     *
     * @param factory  工厂
     * @param property 属性名
     * @param value    属性值
     */
    private static void setPropertyQuietly(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException ex) {
            // 该实现不支持此属性：保持默认行为即可
        }
    }

    /**
     * 把 XMLStreamException 翻译成"（第 N 行）"后缀，便于用户定位。
     *
     * @param ex 异常
     * @return 位置描述（无位置信息时为空串）
     */
    private static String describeLocation(XMLStreamException ex) {
        Location location = ex.getLocation();
        if (location == null || location.getLineNumber() < 0) {
            return "";
        }
        return "（第 " + location.getLineNumber() + " 行）";
    }

    /**
     * 静默关闭 reader（{@code XMLStreamReader#close} 不关闭底层流，流由调用方负责）。
     *
     * @param reader reader（可为 null）
     */
    private static void closeQuietly(XMLStreamReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException ex) {
            // 关闭失败不影响已解析出的结果
        }
    }
}
