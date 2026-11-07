package com.aittest.config;

import com.aittest.common.PageQuery;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：分页插件 + 审计字段自动填充。
 *
 * <p><b>审计字段纪律</b>（见 §8.2 / §3.2.1）：{@code created_at} 全表必有，
 * {@code updated_at} 只有 9 张可变表有；两者由本类的 {@link AuditMetaObjectHandler} 统一填充，
 * 业务代码<b>禁止</b>手写赋值。只追加表的实体干脆不定义 {@code updatedAt} 字段，
 * 这样 {@code strictInsertFill} 会自动跳过，不需要在填充器里写 if-else 判断表名。</p>
 *
 * <p>时间统一取 <b>UTC</b>：库内 datetime(3) 存 UTC、JDBC 带 {@code serverTimezone=UTC}、
 * Jackson 也是 UTC，三处口径必须一致，否则会出现"写 UTC 读 +8 小时"的经典错位。</p>
 *
 * @author 寇豆码
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 分页插件。
     *
     * <p>{@code maxLimit} 配成与 {@link PageQuery#MAX_SIZE} 一致：即使调用方绕过
     * {@code PageQuery} 自己 new 一个 {@code Page(1, 100000)}，SQL 层也会被截断到 100，
     * 属于"第二道防线"。</p>
     *
     * @return MyBatis-Plus 插件链
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit((long) PageQuery.MAX_SIZE);
        // 页码越界返回空列表而不是回到第一页，前端分页器行为更可预期
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /**
     * 审计字段自动填充器。
     *
     * @return MetaObjectHandler
     */
    @Bean
    public MetaObjectHandler auditMetaObjectHandler() {
        return new AuditMetaObjectHandler();
    }

    /**
     * 审计字段填充实现：只填实体里真实存在的字段（strict* 系列自带存在性判断）。
     */
    static class AuditMetaObjectHandler implements MetaObjectHandler {

        @Override
        public void insertFill(MetaObject metaObject) {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
            this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        }

        @Override
        public void updateFill(MetaObject metaObject) {
            this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now(ZoneOffset.UTC));
        }
    }
}
