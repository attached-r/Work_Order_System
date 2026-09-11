package com.rj.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置
 * <p>
 * 1. 乐观锁插件:注册后 {@code @Version} 字段才生效。更新时会自动追加
 *    {@code WHERE version = 旧值} 并把 version + 1;并发下若版本已被他人改写,
 *    影响行数为 0,业务层据此判定「版本冲突」。
 * 2. 分页插件:必须注册,否则 selectPage 不会真正分页(会退化成全表查询)。
 * <p>
 * 插件注册顺序沿用官方建议:乐观锁在分页之前(分页作为最后一环)。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 乐观锁:让 WorkOrder 上的 @Version 真正生效(此前缺失,导致并发覆盖无法拦截)
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
