# Spec: Redis 缓存基础设施

- **创建日期**: 2026-07-29
- **状态**: 待实施
- **关联 Plans**:
  - [redis-file-content-cache-2026-07-29](../plans/redis-file-content-cache-2026-07-29.md)
- **前置 Spec**: [agent-chat-file-upload-architecture-refactor-2026-07-29](../specs/agent-chat-file-upload-architecture-refactor-2026-07-29.md)

---

## 一、背景

Plan [oss-download-service-and-message-enrichment](../plans/oss-download-service-and-message-enrichment-2026-07-29.md) 中已新建 `FileContentCache` 接口和 `NoOpFileContentCache` 空实现（降级用）。`OssDownloadService` 已通过构造函数注入 `FileContentCache`，具备缓存接入能力。

本 Spec 提供真正的 Redis 缓存实现，替换 NoOp 实现，达成以下目标：

1. **避免重复下载**：同一文件在多轮对话中只从 OSS 下载一次，后续从 Redis 缓存读取
2. **降低延迟**：本地/LAN Redis 访问延迟远低于公网 OSS 下载
3. **减轻 OSS 公网流量**：本地开发机通过公网访问 OSS 有带宽成本
4. **奠定基础设施**：为后续缓存场景（验证码、会话缓存、限流计数器等）铺路

## 二、当前项目 Redis 现状

| 项目 | 现状 |
|------|------|
| `pom.xml` | 无 `spring-boot-starter-data-redis` 依赖 |
| `application.yml` | 无 Redis 连接配置 |
| `docker-compose.yml` | 无 Redis 服务 |
| `CodeStorage` 接口 | 仅 `MemoryCodeStorage` 实现，接口已预留 Redis 扩展点 |
| `VerificationCodeConfig` | 已用 `@ConditionalOnMissingBean(name = "redisCodeStorage")` 预留 Redis 切换 |

## 三、技术设计

### 3.1 架构

```
OssDownloadService
    │
    ▼
FileContentCache (接口)
    ├── RedisFileContentCache   [redis.enabled=true, 默认]
    └── NoOpFileContentCache    [redis.enabled=false, 降级]
```

**缓存 Key 设计**:
```
Key:   file:content:<完整OSS_URL>
Val:   文件文本内容 (UTF-8, ≤50KB)
TTL:   24 小时
策略:  Cache-Aside（先查后存）
```

### 3.2 为什么选择 Cache-Aside

- 写入模式简单：不存在"先更新缓存还是先更新 OSS"的一致性问题（文件上传后内容不变）
- 降级友好：缓存故障时直接穿透到 OSS 下载，不影响核心功能
- TTL 过期后自动重新从 OSS 获取最新版本

### 3.3 Redis 配置

```yaml
# docker-compose 中的 Redis 实例
image: redis:7-alpine
persistence: AOF (appendonly yes)
maxmemory: 256mb
eviction: allkeys-lru
```

配置说明：
- `redis:7-alpine`：轻量镜像，适合开发环境
- `appendonly yes`：AOF 持久化，重启不丢数据
- `maxmemory 256mb`：适配开发机器资源
- `allkeys-lru`：内存满时淘汰最近最少使用的 key（文件缓存场景下合理）

### 3.4 降级策略

| 场景 | 行为 |
|------|------|
| Redis 服务不可用 | `RedisFileContentCache` 不创建 Bean → Spring 回退到 `NoOpFileContentCache` |
| Redis 连接超时 | `RedisFileContentCache.get()` catch 异常 → 返回 null → `OssDownloadService` 从 OSS 下载 |
| Redis 写入失败 | `RedisFileContentCache.put()` catch 异常 → 记录日志 → 不影响主流程 |
| `redis.enabled=false` | `RedisFileContentCache` 不创建 → `NoOpFileContentCache` 生效 |

## 四、影响范围

| 文件 | 操作 | 风险 |
|------|------|------|
| `pom.xml` | 添加 `spring-boot-starter-data-redis` | 低 |
| `application.yml` | 添加 Redis 连接 + 开关配置 | 低 |
| `deploy/docker-compose.yml` | 新增 redis 服务 + volume | 低 — 独立容器 |
| `infrastructure/config/RedisConfig.java` | 新增 | 低 |
| `infrastructure/storage/RedisFileContentCache.java` | 新增 | 低 |
| `AgentX/agentx-backend` 环境变量 | 新增 `REDIS_HOST`, `REDIS_PORT` | 低 |

## 五、验收标准

1. `docker compose up -d` → `agentx-redis` 容器 healthy
2. 后端启动无 Redis 连接错误
3. 第一次发送带 .txt 文件的消息 → OSS SDK 下载（日志可见），写入 Redis
4. 第二次发送同一文件 → Redis 命中（日志可见 `"文件缓存命中"`），无 OSS 下载日志
5. 停止 Redis → 发送消息 → 自动降级到 OSS 直接下载
6. 设置 `redis.enabled=false` → 后端仍正常启动和工作
7. `redis-cli keys "file:content:*"` 可查看缓存的 key
