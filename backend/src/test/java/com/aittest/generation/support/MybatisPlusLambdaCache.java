package com.aittest.generation.support;

import com.aittest.execution.domain.ExecutionResult;
import com.aittest.execution.domain.ExecutionRun;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.domain.GenTaskItem;
import com.aittest.system.domain.FailureCategory;
import com.aittest.testcase.domain.CaseReview;
import com.aittest.testcase.domain.TestCase;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 纯 Mockito 单测里的 MyBatis-Plus 实体元数据初始化器（test-only）。
 *
 * <p><b>为什么需要它</b>：生产代码用 {@code Wrappers.<GenTaskItem>lambdaUpdate()} /
 * {@code lambdaQuery()} 拼条件，而 lambda 方法引用要解析成列名，必须查
 * {@code LambdaUtils.getColumnMap(entityClass)}。这个 COLUMN_CACHE_MAP 平时由
 * MyBatis-Plus 在启动期扫描 Mapper 时通过 {@code TableInfoHelper.initTableInfo}
 * 顺带 {@code LambdaUtils.installCache} 填充；纯单测不启 Spring 上下文、不建
 * SqlSessionFactory，缓存就是空的，于是每个 lambda 列都抛
 * {@code MybatisPlusException: can not find lambda cache for this entity}。</p>
 *
 * <p><b>为什么不改生产代码</b>：生产代码在真实容器里完全正确，缺失的是测试的
 * 运行环境，不是实现。这里只在测试侧补上实体元数据，等价于把「启动期扫描」
 * 这一步手工做掉；同时避免为了迁就单测把 lambda wrapper 改成字符串列名。</p>
 *
 * <p>{@link #install()} 幂等，可被多个测试类在 {@code @BeforeAll} 里重复调用：
 * {@code TableInfoHelper.initTableInfo} 本身是 synchronized 且按 Class 去重，
 * 这里再加一层判断，防止同一 JVM 里重复注册。</p>
 *
 * @author 寇豆码
 */
public final class MybatisPlusLambdaCache {

    private MybatisPlusLambdaCache() {
    }

    /**
     * 安装 {@link GenTask} / {@link GenTaskItem} / {@link TestCase} / {@link CaseReview}
     * / {@link ExecutionRun} / {@link ExecutionResult} / {@link FailureCategory} 的
     * TableInfo 与 lambda 列缓存。
     *
     * <p>只注册真正会被 lambda wrapper 用到的实体：其余 mapper（TestPoint / DocSource /
     * ExtractPlanner / RejectReason）都是 mock，不会触发真实的列解析。少注册实体也避免把
     * 无关实体的注解约束带进单测。</p>
     *
     * <p><b>T08 追加后两个</b>：{@code CaseReviewService} 锁用例行用
     * {@code lambdaQuery().eq(TestCase::getId, ...).last("FOR UPDATE")}，批量评审用
     * {@code lambdaUpdate().eq(CaseReview...)} 之外的条件更新，两者都要解析 lambda 列名。</p>
     *
     * <p><b>T10 追加后三个</b>：{@code ExecutionQueryService} 的分页/明细/字典查询分别
     * 用 {@code LambdaQueryWrapper<ExecutionRun>}、{@code <ExecutionResult>}、
     * {@code <FailureCategory>}。</p>
     *
     * <p><b>为什么不再用"装过一次就 return"的开关</b>：那个开关让<b>注册内容依赖于测试类的
     * 执行顺序</b>——先跑的类装完旧的三/四个实体就把 {@code installed} 置 true，后跑的类新增的
     * 实体永远装不上，表现为"单独跑通过、全量跑 {@code can not find lambda cache}"。
     * {@link #installEntity} 自身已按 {@code TableInfoHelper.getTableInfo == null} 去重，
     * 因此每次全量走一遍是幂等且廉价的。</p>
     */
    public static synchronized void install() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        installEntity(assistant, GenTask.class);
        installEntity(assistant, GenTaskItem.class);
        installEntity(assistant, TestCase.class);
        installEntity(assistant, CaseReview.class);
        installEntity(assistant, ExecutionRun.class);
        installEntity(assistant, ExecutionResult.class);
        installEntity(assistant, FailureCategory.class);
    }

    private static void installEntity(MapperBuilderAssistant assistant, Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(assistant, entityClass);
        }
    }
}
