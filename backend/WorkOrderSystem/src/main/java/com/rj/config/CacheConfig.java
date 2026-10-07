package com.rj.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 缓存相关线程池配置(模块四)。
 * <p>
 * 只有 {@code cacheRebuildExecutor} 一个池,供「逻辑过期」的异步重建使用。
 * 参数全部来自 {@link CacheProperties},不在代码里写死。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CacheConfig {

    private final CacheProperties cacheProperties;

    /**
     * 缓存异步重建线程池。
     * <p>
     * 拒绝策略刻意选「<b>丢弃 + warn</b>」而非 {@code CallerRunsPolicy}:重建是可丢弃的优化,
     * 任务堆积时应直接丢弃(下次读还会触发),绝不能让重建压力回灌到请求线程——
     * 那会把「读路径优化」变成「读路径的负担」。
     */
    @Bean("cacheRebuildExecutor")
    public ThreadPoolTaskExecutor cacheRebuildExecutor() {
        CacheProperties.RebuildExecutor cfg = cacheProperties.getRebuildExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(cfg.getCoreSize());
        executor.setMaxPoolSize(cfg.getMaxSize());
        executor.setQueueCapacity(cfg.getQueueCapacity());
        executor.setThreadNamePrefix("cache-rebuild-");
        executor.setRejectedExecutionHandler((runnable, pool) ->
                log.warn("缓存重建任务队列已满,丢弃本次重建(可丢弃的优化,下次读会再次触发)"));
        executor.initialize();
        return executor;
    }
}
