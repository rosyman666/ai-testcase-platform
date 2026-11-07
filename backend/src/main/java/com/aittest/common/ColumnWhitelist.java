package com.aittest.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据库列名白名单：把 MySQL 的 {@code 1406 Data too long for column 'xxx'} 转成能给人看的中文。
 *
 * <p><b>为什么必须有这一层</b>（§8.1）：MySQL 的 1406 消息里带的是**列名**，直接回显等于把表结构
 * 告诉调用方（"哦，这张表有个字段叫 {@code storage_path}"）。它本身不是注入漏洞，但它是
 * **信息泄露 + 话术不合格**：用户看到的是 {@code Data too long for column 'name' at row 1}，
 * 既不知道该改哪、也不知道还能写多少字。</p>
 *
 * <p><b>为什么用"白名单"而不是"黑名单/过滤 SQL"</b>：黑名单永远漏——MySQL 换个语言包、
 * 换个驱动版本，消息格式就变；而白名单的失败方向是**安全**的：没登记过的列一律走通用文案，
 * 宁可少说一句，也不吐出未知列名。</p>
 *
 * <p><b>长度取值的来源</b>：{@code V1__schema.sql} 的 {@code VARCHAR(n)}。{@code utf8mb4} 下 {@code n} 是
 * **字符数**不是字节数，所以"最多 500 字符"对中文同样成立。</p>
 *
 * <p><b>同名不同长的处理</b>：{@code name} 在 {@code doc_source} 是 255、在 {@code prompt_template} 是 200，
 * 而异常消息只给列名不给表名。此时**不报具体上限**（只报字段名）——报错数字比不报更糟：
 * 用户按 255 改完再提交，还是会撞 1406，而第二次的错误还是一模一样。</p>
 *
 * @author 寇豆码
 */
public final class ColumnWhitelist {

    /**
     * 白名单条目。
     *
     * @param label     面向用户的中文字段名
     * @param maxLength 字符数上限；{@code 0} 表示"同名不同长，不给数字"
     */
    public record Column(String label, int maxLength) {
    }

    /**
     * {@code 表名.列名 → 标签 + 上限}。
     *
     * <p>刻意保留表名只是为了**人能对账**（写错长度时一眼能查出是哪张表），运行期查找只按列名。</p>
     */
    private static final String[][] RAW = {
        // doc_source
        {"doc_no", "文档单号", "32"},
        {"name", "文件名", "255"},
        {"file_hash", "文件指纹", "64"},
        {"storage_path", "落盘路径", "500"},
        {"parse_error", "解析错误信息", "1000"},
        // doc_section / api_operation
        {"heading", "章节标题", "500"},
        {"path", "接口路径", "500"},
        {"summary", "接口摘要", "500"},
        {"operation_id", "接口标识", "200"},
        {"tags", "接口标签", "500"},
        // test_point / test_case
        {"title", "标题", "500"},
        {"source_ref", "来源引用", "200"},
        {"model", "模型名", "64"},
        {"content_hash", "内容指纹", "64"},
        {"prompt_version", "模板版本", "32"},
        // case_review
        {"reason_text", "驳回说明", "1000"},
        // gen_task / gen_task_item
        {"task_no", "任务单号", "32"},
        {"error_msg", "错误信息", "1000"},
        // prompt_template
        {"output_schema", "输出 schema 路径", "255"},
        {"model_hint", "推荐模型", "64"},
        // execution
        {"run_no", "执行批次号", "32"},
        {"class_name", "测试类名", "500"},
        {"case_name", "测试方法名", "500"},
        // app_user
        {"username", "登录名", "64"},
        {"display_name", "显示名", "64"},
        {"password_hash", "口令摘要", "100"},
        // llm_call_log
        {"trace_id", "链路 traceId", "64"},
    };

    /**
     * 真实查找表：列名 → 条目。
     *
     * <p>{@code LinkedHashMap} 只为让 {@link #size()} 与 {@code RAW} 顺序一致，便于排查时打印。</p>
     */
    private static final Map<String, Column> BY_NAME = build();

    private ColumnWhitelist() {
    }

    /**
     * 构造查找表，并把"同名不同长"的条目降级为"不给数字"。
     *
     * @return 不可变映射
     */
    private static Map<String, Column> build() {
        Map<String, Column> map = new LinkedHashMap<>();
        for (String[] row : RAW) {
            String column = row[0];
            String label = row[1];
            int maxLength = Integer.parseInt(row[2]);
            Column previous = map.get(column);
            if (previous == null) {
                map.put(column, new Column(label, maxLength));
            } else if (previous.maxLength() != maxLength) {
                // 同名不同长：保留先登记的标签，但撤掉数字（理由见类注释）
                map.put(column, new Column(previous.label(), 0));
            }
        }
        return Map.copyOf(map);
    }

    /**
     * 按列名查白名单。
     *
     * @param column 列名（MySQL 消息里给的那个，大小写不敏感）
     * @return 命中返回条目，未登记返回 {@code null}
     */
    public static Column find(String column) {
        if (column == null) {
            return null;
        }
        return BY_NAME.get(column.trim().toLowerCase());
    }

    /**
     * 生成面向用户的一句话（含字段中文名与上限）。
     *
     * @param column 列名；未登记时返回通用文案
     * @return 可直接 toast 的中文
     */
    public static String describe(String column) {
        Column known = find(column);
        if (known == null) {
            return ErrorCode.E_FIELD_TOO_LONG.getMessage() + "，请检查文本长度";
        }
        if (known.maxLength() <= 0) {
            return "字段「" + known.label() + "」内容超出长度限制";
        }
        return "字段「" + known.label() + "」内容超出长度限制（最多 " + known.maxLength() + " 字符）";
    }

    /**
     * 白名单条目数（供自检与文档对账）。
     *
     * @return 去重后的列名数
     */
    public static int size() {
        return BY_NAME.size();
    }
}
