package com.aittest.system.dto;

import com.aittest.common.CodeLabel;
import java.util.List;

/**
 * 公开配置（{@code GET /api/config/public}）：前端启动即可拿到的枚举字典与只读限制值。
 *
 * <p><b>安全边界</b>：这里出现的每个字段都能被匿名访问者看到，因此只允许放
 * 枚举字典、版本号、provider 名称与限制值；<b>绝不</b>放 {@code baseUrl}、{@code apiKey}
 * 或任何开关的具体取值（如降级开关），那等于给攻击者画地图。</p>
 *
 * @param app               应用信息
 * @param docTypes          文档类型
 * @param parseStatuses     解析状态
 * @param caseTypes         测试点/用例类型
 * @param priorities        优先级
 * @param caseStatuses      用例评审状态
 * @param taskStatuses      任务状态
 * @param taskScenes        任务场景
 * @param riskLevels        风险等级
 * @param testPointStatuses 测试点状态
 * @param executionStatuses 执行结果状态
 * @param triageSources     归因来源
 * @param limits            只读限制值
 * @author 寇豆码
 */
public record PublicConfigVO(
        AppInfo app,
        List<CodeLabel> docTypes,
        List<CodeLabel> parseStatuses,
        List<CodeLabel> caseTypes,
        List<CodeLabel> priorities,
        List<CodeLabel> caseStatuses,
        List<CodeLabel> taskStatuses,
        List<CodeLabel> taskScenes,
        List<CodeLabel> riskLevels,
        List<CodeLabel> testPointStatuses,
        List<CodeLabel> executionStatuses,
        List<CodeLabel> triageSources,
        Limits limits) {

    /**
     * 应用信息（provider/model 只用于页头"当前模式"标识，不含地址与密钥）。
     *
     * @param name       应用名
     * @param version    版本号
     * @param aiProvider 模型供应商
     * @param aiModel    模型名
     * @param serverTime 服务端当前时间（ISO-8601 UTC，前端可据此校时）
     */
    public record AppInfo(String name, String version, String aiProvider, String aiModel, String serverTime) {
    }

    /**
     * 只读限制值（前端据此做前置校验，避免无意义的请求）。
     *
     * @param pageSizeMax   分页最大条数
     * @param uploadMaxMb   上传文件大小上限（MB）
     * @param exportMaxRows 单次导出最大行数
     */
    public record Limits(int pageSizeMax, int uploadMaxMb, int exportMaxRows) {
    }
}
