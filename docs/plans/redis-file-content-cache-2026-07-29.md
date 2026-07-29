# Plan: Redis 文件内容缓存实现

- **创建日期**: 2026-07-29
- **关联 Spec**: [redis-cache-infrastructure-2026-07-29](../specs/redis-cache-infrastructure-2026-07-29.md)
- **前置依赖**: 必须先完成 [oss-download-service-and-message-enrichment](./oss-download-service-and-message-enrichment-2026-07-29.md)（提供 `FileContentCache` 接口和 `OssDownloadService` 缓存接入点）
- **状态**: 待实施

---

## 一、实施概览

本 Plan 在 Plan 1 重构的基础上，实现真正的 Redis 文件内容缓存。所有修改均为增量——添加 Redis 基础设施 + 实现类，不修改 Plan 1 中已有的类。

| 步骤 | 内容 | 涉及文件数 |
|------|------|-----------|
| 1 | docker-compose 添加 Redis 服务 | 1 |
| 2 | 后端添加 Redis 依赖和配置 | 2 |
| 3 | 新建 `RedisConfig` | 1 |
| 4 | 新建 `RedisFileContentCache` | 1 |
| 5 | 验证与测试 | — |

---

## 二、详细步骤

### 步骤 1：docker-compose 添加 Redis 服务

**文件**: `deploy/docker-compose.yml`

#### 1a. 新增 volume

```yaml
volumes:
  postgres-data:
    driver: local
  rabbitmq-data:
    driver: local
  storage-data:
    driver: local
  gateway-logs:
    driver: local
  redis-data:                         # ← 新增
    driver: local
```

#### 1b. 新增 Redis 服务

在 `services:` 段落的 `rabbitmq` 之后添加：

```yaml
  # Redis 缓存服务
  redis:
    image: redis:7-alpine
    container_name: agentx-redis
    volumes:
      - redis-data:/data
    ports:
      - "${REDIS_PORT:-6379}:6379"
    networks:
      - agentx-network
    restart: unless-stopped
    command: >
      redis-server
      --appendonly yes
      --maxmemory 256mb
      --maxmemory-policy allkeys-lru
      --save 900 1
      --save 300 10
      --save 60 10000
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
```

#### 1c. 后端服务添加 Redis 依赖和环境变量

在 `agentx-backend` 服务中：

```yaml
  agentx-backend:
    environment:
      # ... 现有配置不变 ...

      # Redis 配置（新增）
      REDIS_HOST: ${REDIS_HOST:-redis}
      REDIS_PORT: ${REDIS_PORT:-6379}
      REDIS_ENABLED: ${REDIS_ENABLED:-true}

    depends_on:
      postgres:
        condition: service_healthy
        required: false
      rabbitmq:
        condition: service_healthy
      redis:                                    # ← 新增
        condition: service_healthy
```

> **本地非 Docker 开发**: 如果不通过 Docker 运行后端（直接 `mvn spring-boot:run`），需确保本地有 Redis 可用，或将 `REDIS_ENABLED` 设为 `false`。

---

### 步骤 2：后端添加 Redis 依赖和配置

#### 2a. Maven 依赖

**文件**: `AgentX/pom.xml`

```xml
<!-- Spring Data Redis（Lettuce 客户端，非阻塞） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

> `spring-boot-starter-data-redis` 默认使用 **Lettuce** 作为 Redis 客户端（基于 Netty，非阻塞、线程安全），无需额外引入连接池依赖。

#### 2b. 应用配置

**文件**: `AgentX/src/main/resources/application.yml`

在 `spring:` 段落中添加：

```yaml
spring:
  # ... 现有配置 ...
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 3000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 0

# Redis 功能开关（false 时使用 NoOpFileContentCache 降级）
redis:
  enabled: ${REDIS_ENABLED:true}
