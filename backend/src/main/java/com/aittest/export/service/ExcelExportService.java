package com.aittest.export.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.export.dto.CaseRow;
import com.aittest.export.dto.CaseSnapshot;
import com.aittest.export.dto.ExportStats;
import com.aittest.export.support.ExcelColumnDef;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * EasyExcel 双 sheet 写出（docs/07 §2.1 / §4.1）。
 *
 * <p><b>整个工作簿写在内存</b>：导出端点返回 {@code byte[]}，不落盘。
 * {@code export.tempDir} 是历史配置项，本实现不使用——落盘只会留下无人清理的临时文件，
 * 而「失败不落盘空 xlsx」是 §4.1 的硬约束。</p>
 *
 * <p><b>统计 sheet 与用例 sheet 来自同一个 {@link CaseSnapshot}</b>：
 * {@link ExportStats#fromSnapshot(CaseSnapshot)} 是纯内存转换，不查库（D-11）。</p>
 *
 * @author 寇豆码
 */
@Component
public class ExcelExportService {

    /** 「用例」sheet 名。 */
    public static final String SHEET_CASES = "用例";

    /** 「统计」sheet 名。 */
    public static final String SHEET_STATS = "统计";

    /**
     * 生成 xlsx 字节流。
     *
     * @param snapshot 导出快照（非空）
     * @return xlsx 字节
     * @throws BizException 生成失败 50000
     */
    public byte[] write(CaseSnapshot snapshot) {
        if (snapshot == null || snapshot.getRows() == null) {
            throw new BizException(ErrorCode.E_INTERNAL, "导出快照为空，无法生成 Excel");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = EasyExcel.write(out).autoCloseStream(false).build()) {
            WriteSheet caseSheet = EasyExcel.writerSheet(0, SHEET_CASES)
                    .head(ExcelColumnDef.toHead(ExcelColumnDef.CASE_HEADERS))
                    .build();
            writer.write(caseRows(snapshot), caseSheet);

            WriteSheet statSheet = EasyExcel.writerSheet(1, SHEET_STATS)
                    .head(ExcelColumnDef.toHead(ExcelColumnDef.STAT_HEADERS))
                    .build();
            writer.write(ExportStats.fromSnapshot(snapshot)
                    .toRows(ExcelColumnDef.STAT_FIELD_LABELS), statSheet);
        } catch (RuntimeException ex) {
            // message 只给用户看中文，技术细节交给上层日志，不塞进 toast
            throw new BizException(ErrorCode.E_INTERNAL, "生成 Excel 失败");
        }
        return out.toByteArray();
    }

    /**
     * 「用例」sheet 数据行：列顺序严格对齐 {@link ExcelColumnDef#CASE_HEADERS}。
     *
     * @param snapshot 导出快照
     * @return 数据行
     */
    private static List<List<Object>> caseRows(CaseSnapshot snapshot) {
        List<List<Object>> result = new ArrayList<>(snapshot.exportedCount());
        for (CaseRow row : snapshot.getRows()) {
            List<Object> cells = new ArrayList<>(ExcelColumnDef.CASE_HEADERS.size());
            cells.add(row.getSeqNo());
            cells.add(row.getTitle());
            cells.add(row.getPrecondition());
            cells.add(row.getSteps());
            cells.add(row.getExpected());
            cells.add(row.getPriority());
            cells.add(row.getCaseType());
            cells.add(row.getStatus());
            cells.add(row.getConfidence());
            cells.add(row.getModel());
            cells.add(row.getPromptVersion());
            cells.add(row.getCreatedAt() == null ? "" : row.getCreatedAt().toString());
            result.add(cells);
        }
        return result;
    }
}
