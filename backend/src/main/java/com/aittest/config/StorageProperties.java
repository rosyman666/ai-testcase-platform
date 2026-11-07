package com.aittest.config;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 存储配置（{@code storage.*}）：原始上传文件的落盘根目录。
 *
 * <p><b>为什么不用 {@code java.io.tmpdir}</b>：§3.2 给 {@code doc_source.storage_path}
 * 的存在理由是"原始文件可回溯，重解析不必让用户重传"。写进系统临时目录会让这句话变成假的，
 * 而且失败是<b>静默</b>的——库里有一行、{@code storage_path} 有值、文件已被清理，只有真正
 * 调用 {@code reparse} 时才炸。所以落盘目录必须是工作目录下的持久路径。</p>
 *
 * <p>取值语义与 {@link ExportProperties#getTempDir()} 一致：<b>相对路径的解析基准是后端进程
 * 的工作目录</b>（{@code mvn spring-boot:run} 时是 {@code backend/}，{@code java -jar} 时是
 * 启动目录）。因此使用时一律先 {@code toAbsolutePath().normalize()}，并在首次建目录时把
 * 实际绝对路径打进启动日志，避免"文件找不到"查很久。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    /** 默认落盘根目录。抽成常量是为了让 setter 的兜底分支和字段初值只用写一次。 */
    private static final String DEFAULT_DIR = "uploads";

    /** 落盘根目录（相对工作目录；已在 .gitignore 中忽略）。 */
    private String dir = DEFAULT_DIR;

    /**
     * 设置落盘根目录，**空值/空白值回落到默认值**。
     *
     * <p><b>为什么必须自己兜这一层</b>：`${STORAGE_DIR:uploads}` 里的 `:默认值`
     * 只处理"键不存在或不可解析"，**不处理"键存在但值为空"**。`.env` 里写成
     * {@code STORAGE_DIR=}（有键无值）时，Spring 绑出来的是**空串**，于是
     * {@code Path.of("")} 就是进程工作目录本身——文件会静默落到 {@code backend/docs/}
     * 而不是 {@code backend/uploads/docs/}。症状是"刚传的文档打不开、重启又好了"，
     * 而上传/解析/入库全部成功，**没有任何测试会红**。</p>
     *
     * <p>{@code trim()} 同样是必需的：{@code Path.of("  ")} 抛的是
     * {@code InvalidPathException} 而**不是** {@code IOException}，会从
     * {@code store()} 的 catch 里漏出去，最后变成英文的 50000。</p>
     *
     * @param dir 配置值，可为 null/空/空白
     */
    public void setDir(String dir) {
        this.dir = (dir == null || dir.isBlank()) ? DEFAULT_DIR : dir.trim();
    }
}
