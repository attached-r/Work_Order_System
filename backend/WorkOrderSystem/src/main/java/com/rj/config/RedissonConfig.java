package com.rj.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redisson 客户端配置(模块四分布式锁)。
 * <p>
 * <b>刻意只用「核心包 + 手写 Bean」,不用 {@code redisson-spring-boot-starter}</b>,原因有二:
 * <ol>
 *   <li><b>版本解耦</b>:starter 会带一个 {@code redisson-spring-data-XX} 适配模块,必须与
 *       本项目 {@code spring-data-redis 4.x}(Boot 4.1)的大版本对齐;Boot 4 足够新,适配可能未就绪。
 *       核心包不引任何 spring-data 适配层,彻底规避这个不确定性。</li>
 *   <li><b>不抢 RedisTemplate</b>:starter 会接管/替换 {@code RedisTemplate} 的装配,而本项目
 *       的 {@code RedisTemplate} 已由模块一定型(Jackson3 + default typing),被替换会改变所有
 *       既有缓存的序列化格式。<b>我们只需要锁,不需要 Redisson 的 Spring 集成。</b></li>
 * </ol>
 * <p>
 * <b>编解码器用 {@link StringCodec}</b>:锁的 value 只是持有者线程标识,与 Jackson 无关。
 * Redisson 内部用的是 Jackson<b>2</b>({@code com.fasterxml.jackson}),本项目是 Jackson<b>3</b>
 * ({@code tools.jackson});让 Redisson 也承担缓存序列化会让两套 Jackson 在同一份数据上相遇。
 * 锁走 StringCodec、缓存走既有 {@code RedisTemplate},两套序列化永不相遇。
 * <p>
 * 连接参数<b>复用</b> {@code spring.data.redis.*},无需新增连接配置;Redis 在本项目已是硬依赖
 * (模块一起登录 token 就存 Redis),故 {@code RedissonClient} 无条件启用,不做「可选降级」。
 */
@Configuration
public class RedissonConfig {

    /**
     * 手工构造单机 {@link RedissonClient}。
     * <p>
     * {@code destroyMethod = "shutdown"} 让容器关闭时优雅释放 Redisson 的连接与后台线程。
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:0}") int database) {

        Config config = new Config();
        // 锁的 value 是线程标识字符串,用 StringCodec,永不触发 Jackson 序列化
        config.setCodec(StringCodec.INSTANCE);
        // 密码可空:本机开发常无密码,传空串会被 Redisson 当成「非空密码」,故显式判空。
        // Redisson 4.x 将密码上移到 Config 层(SingleServerConfig#setPassword 已废弃并告警)。
        if (StringUtils.hasText(password)) {
            config.setPassword(password);
        }

        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectionMinimumIdleSize(4)
                .setConnectionPoolSize(16);
        return Redisson.create(config);
    }
}
