package com.aittest.aicore.prompt;

import com.aittest.common.JsonUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 模板装载器：启动时把 {@code resources/prompts/*.md} 幂等地 seed 进 {@code prompt_template}。
 *
 * <p><b>幂等是硬要求</b>（T04 ⑤）：重启 N 次不能出现 N 份重复模板。四层保障：</p>
 * <ol>
 *   <li>先按 {@code (code, version)} 查一次，存在就跳过；</li>
 *   <li>DB 上 {@code uk_prompt_template_code_version} 是唯一索引；</li>
 *   <li>并发/多实例时插入会抛 {@code DuplicateKeyException}，捕获后按"已存在"处理；</li>
 *   <li><b>不覆盖已有行</b>：库里的版本是"已在生产用过的那个"，磁盘文件只是它的初始副本。
 *       覆盖会让"同一 version 两次生成结果不同"，A/B 对比直接失效。</li>
 * </ol>
 *
 * <p><b>为什么不放在 Flyway 迁移里</b>：Flyway 迁移是<b>一次性</b>的，写完就冻结（改一个字符都会
 * checksum mismatch，见 D-22 的教训）。而模板要频繁迭代，每次迭代都得新增迁移文件，
 * 很快就会出现 {@code V30__prompt_v7.sql} 这种没人敢动的历史包袱。
 * 把"模板内容"交给应用侧幂等 seed，版本演进就只是"加一个 .md 文件"。</p>
 *
 * <p><b>失败不阻断启动</b>：数据库暂时不可用 / 模板文件写错，只打 error 日志。
 * 理由：模板装载失败时应用<b>其余功能是好的</b>（上传、解析、列表都不依赖它），
 * 为一个初始化动作把整个服务拉不起来，代价不对等。代价是"AI 功能不可用"要等第一次调用才暴露——
 * 因此这里必须打 {@code log.error} 而不是 warn，保证它在日志里足够显眼。</p>
 *
 * @author 寇豆码
 */
@Component
public class PromptTemplateLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateLoader.class);

    /**
     * 随 jar 发布的模板文件（显式清单，不用通配）。
     *
     * <p>不用通配扫目录，是因为"多了一个 .md 文件"与"多了一版要生效的模板"是两件事：
     * 有人往这目录里放个草稿也会被 seed 进去，而 seed 进去的模板会被当成"可用版本"。</p>
     */
    private static final List<String> TEMPLATE_FILES = List.of(
            "prompts/testpoint_extract.v1.md",
            "prompts/case_generate.v1.md",
            "prompts/case_repair.v1.md",
            "prompts/failure_triage.v1.md");

    /** front-matter 分隔行。 */
    private static final String FRONT_MATTER_DELIMITER = "---";

    /** 模板 Mapper。 */
    private final PromptTemplateMapper promptTemplateMapper;

    public PromptTemplateLoader(PromptTemplateMapper promptTemplateMapper) {
        this.promptTemplateMapper = promptTemplateMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            SeedResult result = seed();
            log.info("[PROMPT] 模板装载完成：新增 {}，已存在 {}，内容不一致 {}",
                    result.inserted(), result.existed(), result.mismatched());
            if (!result.mismatchedCodes().isEmpty()) {
                log.warn("[PROMPT] 以下模板的文件内容与库内同版本不一致（库为准，未覆盖）：{}。"
                        + "要升级内容请新增 .vN.md 文件并提升 version，不要改历史文件（§8.6）",
                        String.join("、", result.mismatchedCodes()));
            }
        } catch (RuntimeException ex) {
            log.error("[PROMPT] 模板装载失败，AI 功能在修复前不可用（其余功能不受影响）", ex);
        }
    }

    /**
     * 执行一次幂等 seed。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：① 它由 {@link #run} 自调用，
     * 同类调用不走代理，事务注解本身不生效（写上去只会让人误以为有原子性）；
     * ② 更重要的是<b>不需要</b>——每行插入独立且幂等，"抽点有了、生成没有"这种半成品状态
     * 会在下一次启动时被自动补齐（已有的跳过、缺的插入）。用事务把 4 行绑在一起，
     * 反而会出现"因为第 4 个文件写错，前 3 个永远装不进去"。</p>
     *
     * @return 装载统计
     */
    public SeedResult seed() {
        int inserted = 0;
        int existed = 0;
        int mismatched = 0;
        List<String> mismatchedCodes = new ArrayList<>();

        for (String file : TEMPLATE_FILES) {
            ParsedTemplate parsed = parse(file);
            PromptTemplate existing = findByCodeAndVersion(parsed.code(), parsed.version());
            if (existing != null) {
                existed++;
                if (!normalize(existing.getContent()).equals(normalize(parsed.content()))) {
                    mismatched++;
                    mismatchedCodes.add(parsed.code() + ".v" + parsed.version());
                }
                continue;
            }
            PromptTemplate template = new PromptTemplate();
            template.setCode(parsed.code());
            template.setVersion(parsed.version());
            template.setScene(parsed.scene());
            template.setName(parsed.name());
            template.setContent(parsed.content());
            template.setOutputSchema(parsed.outputSchema());
            template.setModelHint(parsed.modelHint());
            // 只有"该 code 还没有生效版本"时才置 1，否则新版本必须先显式激活（避免启动即改线上行为）
            template.setIsActive(promptTemplateMapper.selectActiveByCode(parsed.code()) == null ? 1 : 0);
            try {
                promptTemplateMapper.insert(template);
                inserted++;
            } catch (org.springframework.dao.DuplicateKeyException dup) {
                // 多实例同时启动：另一个实例刚插进去，视为已存在
                existed++;
            }
        }
        return new SeedResult(inserted, existed, mismatched, mismatchedCodes);
    }

    /**
     * 按 {@code (code, version)} 查一行。
     *
     * @param code    模板编码
     * @param version 版本号
     * @return 已存在的行；没有返回 {@code null}
     */
    private PromptTemplate findByCodeAndVersion(String code, int version) {
        LambdaQueryWrapper<PromptTemplate> wrapper = new LambdaQueryWrapper<PromptTemplate>()
                .eq(PromptTemplate::getCode, code)
                .eq(PromptTemplate::getVersion, version)
                .last("LIMIT 1");
        return promptTemplateMapper.selectOne(wrapper);
    }

    /**
     * 读文件并解析 front-matter。
     *
     * @param path classpath 路径
     * @return 解析结果
     */
    private ParsedTemplate parse(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        String text;
        try (InputStream in = resource.getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("读取模板文件失败：" + path, ex);
        }
        return parseFrontMatter(path, text);
    }

    /**
     * 解析 YAML front-matter（只支持扁平的 {@code key: value}）。
     *
     * <p>不引 SnakeYAML 的显式构造器是因为这里只需要 6 个标量字段，而 SnakeYAML 会把
     * {@code name: 用例生成} 里的中文、{@code output_schema: classpath:...} 里的冒号
     * 按 YAML 规则解释——{@code classpath:schema/x.json} 在 YAML 里是合法的标量，
     * 但一旦有人在值里写 {@code a: b} 就会变成嵌套 map，解析结果类型突变。
     * 模板元信息是<b>我们自己写、自己读</b>的，用最笨的规则最不容易出意外。</p>
     *
     * @param path 文件路径（报错用）
     * @param text 文件全文
     * @return 解析结果
     */
    private ParsedTemplate parseFrontMatter(String path, String text) {
        String normalized = text.replace("\r\n", "\n");
        if (!normalized.startsWith(FRONT_MATTER_DELIMITER)) {
            throw new IllegalStateException("模板文件缺少 front-matter（第一行必须是 ---）：" + path);
        }
        int end = normalized.indexOf("\n" + FRONT_MATTER_DELIMITER, FRONT_MATTER_DELIMITER.length());
        if (end < 0) {
            throw new IllegalStateException("模板文件 front-matter 未闭合（缺少第二个 ---）：" + path);
        }
        String header = normalized.substring(FRONT_MATTER_DELIMITER.length(), end);
        String content = normalized.substring(end + FRONT_MATTER_DELIMITER.length() + 1).strip();

        Map<String, String> meta = new LinkedHashMap<>();
        for (String line : header.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).strip();
            String value = trimmed.substring(colon + 1).strip();
            // 去掉可能存在的引号（YAML 习惯写法）
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            meta.put(key, value);
        }
        requireMeta(path, meta, "code", "version", "scene", "name", "output_schema");
        if (content.isBlank()) {
            throw new IllegalStateException("模板正文为空：" + path);
        }
        return new ParsedTemplate(
                meta.get("code"),
                Integer.parseInt(meta.get("version")),
                meta.get("scene"),
                meta.get("name"),
                content,
                meta.get("output_schema"),
                meta.getOrDefault("model_hint", ""));
    }

    /**
     * 必填元信息校验。
     *
     * @param path 文件路径
     * @param meta 元信息
     * @param keys 必填键
     */
    private void requireMeta(String path, Map<String, String> meta, String... keys) {
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            String value = meta.get(key);
            if (value == null || value.isBlank()) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("模板 front-matter 缺少字段 " + String.join("、", missing) + "：" + path);
        }
    }

    /**
     * 归一化正文（比较用）：统一换行、去掉首尾空白。
     *
     * <p>不比较就必须比较：如果直接比字符串，Windows 上编辑过的文件（CRLF）会与库里（LF）
     * 判定为"不一致"，于是每次启动都报一次 warn——告警一旦变成常态，真出问题时就没人看了。</p>
     *
     * @param content 正文
     * @return 归一化后的正文
     */
    private String normalize(String content) {
        return content == null ? "" : content.replace("\r\n", "\n").strip();
    }

    /**
     * 解析出的模板元信息。
     *
     * @param code         模板编码
     * @param version      版本号
     * @param scene        场景
     * @param name         名称
     * @param content      正文
     * @param outputSchema 输出 schema 路径
     * @param modelHint    推荐模型
     */
    private record ParsedTemplate(String code, int version, String scene, String name,
                                  String content, String outputSchema, String modelHint) {
    }

    /**
     * 装载统计。
     *
     * @param inserted        新增条数
     * @param existed         已存在条数
     * @param mismatched      内容与库内不一致的条数
     * @param mismatchedCodes 不一致的模板标识（{@code code.vN}）
     */
    public record SeedResult(int inserted, int existed, int mismatched, List<String> mismatchedCodes) {

        /**
         * 便于日志/调试输出。
         *
         * @return JSON 文本
         */
        @Override
        public String toString() {
            return JsonUtils.toJson(this);
        }
    }
}
