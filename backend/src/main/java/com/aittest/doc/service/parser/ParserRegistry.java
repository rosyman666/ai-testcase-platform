package com.aittest.doc.service.parser;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.DocType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 解析器注册表：按 {@link DocType} 路由到具体解析器。
 *
 * <p>刻意做成"由 Spring 注入全部实现、按 {@link DocumentParser#supports()} 建索引"，
 * 而不是 {@code switch (docType)} —— 新增一种格式只需要加一个 {@code @Component}，
 * 不用回来改这个类，也不会出现"某个分支忘了注册"。</p>
 *
 * <p>同时兼任"白名单唯一真源"的第二道检查：{@link DocType} 里有四种类型，
 * 若某个类型没有对应实现，这里在启动时就报错，而不是等用户上传时才 500。</p>
 *
 * @author 寇豆码
 */
@Component
public class ParserRegistry {

    /** 类型 → 解析器。 */
    private final Map<DocType, DocumentParser> parsers = new EnumMap<>(DocType.class);

    /**
     * 构造时收集全部解析器实现并校验覆盖度。
     *
     * @param parserList Spring 注入的全部 {@link DocumentParser} 实现
     */
    public ParserRegistry(List<DocumentParser> parserList) {
        for (DocumentParser parser : parserList) {
            DocumentParser previous = parsers.put(parser.supports(), parser);
            if (previous != null) {
                throw new IllegalStateException(
                        "文档类型 " + parser.supports() + " 注册了多个解析器："
                                + previous.getClass().getSimpleName() + " / " + parser.getClass().getSimpleName());
            }
        }
        for (DocType type : DocType.values()) {
            if (!parsers.containsKey(type)) {
                throw new IllegalStateException("文档类型 " + type + " 没有对应的解析器实现，启动中止");
            }
        }
    }

    /**
     * 解析文档。
     *
     * @param type  文档类型
     * @param bytes 文件字节
     * @param ctx   解析上下文
     * @return 解析结果
     */
    public DocumentParser.ParseResult parse(DocType type, byte[] bytes, DocumentParser.ParseContext ctx) {
        DocumentParser parser = parsers.get(type);
        if (parser == null) {
            // 构造期已校验过覆盖度，走到这里说明枚举被扩展但注册表没同步，属编码错误
            throw new BizException(ErrorCode.E_DOC_UNSUPPORTED, "暂不支持的文档类型：" + type.label());
        }
        return parser.parse(bytes, ctx);
    }

    /**
     * 已注册的解析器数量（供健康检查/自测用）。
     *
     * @return 数量
     */
    public int size() {
        return parsers.size();
    }
}
