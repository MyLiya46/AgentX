package org.xhy.infrastructure.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** Redis 文件内容缓存实现
 *
 * 实现 FileContentCache 接口，使用 Redis 作为缓存后端。
 *
 * 缓存策略： - Key: file:content:<完整OSS_URL> - Value: 文件文本内容（UTF-8） - TTL: 24 小时
 *
 * 容错设计： - 所有 Redis 操作异常均被 catch，不向调用方传播 - 读异常返回 null（调用方回退到 OSS 直接下载） - 写异常记录日志后忽略
 *
 * Bean 创建条件: redis.enabled=true（默认） 当 redis.enabled=false 时，此 Bean 不创建， Spring 通过 ConditionalOnMissingBean 自动使用
 * NoOpFileContentCache */
@Component
@ConditionalOnProperty(name = "redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisFileContentCache implements FileContentCache {

    private static final Logger logger = LoggerFactory.getLogger(RedisFileContentCache.class);

    private static final String CACHE_KEY_PREFIX = "file:content:";
    private static final long CACHE_TTL_HOURS = 24;

    private final StringRedisTemplate stringRedisTemplate;

    public RedisFileContentCache(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public String get(String fileUrl) {
        try {
            String content = stringRedisTemplate.opsForValue().get(CACHE_KEY_PREFIX + fileUrl);
            if (content != null) {
                logger.debug("文件缓存命中: size={} chars", content.length());
            }
            return content;
        } catch (Exception e) {
            logger.warn("读取文件缓存失败，回退到 OSS 直接下载: url={}, error={}", fileUrl, e.getMessage());
            return null;
        }
    }

    @Override
    public void put(String fileUrl, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(CACHE_KEY_PREFIX + fileUrl, content, CACHE_TTL_HOURS, TimeUnit.HOURS);
            logger.debug("文件内容已缓存: size={} chars, ttl={}h", content.length(), CACHE_TTL_HOURS);
        } catch (Exception e) {
            logger.warn("写入文件缓存失败（不影响主流程）: url={}, error={}", fileUrl, e.getMessage());
        }
    }
}
