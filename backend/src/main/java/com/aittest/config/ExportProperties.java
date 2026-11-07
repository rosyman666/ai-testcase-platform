package com.aittest.config;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 导出配置（{@code export.*}）：控制单次导出条数上限与临时文件目录。
 *
 * <p>上限存在的意义是保护内存：一次性 POI 写 5 万行会 OOM，
 * 超过上限直接返回 40003 并提示用户加筛选条件，而不是让服务崩掉。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@ToString
@Component
@ConfigurationProperties(prefix = "export")
public class ExportProperties {

    /** 单次导出最大用例数。 */
    private int maxRows = 5000;

    /** 临时文件目录（相对工作目录；已在 .gitignore 中忽略）。 */
    private String tempDir = "exports";

    /** JUnit 代码生成的包名。 */
    private String junitPackage = "com.aittest.generated";

    /** JUnit 代码生成的默认超时（秒）。 */
    private int junitTimeoutSeconds = 30;
}
