package org.xhy.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 配置
 *
 * 仅在 redis.enabled=true 时激活（默认激活）。
 * 关闭后 Spring 不会创建 Redis 相关 Bean，自动降级到 NoOp 实现。
 */
@Configuration
@ConditionalOnProperty(name = "redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisConfig {

    /**
     * StringRedisTemplate — RedisTemplate<String, String> 的便捷子类
     *
     * 用于文件内容缓存等纯字符串场景。
     * Key/Value 均使用 String 序列化器，在 Redis CLI 中可直接查看内容。
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
