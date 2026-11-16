package com.aittest.execution.parser;

import com.aittest.common.enums.ExecutionSourceType;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 执行结果文件解析器：一种格式一个实现（{@code @Component} 注册）。
 *
 * <p><b>为什么是"格式 → 解析器"的接口，而不是一个大 {@code if (xml) ... else ...}</b>：
 * 新增格式（比如 Surefire 的 txt 汇总）时只加一个类，不动导入服务；
 * 导入服务通过 {@code List<ResultParser>} 注入、按 {@link #sourceType()} 建
 * {@code Map}，也就不会出现"枚举加了一项但解析器忘了加"的分支缺口——
 * 那种缺口的表现是运行时 {@code NullPointerException}，而不是启动期报错。</p>
 *
 * <p><b>实现方两条约定</b>：</p>
 * <ol>
 *   <li>只读流式：文件上限 20MB（{@code spring.servlet.multipart.max-file-size}）、
 *       验收要求 50MB 不 OOM，因此必须边读边放，不许把整个文件读成 {@code String} 或 DOM；</li>
 *   <li>文件格式问题一律抛 {@code BizException(E_RESULT_PARSE_FAILED, "...第 N 行...")}，
 *       不要抛裸 {@code RuntimeException}——后者会被兜底处理器映射成 50000，
 *       用户看到"系统内部错误"，而实际原因是他上传的文件不合法。</li>
 * </ol>
 *
 * @author 寇豆码
 */
public interface ResultParser {

    /**
     * 本解析器处理的格式。
     *
     * @return 来源格式
     */
    ExecutionSourceType sourceType();

    /**
     * 解析执行结果文件。
     *
     * <p>调用方负责关闭 {@code in}（通常在 try-with-resources 里调用本方法）；
     * 实现方<b>不要</b>关闭它，否则调用方再读会抛 {@code Stream closed}。</p>
     *
     * @param in 文件输入流（调用方保证非 null 且未关闭）
     * @return 解析结果（可能为空列表，由调用方判空后抛 40003）
     * @throws IOException 读流失败（磁盘/网络层，非格式问题）
     */
    List<ParsedResult> parse(InputStream in) throws IOException;
}
