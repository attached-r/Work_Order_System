package com.rj.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // key / hash key 用字符串序列化，Redis 里 key 干净无前缀
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        // value / hash value 用 JSON 序列化；Spring Data Redis 4.x 用 Jackson3 版
        // GenericJacksonJsonRedisSerializer 取代了旧的 GenericJackson2JsonRedisSerializer。
        // 新版默认不写类型信息，需通过 customize 钩子激活 default typing，
        // 让 JSON 带上 @class 类型属性，取回时才能还原成具体实体类型。
        // (enableDefaultTyping 在不同 4.0.x 版本参数不一致，这里用官方测试同款写法更稳)
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType(Object.class)
                .build();
        GenericJacksonJsonRedisSerializer jsonSerializer = GenericJacksonJsonRedisSerializer.builder()
                .customize(mapper -> mapper.activateDefaultTypingAsProperty(ptv, DefaultTyping.NON_FINAL, "@class"))
                .build();

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
