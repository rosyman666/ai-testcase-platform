package com.aittest.common.enums;

/**
 * 失败归因分类的<b>封闭取值集合</b>（规则能产出的全部结果）。
 *
 * <p><b>为什么不叫 {@code FailureCategory}</b>：MyBatis-Plus 的自动配置会把基础包下
 * 所有被引用的类型按<b>简单类名</b>注册成别名，而 {@code com.aittest.system.domain.FailureCategory}
 * （字典表实体）已经占用了 {@code FailureCategory} 这个别名。同名会有两个后果：
 * 一是启动期直接失败（{@code The alias 'FailureCategory' is already mapped to the value
 * 'com.aittest.system.domain.FailureCategory'}），二是即便侥幸启动，XML 里的
 * {@code resultType="FailureCategory"} 也会指向不确定的那个类。编译期与纯单测都发现不了
 * 这件事——只有真起 Spring 上下文才会炸，所以这里用 {@code ...Code} 后缀把两者彻底分开。</p>
 *
 * <p><b>为什么既是枚举又对应字典表</b>：两者职责不同，缺一不可。</p>
 * <ul>
 *   <li>枚举（本类）是<b>规则能产出的全部结果</b>：{@code ExecutionTriageService} 写入前要校验、
 *       {@code FailureTriageRules} 要一个编译期封闭、可穷举、未命中时有明确兜底
 *       （{@link #UNKNOWN}）的返回类型。若规则直接返回 {@code String}，拼错一个码值不会有
 *       任何编译错误，只会在库里留下一行 {@code failure_category = "TIMOUT"}，
 *       而页面照常显示、饼图悄悄少一块；</li>
 *   <li>字典表 {@link com.aittest.system.domain.FailureCategory} 是<b>可运营的元数据</b>：
 *       中文文案、严重度、排序、是否启用。运营可以改文案、停用某个分类，
 *       这些都不该要求改代码发版。</li>
 * </ul>
 *
 * <p>两边的 {@code code} 必须逐条一致（种子数据见 {@code V2__dict_and_config.sql}）。
 * 新增分类时两边一起改：枚举给规则与写侧校验用，字典给前端下拉、饼图与「是否启用」用。</p>
 *
 * @author 寇豆码
 */
public enum FailureCategoryCode implements CodedEnum {

    /** 超时：用例或下游调用在限定时间内没有返回。 */
    TIMEOUT("TIMEOUT", "超时", 10),

    /** 断言失败：被测行为与预期不符（功能缺陷的主战场）。 */
    ASSERTION("ASSERTION", "断言失败", 20),

    /** 环境问题：网络/域名/证书/端口等基础设施不可达。 */
    ENV("ENV", "环境问题", 30),

    /** 依赖服务异常：类缺失、Bean 缺依赖、上游方法不存在。 */
    DEPENDENCY("DEPENDENCY", "依赖服务异常", 40),

    /** 测试数据问题：唯一键冲突、格式非法、造数缺失。 */
    DATA("DATA", "测试数据问题", 50),

    /** 未归类：规则与关键词都没命中时的<b>显式</b>兜底，不是"没有归因"。 */
    UNKNOWN("UNKNOWN", "未归类", 999);

    private final String code;

    private final String label;

    private final int sortNo;

    FailureCategoryCode(String code, String label, int sortNo) {
        this.code = code;
        this.label = label;
        this.sortNo = sortNo;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public int sortNo() {
        return sortNo;
    }
}
