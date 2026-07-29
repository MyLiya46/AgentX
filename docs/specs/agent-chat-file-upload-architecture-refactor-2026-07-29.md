# Spec: Agent 聊天文件上传架构重构

- **创建日期**: 2026-07-29
- **状态**: 待实施
- **关联 Plans**:
  - [oss-download-service-and-message-enrichment-2026-07-29](../plans/oss-download-service-and-message-enrichment-2026-07-29.md)
  - [redis-file-content-cache-2026-07-29](../plans/redis-file-content-cache-2026-07-29.md)

---

## 一、问题陈述

用户在 Agent 聊天框中上传 .txt 文件后，Agent 回复中完全未提及文件内容。经过全链路追踪，定位到三个缺陷，其中两个是架构层面的反模式：

| # | 缺陷 | 类型 |
|---|------|------|
| 1 | `HttpURLConnection` 无认证直连 OSS 下载文件，失败静默吞掉 | 架构缺陷 |
| 2 | `setupContextAndHistory()` 创建不持久化的临时 `MessageEntity` 混入历史消息列表传递文件内容 | 架构反模式 |
| 3 | `ChatRequest.message` 的 `@NotBlank` 校验与前端行为不一致 | Bug |

## 二、现有架构分析

### 2.1 当前文件内容传递路径

```
ChatRequest.fileUrls
    │
    ▼
ConversationAppService.setupContextAndHistory()
    │  创建临时 MessageEntity (id=null, role=USER, 仅含 fileUrls, 不持久化)
    │  混入 messageHistory 列表
    ▼
AbstractMessageHandler.buildHistoryMessage()
    │  遍历 messageHistory
    │  遇到临时实体 → convertFileUrlsToContents()
    │      → downloadTextFile()  [HttpURLConnection 无认证下载]
    │      → 失败返回 null → 静默跳过
    │  成功时创建 UserMessage 加入 memory
    ▼
agent.chat(userText)
    │  仅传文本，文件内容已在 memory 中（如果下载成功的话）
    ▼
LLM
```

### 2.2 架构设计问题

**问题 A — 下载方式脆弱**：`HttpURLConnection` 无 AccessKey 认证，完全依赖 OSS Bucket 级别的公共访问策略。Bucket 迁移、安全策略变更、公共访问被阻止等场景下立即失效。

**问题 B — 临时实体反模式**：将不持久化的对象混入持久化对象列表中，违反了"列表元素应具有统一生命周期"的原则。null ID 需要通过过滤代码来防御，增加了认知负担和维护成本。

**问题 C — 职责混乱**：`buildHistoryMessage()` 的职责是"构建历史消息到内存中"（见方法名），但它同时承担了下载文件内容、转换 Content 等与历史构建无关的职责。

**问题 D — 文件内容绑死历史通道**：文件内容只能通过 history message 传递，无法作为当前消息的一部分直接发送给 LLM。当用户说"总结这个文件"时，文件内容和指令出现在两条不同的消息中，语义上不连贯。

## 三、目标架构

### 3.1 新的文件内容传递路径

```
ChatRequest.fileUrls
    │
    ▼
AbstractMessageHandler.chat()
    │  createUserMessage() → DB 保存原始文本 + fileUrls
    │  enrichCurrentMessageWithFiles()            ← 新增步骤
    │      → OssDownloadService.downloadTextFiles()  ← OSS SDK + AccessKey 认证
    │      → FileContentCache.get() / put()         ← 可选缓存层
    │      → 更新 chatContext.userMessage（合并文本+文件内容）
    │  buildHistoryMessage()                      ← 不再处理文件内容
    │      → 历史消息仅使用文本内容
    │      → 历史消息若有 fileUrls，附加"该消息包含文件附件"提示
    │  agent.chat(enrichedMessage)                ← 文件内容与文本在同一消息中
    ▼
LLM
```

### 3.2 架构原则

1. **认证访问**：OSS 文件下载必须使用 OSS SDK + AccessKey，不依赖公共读权限
2. **职责单一**：`buildHistoryMessage()` 只构建历史，文件内容下载独立为 `OssDownloadService`
3. **就近注入**：文件内容在调用 LLM 之前、靠近调用点的位置注入当前消息，不绕道历史通道
4. **缓存可插拔**：缓存作为独立抽象层（接口 + 实现），通过构造器注入，Redis 不可用时自动降级
5. **构造函数注入**：不使用 `@Autowired(required = false)`，所有依赖通过构造函数显式声明

### 3.3 模块划分

```
infrastructure/storage/
├── OssDownloadService.java          # OSS SDK 下载服务（新增）
├── FileContentCache.java            # 缓存接口（新增）
├── RedisFileContentCache.java       # Redis 实现（新增）
├── NoOpFileContentCache.java        # 空实现/降级（新增）
└── OssUploadService.java            # 现有，不动
```

### 3.4 依赖关系

```
AbstractMessageHandler
    ├── OssDownloadService (构造函数注入)
    │   ├── FileContentCache (构造函数注入)
    │   │   ├── RedisFileContentCache   [redis.enabled=true]
    │   │   └── NoOpFileContentCache    [redis.enabled=false]
    │   └── OSS SDK (aliyun-oss-java-sdk)
    └── ... 现有依赖不变
```

## 四、影响范围

| 文件 | 操作 | 风险 |
|------|------|------|
| `pom.xml` | 添加 `aliyun-oss-java-sdk` 依赖 | 低 — 阿里云官方 SDK |
| `application.yml` | 无新增配置（复用现有 oss.* 配置） | 无风险 |
| `OssDownloadService.java` | 新增 | 低 |
| `FileContentCache.java` | 新增接口 | 低 |
| `AbstractMessageHandler.java` | 注入 OssDownloadService，新增 enrich 方法，简化 buildHistoryMessage | 中 — 修改核心流程 |
| `ConversationAppService.java` | 移除临时实体创建和过滤逻辑 | 低 |
| `ChatRequest.java` | 移除 `@NotBlank` | 低 |
| 4 个子类 Handler | 构造函数增加 OssDownloadService 参数 | 低 — 纯传递 |

## 五、验收标准

1. 发送带 .txt 文件的聊天消息 → Agent 回复中正确引用文件内容
2. OSS 文件下载使用 `OssDownloadService`（日志中可见 OSS SDK 调用），不再使用 `HttpURLConnection`
3. OSS Bucket 关闭"公共读"权限后，文件下载仍正常工作（因为有 AccessKey 认证）
4. 仅发送文件不输入文字 → 正常进入对话流程，不被 `@NotBlank` 拦截
5. 历史消息加载后，`activeMessages` 列表中不含 `null` ID
6. 多轮对话中，历史消息若有文件附件，Agent 能看到"该消息包含文件附件"提示
7. 单元测试覆盖 `OssDownloadService` 和 `enrichCurrentMessageWithFiles()` 核心路径