```

> **说明**: Spring Data Redis 的自动配置在 classpath 有依赖时默认启动。如果 `redis.enabled=false`，`RedisConfig` 和 `RedisFileContentCache` 中的 `@ConditionalOnProperty` 会阻止 Bean 创建，Spring 回退到 `NoOpFileContentCache`。**但 Spring Data Redis 的自动配置本身仍会尝试连接**。若需完全禁用（包括不尝试连接），还需在 `application.yml` 中排除自动配置：
> ```yaml
> spring:
>   autoconfigure:
>     exclude:
>       - org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
> ```
> 此排除项仅在 `redis.enabled=false` 时需要。可写在 profile-specific 配置中，如 `application-local.yml`。

---

### 步骤 3：新建 `RedisConfig`

**新文件**: `AgentX/src/main/java/org/xhy/infrastructure/config/RedisConfig.java`

```java
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
```

**设计要点**:
- 使用 `StringRedisTemplate` 而非泛型 `RedisTemplate<String, String>`：更简洁，Spring 官方推荐
- Key 和 Value 均为 String 序列化：在 Redis CLI 中可读，方便调试
- `@ConditionalOnProperty` 配合 `matchIfMissing = true`：未配置时默认启用

---

### 步骤 4：新建 `RedisFileContentCache`

**新文件**: `AgentX/src/main/java/org/xhy/infrastructure/storage/RedisFileContentCache.java`

```java
package org.xhy.infrastructure.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redis 文件内容缓存实现
 * 
 * 实现 FileContentCache 接口，使用 Redis 作为缓存后端。
 * 
 * 缓存策略：
 * - Key:   file:content:<完整OSS_URL>
 * - Value: 文件文本内容（UTF-8）
 * - TTL:   24 小时
 * 
 * 容错设计：
 * - 所有 Redis 操作异常均被 catch，不向调用方传播
 * - 读异常返回 null（调用方回退到 OSS 直接下载）
 * - 写异常记录日志后忽略
 * 
 * Bean 创建条件: redis.enabled=true（默认）
 * 当 redis.enabled=false 时，此 Bean 不创建，Spring 自动使用 NoOpFileContentCache
 */
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
                logger.debug("文件内容缓存命中: size={} chars, key={}", content.length(),
                        CACHE_KEY_PREFIX + fileUrl);
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
            stringRedisTemplate.opsForValue().set(
                    CACHE_KEY_PREFIX + fileUrl,
                    content,
                    CACHE_TTL_HOURS,
                    TimeUnit.HOURS);
            logger.debug("文件内容已缓存: size={} chars, ttl={}h, key={}",
                    content.length(), CACHE_TTL_HOURS, CACHE_KEY_PREFIX + fileUrl);
        } catch (Exception e) {
            logger.warn("写入文件缓存失败（不影响主流程）: url={}, error={}", fileUrl, e.getMessage());
        }
    }
}
```

**为什么用构造函数注入而非 `@Autowired`**:
- 符合 Spring 最佳实践
- 依赖显式声明，测试时可注入 mock
- `StringRedisTemplate` 是必需依赖（没有它 `RedisFileContentCache` 无法工作），构造函数注入确保不为 null

---

### 步骤 5：验证与测试

#### 5.1 基础设施验证

```bash
# 启动服务
cd deploy && docker compose up -d

# 确认 Redis 运行
docker compose ps redis
# NAME            STATUS
# agentx-redis    Up (healthy)

# 直接测试 Redis
docker exec agentx-redis redis-cli ping
# PONG

# 检查后端日志 - 应无 Redis 连接错误
docker compose logs agentx-backend | grep -i redis
# 预期: "Spring Data Redis - Using Lettuce" (INFO 级别)
```

#### 5.2 缓存功能验证

```
1. 打开 Agent 聊天框，上传一个 .txt 文件（内容唯一可辨识，如 "Hello Redis Test 12345"）
2. 发送消息 → Agent 应正确回复文件内容
3. 查看日志:
   - "文件缓存未命中，使用 OSS SDK 下载"  ← 第一次
   - "文件内容已缓存: size=XX chars, ttl=24h"
4. 在同一会话中再次发送相同文件（不同消息）:
   - "文件缓存命中"  ← 从 Redis 读取
   - 无 OSS 下载日志
5. 查看 Redis 中的缓存数据:
   docker exec agentx-redis redis-cli keys "file:content:*"
   docker exec agentx-redis redis-cli get "file:content:https://..." | head -c 200
```

#### 5.3 降级验证

```bash
# 1. 停止 Redis
docker compose stop redis

# 2. 发送带文件的消息 → 应正常从 OSS 下载（不因 Redis 不可用而失败）
#    日志: "读取文件缓存失败，回退到 OSS 直接下载"

# 3. 恢复 Redis
docker compose start redis
#    等待 healthcheck 通过后，缓存功能自动恢复
```

#### 5.4 完全禁用验证

```yaml
# 在 .env 或 docker-compose 环境变量中设置
REDIS_ENABLED=false
```

```bash
docker compose up -d
# 后端正常启动，日志中无 Redis 连接信息
# 发送带文件消息 → 正常从 OSS 下载（NoOpFileContentCache）
```

---

## 三、与后续场景的兼容性

本 Plan 实施后，项目具备了 Redis 基础设施。以下场景可直接复用 `StringRedisTemplate` 和 `RedisConfig`：

| 后续场景 | 复用方式 |
|---------|---------|
| 验证码 Redis 存储 | 新建 `RedisCodeStorage implements CodeStorage`，注入 `StringRedisTemplate` |
| 会话缓存 | 新建 `SessionCacheService`，注入 `StringRedisTemplate` |
| Token 黑名单 | 新建 `TokenBlacklistService`，使用 `StringRedisTemplate` 的 Set 操作 |
| 限流计数器 | 新建 `RateLimitService`，使用 `StringRedisTemplate` 的 INCR + EXPIRE |

---

## 四、风险与回滚

| 风险 | 等级 | 缓解措施 |
|------|------|---------|
| Redis 容器启动失败 | 低 | `FileContentCache` 接口 + NoOp 降级 |
| 缓存穿透（大量新文件同时请求） | 低 | 开发阶段文件量小；生产可加布隆过滤器 |
| Redis 内存溢出 | 低 | `maxmemory 256mb` + `allkeys-lru` 淘汰 |
| `redis.enabled=false` 时 Spring Data Redis 自动配置仍尝试连接 | 中 | 需同步排除 `RedisAutoConfiguration`，见步骤 2b 说明 |

**回滚方式**:
1. 设置 `REDIS_ENABLED=false` → 立即降级到 NoOpFileContentCache
2. `docker compose` 中注释掉 redis 服务
3. `git revert` 对应 commit（改动仅涉及新增文件 + pom.xml/yml，无现有逻辑修改）
