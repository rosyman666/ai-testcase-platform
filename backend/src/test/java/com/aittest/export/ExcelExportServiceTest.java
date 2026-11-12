package com.aittest.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aittest.common.BizException;
import com.aittest.config.ExportProperties;
import com.aittest.export.dto.CaseRow;
import com.aittest.export.dto.CaseSnapshot;
import com.aittest.export.dto.ExportQuery;
import com.aittest.export.dto.ExportStats;
import com.aittest.export.service.CaseSnapshotBuilder;
import com.aittest.export.service.ExcelExportService;
import com.aittest.export.support.ExcelColumnDef;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.mapper.GenTaskMapper;
import com.aittest.generation.support.MybatisPlusLambdaCache;
import com.aittest.testcase.domain.TestCase;
import com.aittest.testcase.mapper.TestCaseMapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T09 导出：双 sheet / 中文 / 统计同快照 / 参数与空集口径（docs/07 §8 T02 完成判据 ②③⑦）。
 *
 * <p>用 POI 反向读回 xlsx，而不是「断言字节非空」：中文列头乱码、sheet 缺失、统计行数不对
 * 这几类问题只有在真正解析工作簿时才会暴露。</p>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class ExcelExportServiceTest {

    @Mock
    private TestCaseMapper testCaseMapper;

    @Mock
    private GenTaskMapper genTaskMapper;

    private ExportProperties properties;
    private CaseSnapshotBuilder builder;
    private ExcelExportService excelExportService;

    /**
     * 安装 MyBatis-Plus 的 lambda 列缓存：{@link CaseSnapshotBuilder} 用
     * {@code LambdaQueryWrapper<TestCase>} 拼列名，纯 Mockito 单测不启 Spring 上下文，
     * 不装缓存会在 {@code getSqlSegment()} 时抛
     * {@code MybatisPlusException: can not find lambda cache}。与既有
     * {@code GenTaskQueryTest} 用同一个 test-only 初始化器，不改生产代码。
     */
    @BeforeAll
    static void installLambdaCache() {
        MybatisPlusLambdaCache.install();
    }

    @BeforeEach
    void setUp() {
        properties = new ExportProperties();
        properties.setMaxRows(5000);
        builder = new CaseSnapshotBuilder(testCaseMapper, genTaskMapper, properties);
        excelExportService = new ExcelExportService();
    }

    @Test
    @DisplayName("双 sheet、中文列头不乱码、统计 5 字段与用例 sheet 同一批快照")
    void writesTwoSheetsWithChineseHeadersAndConsistentStats() throws IOException {
        CaseSnapshot snapshot = snapshot(
                row(1, "用户登录成功", "ACCEPTED"),
                row(2, "密码错误返回 401", "EDITED"),
                row(3, "中文标题含\"引号\"与反斜杠\\", "ACCEPTED"));

        byte[] xlsx = excelExportService.write(snapshot);
        assertThat(xlsx).isNotEmpty();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(0)).isEqualTo(ExcelExportService.SHEET_CASES);
            assertThat(workbook.getSheetName(1)).isEqualTo(ExcelExportService.SHEET_STATS);

            DataFormatter formatter = new DataFormatter();
            Sheet cases = workbook.getSheetAt(0);
            Row header = cases.getRow(0);
            assertThat(header.getPhysicalNumberOfCells()).isEqualTo(ExcelColumnDef.CASE_HEADERS.size());
            assertThat(text(header.getCell(0), formatter)).isEqualTo("序号");
            assertThat(text(header.getCell(1), formatter)).isEqualTo("用例标题");
            assertThat(text(header.getCell(11), formatter)).isEqualTo("创建时间(UTC)");
            assertThat(cases.getLastRowNum()).isEqualTo(3);
            assertThat(text(cases.getRow(1).getCell(1), formatter)).isEqualTo("用户登录成功");
            assertThat(text(cases.getRow(3).getCell(1), formatter))
                    .isEqualTo("中文标题含\"引号\"与反斜杠\\");
            assertThat(text(cases.getRow(1).getCell(7), formatter)).isEqualTo("ACCEPTED");

            Sheet stats = workbook.getSheetAt(1);
            assertThat(text(stats.getRow(0).getCell(0), formatter)).isEqualTo("统计项");
            assertThat(stats.getLastRowNum()).isEqualTo(5);
            assertThat(text(stats.getRow(1).getCell(0), formatter)).isEqualTo("导出时间(UTC)");
            assertThat(text(stats.getRow(2).getCell(0), formatter)).isEqualTo("任务号");
            assertThat(text(stats.getRow(2).getCell(1), formatter)).isEqualTo("T20261112000001");
            assertThat(text(stats.getRow(4).getCell(0), formatter)).isEqualTo("导出用例数");
            assertThat(text(stats.getRow(4).getCell(1), formatter)).isEqualTo("3");
            assertThat(text(stats.getRow(5).getCell(1), formatter))
                    .contains("DRAFT=0")
                    .contains("ACCEPTED=2")
                    .contains("EDITED=1")
                    .contains("REJECTED=0");
        }

        // ExportStats 只能是纯内存转换：同一快照、同一批 rows，绝无第二次查询
        ExportStats stats = ExportStats.fromSnapshot(snapshot);
        assertThat(stats.getExportedCount()).isEqualTo(3);
        assertThat(stats.getStatusCounts()).isSameAs(snapshot.getStatusCounts());
        assertThat(stats.getTaskNo()).isEqualTo(snapshot.getTaskNo());
    }

    @Test
    @DisplayName("默认 statuses 只查 ACCEPTED/EDITED，且用例集只查一次库")
    void defaultStatusesAndSingleQuery() {
        ExportQuery query = new ExportQuery();
        query.setTaskId(1L);
        when(genTaskMapper.selectById(any())).thenReturn(task());
        when(testCaseMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(entity("A", "ACCEPTED"), entity("B", "EDITED")));

        CaseSnapshot snapshot = builder.build(query);

        assertThat(snapshot.getRows()).hasSize(2);
        assertThat(snapshot.getStatusCounts())
                .containsEntry("ACCEPTED", 1)
                .containsEntry("EDITED", 1)
                .containsEntry("DRAFT", 0)
                .containsEntry("REJECTED", 0);
        assertThat(snapshot.getCriteria()).isEqualTo("statuses=ACCEPTED,EDITED; taskId=1");

        ArgumentCaptor<Wrapper<TestCase>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(testCaseMapper, times(1)).selectList(captor.capture());
        Wrapper<TestCase> wrapper = captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("status");
        AbstractWrapper<?, ?, ?> abstractWrapper = (AbstractWrapper<?, ?, ?>) wrapper;
        assertThat(abstractWrapper.getParamNameValuePairs().values().toString())
                .contains("ACCEPTED")
                .contains("EDITED")
                .doesNotContain("DRAFT")
                .doesNotContain("REJECTED");
    }

    @Test
    @DisplayName("statuses 含非法值 → 整单 40001，message 列出可选值且不查库")
    void illegalStatusRejectsWholeRequest() {
        ExportQuery query = new ExportQuery();
        query.setTaskId(1L);
        query.setStatuses("ACCEPTED,BOGUS");

        BizException ex = catchBiz(() -> builder.build(query));
        assertThat(ex.getErrorCode().getCode()).isEqualTo(40001);
        assertThat(ex.getMessage()).contains("BOGUS").contains("DRAFT/ACCEPTED/EDITED/REJECTED");
        verifyNoInteractions(genTaskMapper, testCaseMapper);
    }

    @Test
    @DisplayName("大小写不符同样 40001；空串回落默认")
    void caseSensitiveStatusWhiteList() {
        ExportQuery bad = new ExportQuery();
        bad.setTaskId(1L);
        bad.setStatuses("accepted");
        assertThat(catchBiz(() -> builder.build(bad)).getErrorCode().getCode()).isEqualTo(40001);
        verifyNoInteractions(genTaskMapper, testCaseMapper);

        ExportQuery blank = new ExportQuery();
        blank.setTaskId(1L);
        blank.setStatuses("  ");
        assertThat(blank.normalizeStatuses()).containsExactly("ACCEPTED", "EDITED");
    }

    @Test
    @DisplayName("空集 → 40003「没有符合条件的用例」，不带 Content-Disposition")
    void emptyResultThrows40003() {
        ExportQuery query = new ExportQuery();
        query.setTaskId(1L);
        when(genTaskMapper.selectById(any())).thenReturn(task());
        when(testCaseMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        BizException ex = catchBiz(() -> builder.build(query));
        assertThat(ex.getErrorCode().getCode()).isEqualTo(40003);
        assertThat(ex.getMessage()).isEqualTo("没有符合条件的用例");
    }

    @Test
    @DisplayName("超过 export.maxRows → 40003「超出单次上限」，LIMIT maxRows+1 探测")
    void overLimitThrows40003() {
        properties.setMaxRows(2);
        ExportQuery query = new ExportQuery();
        query.setTaskId(1L);
        when(genTaskMapper.selectById(any())).thenReturn(task());
        when(testCaseMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                entity("A", "ACCEPTED"), entity("B", "EDITED"), entity("C", "ACCEPTED")));

        BizException ex = catchBiz(() -> builder.build(query));
        assertThat(ex.getErrorCode().getCode()).isEqualTo(40003);
        assertThat(ex.getMessage()).contains("超出单次上限").contains("2");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<TestCase>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(testCaseMapper).selectList(captor.capture());
        assertThat(captor.getValue().getCustomSqlSegment()).contains("LIMIT 3");
    }

    @Test
    @DisplayName("任务不存在 → 40401，且不会去查用例")
    void missingTaskThrows40401() {
        ExportQuery query = new ExportQuery();
        query.setTaskId(999L);
        when(genTaskMapper.selectById(any())).thenReturn(null);

        BizException ex = catchBiz(() -> builder.build(query));
        assertThat(ex.getErrorCode().getCode()).isEqualTo(40401);
        verify(testCaseMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("缺 taskId → 40002；非法 packageName → 40001；合法包名原样返回")
    void requiredAndPackageValidation() {
        ExportQuery noTask = new ExportQuery();
        assertThat(catchBiz(() -> builder.build(noTask)).getErrorCode().getCode()).isEqualTo(40002);

        ExportQuery badPackage = new ExportQuery();
        badPackage.setPackageName("123.bad");
        assertThat(catchBiz(badPackage::requirePackageName).getErrorCode().getCode()).isEqualTo(40001);

        ExportQuery goodPackage = new ExportQuery();
        goodPackage.setPackageName("com.aittest.generated");
        assertThat(goodPackage.requirePackageName()).isEqualTo("com.aittest.generated");

        ExportQuery noPackage = new ExportQuery();
        assertThat(noPackage.requirePackageName()).isNull();
        verifyNoInteractions(genTaskMapper, testCaseMapper);
    }

    @Test
    @DisplayName("docId 过滤进入同一条查询，条件回显带上 docId")
    void docIdFilterIsAppliedInSameQuery() {
        ExportQuery query = new ExportQuery();
        query.setTaskId(1L);
        query.setDocId(77L);
        when(genTaskMapper.selectById(any())).thenReturn(task());
        when(testCaseMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(entity("A", "ACCEPTED")));

        CaseSnapshot snapshot = builder.build(query);

        assertThat(snapshot.getCriteria()).contains("docId=77");
        assertThat(snapshot.getTaskNo()).isEqualTo("T20261112000001");
        verify(testCaseMapper, times(1)).selectList(any(Wrapper.class));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String text(Cell cell, DataFormatter formatter) {
        return cell == null ? "" : formatter.formatCellValue(cell);
    }

    private static GenTask task() {
        GenTask task = new GenTask();
        task.setId(1L);
        task.setTaskNo("T20261112000001");
        return task;
    }

    private static TestCase entity(String title, String status) {
        TestCase entity = new TestCase();
        entity.setId(1L);
        entity.setTaskId(1L);
        entity.setSeqNo(1);
        entity.setTitle(title);
        entity.setStatus(status);
        entity.setSteps("[{\"no\":1,\"action\":\"打开页面\",\"data\":\"-\"}]");
        entity.setConfidence(new BigDecimal("0.500"));
        entity.setCreatedAt(LocalDateTime.of(2026, 11, 12, 10, 0, 0));
        return entity;
    }

    private static CaseRow row(int seqNo, String title, String status) {
        return CaseRow.builder()
                .seqNo(seqNo)
                .title(title)
                .precondition("无")
                .steps("[{\"no\":1,\"action\":\"打开页面\",\"data\":\"-\"}]")
                .expected("符合预期")
                .priority("P1")
                .caseType("FUNCTIONAL")
                .status(status)
                .confidence(new BigDecimal("0.500"))
                .model("deepseek-chat")
                .promptVersion("case_generate.v1")
                .createdAt(LocalDateTime.of(2026, 11, 12, 10, 0, 0))
                .build();
    }

    private static CaseSnapshot snapshot(CaseRow... rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("DRAFT", 0);
        counts.put("ACCEPTED", 0);
        counts.put("EDITED", 0);
        counts.put("REJECTED", 0);
        List<CaseRow> list = new ArrayList<>();
        for (CaseRow row : rows) {
            list.add(row);
            counts.merge(row.getStatus(), 1, Integer::sum);
        }
        return CaseSnapshot.builder()
                .taskNo("T20261112000001")
                .rows(list)
                .statusCounts(counts)
                .exportedAt(LocalDateTime.of(2026, 11, 12, 10, 30, 0))
                .criteria("statuses=ACCEPTED,EDITED; taskId=1")
                .build();
    }

    private static BizException catchBiz(Runnable action) {
        try {
            action.run();
            throw new AssertionError("预期抛出 BizException，实际正常返回");
        } catch (BizException ex) {
            return ex;
        }
    }
}
