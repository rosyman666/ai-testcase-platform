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

    /** 落盘根目录（相对工作目录；已在 .gitignore 中忽略）。 */
    private String dir = "uploads";
}
