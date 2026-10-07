package com.rj.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 缓存行为参数,对应 application.yaml 中的 {@code cache.*}(模块四)。
 * <p>
 * TTL 与线程池大小都是「策略」不是「代码」,集中在此便于调参,避免散落魔法数字。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cache")
public class CacheProperties {

    /** 工单详情缓存的 TTL 配置(逻辑过期 + 物理过期) */
    private WorkorderDetail workorderDetail = new WorkorderDetail();

    /** 空值哨兵(防穿透)的物理 TTL 秒数。哨兵要短,避免「查不到」多存活很久 */
    private long absentTtlSeconds = 60L;

    /** 权限树字典缓存的物理 TTL 秒数(TTL-only 失效) */
    private long permissionTreeTtlSeconds = 1800L;

    /** 角色已分配权限缓存的物理 TTL 秒数(写失效为主,TTL 兜底) */
    private long rolePermsTtlSeconds = 600L;

    /** 逻辑过期异步重建线程池配置 */
    private RebuildExecutor rebuildExecutor = new RebuildExecutor();

    /**
     * 工单详情缓存的过期策略。
     * <p>
     * <b>物理 TTL 必须大于逻辑 TTL</b>:逻辑过期靠「主动重建」刷新,物理过期只是防止
     * 「边缘键」永远驻留的兜底清场。若物理 ≤ 逻辑,键会先被 Redis 物理删除,
     * 读路径就会退化成「未命中 → 同步重建」,逻辑过期也就名存实亡。
     */
    @Data
    public static class WorkorderDetail {

        /** 逻辑过期秒数:到达后本次读返回旧值并触发异步重建 */
        private long logicalTtlSeconds = 300L;

        /** 物理过期秒数:Redis 层面的硬 TTL,只做兜底清场 */
        private long physicalTtlSeconds = 1800L;
    }

    /**
     * 逻辑过期异步重建线程池参数。
     * <p>
     * 队列满时的策略是「丢弃 + warn」:重建是<b>可丢弃的优化</b>——这次丢了,下次读还会触发;
     * 绝不能因为重建任务堆积把请求线程拖下水(CallerRunsPolicy 会把重建压力回灌给请求线程)。
     */
    @Data
    public static class RebuildExecutor {

        /** 核心线程数 */
        private int coreSize = 2;

        /** 最大线程数 */
        private int maxSize = 4;

        /** 有界队列容量,满则丢弃 */
        private int queueCapacity = 256;
    }
}
