package com.aittest.doc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aittest.common.IdGen;
import com.aittest.common.PageQuery;
import com.aittest.config.StorageProperties;
import com.aittest.doc.domain.ApiOperation;
import com.aittest.doc.domain.DocSource;
import com.aittest.doc.mapper.ApiOperationMapper;
import com.aittest.doc.mapper.DocSectionMapper;
import com.aittest.doc.mapper.DocSourceMapper;
import com.aittest.doc.service.parser.ParserRegistry;
import com.aittest.generation.support.MybatisPlusLambdaCache;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 文档列表的<b>排序方向</b>回归测试（§11.1 D-55）。
 *
 * <p><b>只有两个用例，也只为这一件事存在</b>：{@code DocService} 的其余行为（上传查重四态、
 * 解析、删除落盘文件）各有各的验收，不在本类重复。</p>
 *
 * <p><b>本类要挡住的具体事故</b>：{@code MyBatis-Plus} 的
 * {@code orderBy(condition, isAsc, column)} <b>第二参是 {@code isAsc} 而不是 {@code isDesc}</b>。
 * 把 {@code PageQuery.isDesc()} 直接传进去，方向会整体翻转：默认（本应最新在前）变成升序，
 * 用户点「降序」拿到升序、点「升序」拿到降序。它<b>不抛异常、类型也对、单测也绿</b>——
 * 只有在页面上肉眼看到"最新上传的文档排在最后一页"时才暴露，而那时通常已经被当成
 * "分页插件坏了"或"数据没进来"排查过一轮。</p>
 *
 * <p><b>为什么两个用例必须成对</b>：只断默认降序，"方向恒降序"的实现也能过（用户在页面上
 * 点升序同样无效）；只断显式升序，"方向恒升序"的实现也能过。两个方向各断一次，
 * 才能证明排序是<b>跟着参数走</b>的——这也是 {@code GenTaskQueryTest} /
 * {@code CaseServiceTest} 里那两条用例的写法。</p>
 *
 * <p><b>第三处（{@code operations} 的 {@code seq_no}）语义不同，不能照抄 {@code !desc}</b>：
 * {@code seq_no} 是文档里的自然顺序（章节 / 接口原序），默认必须<b>升序</b>；而
 * {@code PageQuery.isDesc()} 未指定 {@code sort} 时返回 {@code true}（"最新在前"的列表习惯）。
 * 照抄 {@code !isDesc()} 会把默认翻成<b>降序</b>，与同文件 {@code sections()} 的
 * {@code orderByAsc(getSeqNo)} 自相矛盾——等于把"显式排序无效"换成"默认顺序也错"。
 * 所以那里显式区分三态（未传 sort / asc / desc），三例分别钉住，其中<b>只有第 1 例
 * 能抓住"照抄 {@code !desc}"这种写法</b>，第 2、3 例在两种写法下都绿。</p>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class DocServiceTest {

    /** 文档主键（{@code operations} 会先过 {@code requireDoc}）。 */
    private static final Long DOC_ID = 6_100L;


    @Mock
    private DocSourceMapper docSourceMapper;

    @Mock
    private DocSectionMapper docSectionMapper;

    @Mock
    private ApiOperationMapper apiOperationMapper;

    @Mock
    private ParserRegistry parserRegistry;

    @Mock
    private IdGen idGen;

    @Mock
    private StorageProperties storageProperties;

    @Mock
    private PlatformTransactionManager transactionManager;

    private DocService service;

    /**
     * 安装 lambda 列缓存：断言读的是 {@code wrapper.getSqlSegment()}，
     * 而 {@code orderBy(..., DocSource::getCreatedAt)} 解析列名需要 TableInfo。
     * 生产环境由启动期 Mapper 扫描填充，纯单测没有那一步。
     */
    @BeforeAll
    static void installLambdaCache() {
        MybatisPlusLambdaCache.install();
    }

    @BeforeEach
    void setUp() {
        service = new DocService(docSourceMapper, docSectionMapper, apiOperationMapper,
                parserRegistry, idGen, storageProperties, transactionManager);
    }

    @Test
    @DisplayName("列表默认 created_at DESC（把 isAsc 写回 desc 时这条必须红：新文档会排到最后一页）")
    void listDefaultSortsByCreatedAtDesc() {
        when(docSourceMapper.selectPage(any(), any())).thenReturn(emptyPage());

        service.list(new PageQuery(), null, null, null);

        String sql = capturedWrapperSql();
        assertThat(sql).containsIgnoringCase("created_at DESC").containsIgnoringCase("id DESC");
        // 反向断言是承重那句：正向断言在"整段 ORDER BY 消失"时也会绿，只有它能钉住方向
        assertThat(sql).as("默认必须降序：升序会把最新上传的文档排到最后一页")
                .doesNotContainIgnoringCase("created_at ASC");
    }

    @Test
    @DisplayName("显式 sort=createdAt,asc 时按 ASC（与默认相反，才能证明是「跟参数走」而不是「恒降序」）")
    void listHonoursAscendingSort() {
        when(docSourceMapper.selectPage(any(), any())).thenReturn(emptyPage());
        PageQuery query = new PageQuery();
        query.setSort("createdAt,asc");

        service.list(query, null, null, null);

        String sql = capturedWrapperSql();
        assertThat(sql).containsIgnoringCase("created_at ASC");
        assertThat(sql).as("显式 asc 时不能再出现 created_at DESC（方向写死成降序时这条红）")
                .doesNotContainIgnoringCase("created_at DESC");
        // 末尾的 id DESC 与主排序无关，恒在：同时间戳（批量上传）下的稳定兜底
        assertThat(sql).containsIgnoringCase("id DESC");
    }

    // ------------------------------------------------------------------
    // 接口列表（operations）的 seq_no 方向：默认升序，显式 desc 才降序
    // ------------------------------------------------------------------

    @Test
    @DisplayName("接口列表不传 sort 时 seq_no ASC（照抄 !isDesc() 会把默认翻成降序，这条必须红）")
    void operationsDefaultSortsBySeqNoAsc() {
        givenDocWithEmptyOperations();

        service.operations(DOC_ID, new PageQuery());

        String sql = capturedOperationSql();
        assertThat(sql).containsIgnoringCase("seq_no ASC");
        assertThat(sql).as("seq_no 是文档里的自然顺序，默认降序会与 sections() 的升序自相矛盾")
                .doesNotContainIgnoringCase("seq_no DESC");
    }

    @Test
    @DisplayName("接口列表 sort=seqNo,asc 时是 ASC（与默认同向）")
    void operationsHonoursAscendingSort() {
        givenDocWithEmptyOperations();
        PageQuery query = new PageQuery();
        query.setSort("seqNo,asc");

        service.operations(DOC_ID, query);

        String sql = capturedOperationSql();
        assertThat(sql).containsIgnoringCase("seq_no ASC");
        assertThat(sql).doesNotContainIgnoringCase("seq_no DESC");
    }

    @Test
    @DisplayName("接口列表 sort=seqNo,desc 时是 DESC（唯一能证明 desc 参数真的生效的方向）")
    void operationsHonoursDescendingSort() {
        givenDocWithEmptyOperations();
        PageQuery query = new PageQuery();
        query.setSort("seqNo,desc");

        service.operations(DOC_ID, query);

        String sql = capturedOperationSql();
        assertThat(sql).containsIgnoringCase("seq_no DESC");
        assertThat(sql).as("显式 desc 时不能再是 ASC（方向写死成升序时这条红）")
                .doesNotContainIgnoringCase("seq_no ASC");
    }

    /**
     * 捕获 {@code list} 发出的 wrapper 的 SQL 段（列名与方向都在里面，不需要连库）。
     *
     * @return SQL 片段，形如 {@code ORDER BY created_at DESC,id DESC}
     */
    @SuppressWarnings("unchecked")
    private String capturedWrapperSql() {
        ArgumentCaptor<LambdaQueryWrapper<DocSource>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(docSourceMapper).selectPage(any(), captor.capture());
        return captor.getValue().getSqlSegment();
    }

    /**
     * 捕获 {@code operations} 发出的 wrapper 的 SQL 段。
     *
     * @return SQL 片段，形如 {@code (doc_id = ...) ORDER BY seq_no ASC}
     */
    @SuppressWarnings("unchecked")
    private String capturedOperationSql() {
        ArgumentCaptor<LambdaQueryWrapper<ApiOperation>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(apiOperationMapper).selectPage(any(), captor.capture());
        return captor.getValue().getSqlSegment();
    }

    /**
     * 让 {@code operations} 能过 {@code requireDoc}，并返回空分页。
     */
    private void givenDocWithEmptyOperations() {
        DocSource doc = new DocSource();
        doc.setId(DOC_ID);
        when(docSourceMapper.selectById(DOC_ID)).thenReturn(doc);
        Page<ApiOperation> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0L);
        when(apiOperationMapper.selectPage(any(), any())).thenReturn(page);
    }

    /**
     * 空分页结果（本类只关心 wrapper，不关心数据）。
     *
     * @return 分页对象
     */
    private static Page<DocSource> emptyPage() {
        Page<DocSource> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0L);
        return page;
    }
}
