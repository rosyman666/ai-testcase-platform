package com.aittest.export.controller;

import com.aittest.common.BizException;
import com.aittest.config.ExportProperties;
import com.aittest.export.dto.CaseSnapshot;
import com.aittest.export.dto.ExportQuery;
import com.aittest.export.service.CaseSnapshotBuilder;
import com.aittest.export.service.ExcelExportService;
import com.aittest.export.service.JUnitExportService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T09 导出端点 #35 / #36（docs/07 §4.1 / §4.2 / §4.0.3）。
 *
 * <p><b>两个方法刻意不声明 {@code produces}</b>：声明固定二进制 MIME 会复现 D-35——
 * 异常处理器想吐 JSON 时内容协商失败，客户端只看到 500 空 body，{@code 40003} 根本到不了前端。
 * 成功时由 {@link ResponseEntity} 显式设 {@code Content-Type} + {@code Content-Disposition}；
 * 失败时一律抛 {@link BizException}，交 {@code GlobalExceptionHandler.build()} 输出
 * {@code application/json} 的 {@code ApiResponse}。</p>
 *
 * <p><b>不落盘</b>：成功/失败都只在内存里产出字节，失败路径不会留下空 xlsx / 空 zip，
 * 也不会带 {@code Content-Disposition}。</p>
 *
 * <p>鉴权沿用 {@code /api/**} 默认保护（不在 {@code WebMvcConfig.AUTH_WHITELIST}），
 * token 走 header；本类不涉及 {@code AuthContext}。</p>
 *
 * @author 寇豆码
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    /** #35 成功响应的 MIME。 */
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** #36 成功响应的 MIME。 */
    private static final String ZIP_MIME = "application/zip";

    /** 文件名时间戳（UTC，与库里 {@code created_at} 同源）。 */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private final CaseSnapshotBuilder snapshotBuilder;
    private final ExcelExportService excelExportService;
    private final JUnitExportService junitExportService;
    private final ExportProperties exportProperties;

    public ExportController(CaseSnapshotBuilder snapshotBuilder,
                            ExcelExportService excelExportService,
                            JUnitExportService junitExportService,
                            ExportProperties exportProperties) {
        this.snapshotBuilder = snapshotBuilder;
        this.excelExportService = excelExportService;
        this.junitExportService = junitExportService;
        this.exportProperties = exportProperties;
    }

    /**
     * #35 GET /api/export/cases.xlsx —— 导出「用例」+「统计」双 sheet。
     *
     * @param query taskId 必填；docId / statuses 可选
     * @return 二进制 xlsx
     */
    @GetMapping("/cases.xlsx")
    public ResponseEntity<byte[]> exportXlsx(@ModelAttribute ExportQuery query) {
        CaseSnapshot snapshot = snapshotBuilder.build(query);
        byte[] body = excelExportService.write(snapshot);
        return binary(body, XLSX_MIME, filename(snapshot.getTaskNo(), "xlsx"));
    }

    /**
     * #36 GET /api/export/junit.zip —— 导出 JUnit 骨架 zip（方案 A）。
     *
     * <p>{@code packageName} 只在本端点生效；未传用 {@code export.junitPackage} 兜底。
     * {@code statuses} 与 #35 共用 {@link ExportQuery#normalizeStatuses()}，
     * 保证同一任务下两个文件的用例条数一致。</p>
     *
     * @param query taskId 必填；docId / statuses / packageName 可选
     * @return 二进制 zip
     */
    @GetMapping("/junit.zip")
    public ResponseEntity<byte[]> exportJunitZip(@ModelAttribute ExportQuery query) {
        // 包名是参数校验，放在查库之前：非法包名不该先跑一次大查询
        String packageName = query.requirePackageName();
        if (packageName == null) {
            packageName = exportProperties.getJunitPackage();
        }
        CaseSnapshot snapshot = snapshotBuilder.build(query);
        byte[] body = junitExportService.write(snapshot, packageName);
        return binary(body, ZIP_MIME, filename(snapshot.getTaskNo(), "zip"));
    }

    /**
     * 成功响应的统一二进制包装。
     *
     * @param body 文件内容
     * @param mime MIME
     * @param filename 文件名
     * @return HTTP 200 + 显式 Content-Type / Content-Disposition
     */
    private static ResponseEntity<byte[]> binary(byte[] body, String mime, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, mime)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    /**
     * 生成 {@code T{taskNo}_{yyyyMMdd-HHmmss}.xlsx|zip}。
     *
     * <p>{@code gen_task.task_no} 本身已以 {@code T} 开头（{@code T+yyyyMMdd+6位序号}），
     * 因此只在它不以 T 开头时补前缀，避免出现 {@code TT...} 这种与任务号对不上的文件名。</p>
     *
     * @param taskNo 任务业务单号
     * @param ext 扩展名
     * @return ASCII 安全的文件名
     */
    private static String filename(String taskNo, String ext) {
        String safeTaskNo = (taskNo == null || taskNo.isBlank())
                ? "unknown"
                : taskNo.replaceAll("[^A-Za-z0-9_-]", "_");
        String prefix = safeTaskNo.startsWith("T") ? safeTaskNo : "T" + safeTaskNo;
        return prefix + "_" + LocalDateTime.now(ZoneOffset.UTC).format(STAMP) + "." + ext;
    }
}
