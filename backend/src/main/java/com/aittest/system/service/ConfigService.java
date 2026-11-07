package com.aittest.system.service;

import com.aittest.common.enums.CasePriority;
import com.aittest.common.enums.CaseStatus;
import com.aittest.common.enums.CaseType;
import com.aittest.common.enums.DocType;
import com.aittest.common.enums.ExecutionStatus;
import com.aittest.common.enums.ParseStatus;
import com.aittest.common.enums.RiskLevel;
import com.aittest.common.enums.TaskScene;
import com.aittest.common.enums.TaskStatus;
import com.aittest.common.enums.TestPointStatus;
import com.aittest.common.enums.TriageSource;
import com.aittest.config.AiProperties;
import com.aittest.config.ExportProperties;
import com.aittest.system.dto.PublicConfigVO;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 公开配置服务：把前端启动所需的枚举字典与只读限制值一次性组装返回。
 *
 * <p>为什么合并成一个接口：前端首屏最多需要 10 类字典，逐个请求会产生 10 次往返；
 * 合并且在白名单内，登录页也能直接用。</p>
 *
 * @author 寇豆码
 */
@Service
public class ConfigService {

    /** 上传大小上限（MB），与 {@code spring.servlet.multipart.max-file-size} 保持一致。 */
    private static final int UPLOAD_MAX_MB = 20;

    private final DictService dictService;

    private final AiProperties aiProperties;

    private final ExportProperties exportProperties;

    private final String appName;

    private final String appVersion;

    public ConfigService(DictService dictService,
                         AiProperties aiProperties,
                         ExportProperties exportProperties,
                         @Value("${spring.application.name:ai-testcase-platform}") String appName,
                         @Value("${info.app.version:1.0.0}") String appVersion) {
        this.dictService = dictService;
        this.aiProperties = aiProperties;
        this.exportProperties = exportProperties;
        this.appName = appName;
        this.appVersion = appVersion;
    }

    /**
     * 组装公开配置。
     *
     * @return 公开配置
     */
    public PublicConfigVO publicConfig() {
        PublicConfigVO.AppInfo app = new PublicConfigVO.AppInfo(
                appName,
                appVersion,
                aiProperties.getProvider(),
                aiProperties.getModel(),
                Instant.now().toString());
        PublicConfigVO.Limits limits = new PublicConfigVO.Limits(
                com.aittest.common.PageQuery.MAX_SIZE,
                UPLOAD_MAX_MB,
                exportProperties.getMaxRows());
        return new PublicConfigVO(
                app,
                dictService.ofEnum(DocType.values()),
                dictService.ofEnum(ParseStatus.values()),
                dictService.ofEnum(CaseType.values()),
                dictService.ofEnum(CasePriority.values()),
                dictService.ofEnum(CaseStatus.values()),
                dictService.ofEnum(TaskStatus.values()),
                dictService.ofEnum(TaskScene.values()),
                dictService.ofEnum(RiskLevel.values()),
                dictService.ofEnum(TestPointStatus.values()),
                dictService.ofEnum(ExecutionStatus.values()),
                dictService.ofEnum(TriageSource.values()),
                limits);
    }
}
