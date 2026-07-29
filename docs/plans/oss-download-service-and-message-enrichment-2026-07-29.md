# Plan: OSS 下载服务 + 消息富化架构重构

- **创建日期**: 2026-07-29
- **关联 Spec**: [agent-chat-file-upload-architecture-refactor-2026-07-29](../specs/agent-chat-file-upload-architecture-refactor-2026-07-29.md)
- **前置依赖**: 无（可独立实施，Redis 缓存为后续增强）
- **状态**: 待实施

---

## 一、实施概览

本 Plan 实施 Spec 中的核心架构重构，分 6 步进行。每一步完成后的代码均可编译通过。

| 步骤 | 内容 | 涉及文件数 |
|------|------|-----------|
| 1 | 添加 Maven 依赖 | 1 |
| 2 | 新建 `OssDownloadService` | 1 |
| 3 | 新建 `FileContentCache` 接口 + `NoOpFileContentCache` | 2 |
| 4 | 重构 `AbstractMessageHandler` | 1 |
| 5 | 修改 4 个子类 Handler 构造函数 | 4 |
| 6 | 清理 `ConversationAppService` + 修复 `ChatRequest.@NotBlank` | 2 |

---

## 二、详细步骤

### 步骤 1：添加 Maven 依赖

**文件**: `AgentX/pom.xml`

在 `<dependencies>` 中添加 OSS SDK：

```xml
<!-- 阿里云 OSS SDK（官方认证下载，替换 HttpURLConnection） -->
<dependency>
    <groupId>com.aliyun.oss</groupId>
    <artifactId>aliyun-sdk-oss</artifactId>
    <version>3.17.4</version>
</dependency>
```

> **版本说明**: 3.17.4 是当前稳定版本。OSS SDK 与项目已有的 `OssUploadService`（使用手动签名）互补——SDK 用于服务端下载，手动签名用于前端直传凭证生成。

---

### 步骤 2：新建 `OssDownloadService`

**新文件**: `AgentX/src/main/java/org/xhy/infrastructure/storage/OssDownloadService.java`

```java
package org.xhy.infrastructure.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.OSSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.xhy.infrastructure.config.OssProperties;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OSS 文件下载服务
 * 
 * 使用 OSS SDK + AccessKey 认证下载文件内容，替代原有的
 * {@code HttpURLConnection} 匿名访问方式。
 * 
 * 设计原则：
 * - 认证访问：不依赖 Bucket 级别公共读权限，通过 AccessKey 签名访问
 * - 批量下载：一次下载多个文件，返回 URL → 内容 映射
 * - 缓存可插拔：通过 FileContentCache 接口接入缓存层（NoOp 或 Redis）
 * - 失败隔离：单个文件下载失败不影响其他文件
 */
@Service
public class OssDownloadService {

    private static final Logger logger = LoggerFactory.getLogger(OssDownloadService.class);

    /** 文本文件扩展名集合 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "csv", "log", "json", "xml", "yml", "yaml",
            "html", "htm", "js", "ts", "java", "py", "css", "sql",
            "sh", "bat", "ini", "cfg", "conf");

    /** 图片文件扩展名集合（直接传 URL，不需下载） */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico");

    /** 单文件最大下载大小：2MB */
    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024;

    /** 文本内容最大长度：50000 字符 */
    private static final int MAX_CONTENT_LENGTH = 50000;

    private final OssProperties ossProperties;
    private final FileContentCache fileContentCache;

    public OssDownloadService(OssProperties ossProperties, FileContentCache fileContentCache) {
        this.ossProperties = ossProperties;
        this.fileContentCache = fileContentCache;
    }

    /**
     * 批量下载文本文件内容
     * 
     * 优先从缓存获取，缓存未命中时通过 OSS SDK 下载并回写缓存。
     * 图片和二进制文件不在此方法处理。
     *
     * @param fileUrls OSS 文件完整 URL 列表
     * @return URL → 文件内容 映射（下载失败的文件不在 Map 中）
     */
    public Map<String, String> downloadTextFiles(List<String> fileUrls) {
        if (fileUrls == null || fileUrls.isEmpty()) {
            return Collections.emptyMap();
        }

        // 筛选出文本文件（图片传 URL、二进制告知文件名）
        List<String> textUrls = fileUrls.stream()
                .filter(url -> TEXT_EXTENSIONS.contains(getExtension(url)))
                .collect(Collectors.toList());

        if (textUrls.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> result = new LinkedHashMap<>();
        OSS ossClient = null;

        try {
            ossClient = createOssClient();

            for (String fileUrl : textUrls) {
                String content = downloadSingleFile(ossClient, fileUrl);
                if (content != null) {
                    result.put(fileUrl, content);
                }
            }
        } catch (Exception e) {
            logger.error("创建 OSS 客户端或批量下载文件时发生异常", e);
        } finally {
            if (ossClient != null) {
                try {
                    ossClient.shutdown();
                } catch (Exception ignored) {
                }
            }
        }

        return result;
    }

    /**
     * 下载单个文件（含缓存逻辑）
     */
    private String downloadSingleFile(OSS ossClient, String fileUrl) {
        // 1. 查缓存
        String cached = fileContentCache.get(fileUrl);
        if (cached != null) {
            logger.debug("文件缓存命中: {}", fileUrl);
            return cached;
        }

        // 2. 缓存未命中，通过 OSS SDK 下载
        logger.debug("文件缓存未命中，使用 OSS SDK 下载: {}", fileUrl);
        String objectKey = extractObjectKey(fileUrl);
        if (objectKey == null) {
            logger.error("无法从 URL 提取 OSS ObjectKey: {}", fileUrl);
            return null;
        }

        try {
            OSSObject ossObject = ossClient.getObject(ossProperties.getBucketName(), objectKey);

            long contentLength = ossObject.getObjectMetadata().getContentLength();
            if (contentLength > MAX_FILE_SIZE) {
                logger.warn("文件过大，跳过下载: {} ({} bytes)", fileUrl, contentLength);
                ossObject.close();
                return null;
            }

            // 读取文本内容
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(ossObject.getObjectContent(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (sb.length() > MAX_CONTENT_LENGTH) {
                        sb.setLength(MAX_CONTENT_LENGTH);
                        sb.append("\n\n... (内容已截断)");
                        break;
                    }
                }
            }
            ossObject.close();

            String content = sb.toString();
            if (content.length() > MAX_CONTENT_LENGTH) {
                content = content.substring(0, MAX_CONTENT_LENGTH) + "\n\n... (内容已截断)";
            }

            // 3. 回写缓存
            fileContentCache.put(fileUrl, content);

            logger.info("OSS 文件下载成功: {} ({} chars)", fileUrl, content.length());
            return content;

        } catch (Exception e) {
            logger.error("OSS 文件下载失败: URL={}, 错误={}", fileUrl, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 构建供 LLM 使用的用户消息内容
     * 
     * 将原始用户文本与文件内容合并为一条完整消息。
     *
     * @param userText    用户输入的文本（可为空）
     * @param fileUrls    文件 URL 列表
     * @param downloaded  已下载的文件内容映射
     * @return 合并后的消息字符串
     */
    public String buildEnrichedMessage(String userText, List<String> fileUrls,
                                        Map<String, String> downloaded) {
        StringBuilder sb = new StringBuilder();

        // 用户文本
        if (userText != null && !userText.isBlank()) {
            sb.append(userText);
        }

        // 文件内容附件
        if (fileUrls != null && !fileUrls.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }

            for (String fileUrl : fileUrls) {
                String ext = getExtension(fileUrl);
                String fileName = fileUrl.substring(fileUrl.lastIndexOf('/') + 1);

                if (downloaded.containsKey(fileUrl)) {
                    // 文本文件：附加内容
                    sb.append("【用户上传文件: ").append(fileName).append("】\n\n");
                    sb.append(downloaded.get(fileUrl));
                    sb.append("\n\n【文件内容结束】\n");
                } else if (IMAGE_EXTENSIONS.contains(ext)) {
                    // 图片：告知模型（URL 由 langchain4j ImageContent 处理）
                    sb.append("用户上传了图片: ").append(fileName).append("\n");
                } else if (TEXT_EXTENSIONS.contains(ext)) {
                    // 文本文件下载失败
                    sb.append("用户上传了文件: ").append(fileName)
                            .append("（系统无法读取该文件内容，请告知用户此问题）\n");
                    logger.error("文本文件下载失败，已通知 LLM: url={}", fileUrl);
                } else {
                    // 其他二进制文件（PDF、docx 等）
                    sb.append("用户上传了文件: ").append(fileName)
                            .append("（").append(ext).append(" 格式，访问地址：")
                            .append(fileUrl).append("）\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * 从完整 URL 中提取 OSS ObjectKey
     * 
     * URL 格式: https://{bucket}.{endpoint}/{objectKey}
     * ObjectKey 示例: agent/2026/07/29/1234567890_abc123.txt
     */
    String extractObjectKey(String fileUrl) {
        try {
            // 构建 bucket endpoint 前缀
            String prefix = ossProperties.getBucketName() + "." 
                    + ossProperties.getEndpoint().replace("https://", "");
            int idx = fileUrl.indexOf(prefix);
            if (idx < 0) {
                return null;
            }
            // objectKey 从 prefix 之后开始（跳过 "/"）
            String objectKey = fileUrl.substring(idx + prefix.length() + 1);
            // 去掉可能的查询参数
            int queryIdx = objectKey.indexOf('?');
            if (queryIdx > 0) {
                objectKey = objectKey.substring(0, queryIdx);
            }
            return objectKey;
        } catch (Exception e) {
            logger.warn("提取 ObjectKey 失败: {}", fileUrl, e);
            return null;
        }
    }

    /**
     * 获取文件扩展名（小写，不含点号）
     */
    static String getExtension(String url) {
        String path = url.toLowerCase();
        int queryIdx = path.indexOf('?');
        if (queryIdx > 0) {
            path = path.substring(0, queryIdx);
        }
        int dotIdx = path.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx >= path.length() - 1) {
            return "";
        }
        return path.substring(dotIdx + 1);
    }

    private OSS createOssClient() {
        String endpoint = ossProperties.getEndpoint();
        return new OSSClientBuilder().build(
                endpoint,
                ossProperties.getAccessKey(),
                ossProperties.getSecretKey());
    }
}
```

**关键设计决策**:

| 决策 | 理由 |
|------|------|
| `extractObjectKey()` 从 URL 反解 ObjectKey | 避免在前端和后端之间额外传递 ObjectKey，保持接口简单 |
| 单个文件下载失败不抛异常，记录日志并跳过 | 批量下载场景下部分成功优于全失败 |
| `buildEnrichedMessage()` 合并文本与文件内容为一条消息 | Agent 看到的是连贯的上下文，而非分裂的多条消息 |
| `FileContentCache` 通过构造函数注入 | 显式依赖，便于测试和切换实现 |
| 关闭 `OSSObject` 使用 try-finally | 防止连接泄漏 |

---

### 步骤 3：新建 `FileContentCache` 接口 + `NoOpFileContentCache`

**新文件**: `AgentX/src/main/java/org/xhy/infrastructure/storage/FileContentCache.java`

```java
package org.xhy.infrastructure.storage;

/**
 * 文件内容缓存接口
 * 
 * 定义文件内容缓存的抽象，支持多种实现（Redis、NoOp 等）。
 * 所有实现必须保证异常不抛出——缓存失败不应阻塞主流程。
 */
public interface FileContentCache {

    /**
     * 获取缓存的文件内容
     *
     * @param fileUrl 文件 URL 作为缓存 Key
     * @return 缓存内容，未命中返回 null
     */
    String get(String fileUrl);

    /**
     * 缓存文件内容
     *
     * @param fileUrl 文件 URL 作为缓存 Key
     * @param content 文件文本内容
     */
    void put(String fileUrl, String content);
}
```

**新文件**: `AgentX/src/main/java/org/xhy/infrastructure/storage/NoOpFileContentCache.java`

```java
package org.xhy.infrastructure.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 空操作缓存实现
 * 
 * 当 Redis 不可用或未配置时使用，所有操作均为空操作。
 * 通过 @ConditionalOnMissingBean 确保仅在无其他缓存实现时生效。
 */
@Component
@ConditionalOnMissingBean(FileContentCache.class)
public class NoOpFileContentCache implements FileContentCache {

    @Override
    public String get(String fileUrl) {
        return null;
    }

    @Override
    public void put(String fileUrl, String content) {
        // 无操作
    }
}
```

> **说明**: `@ConditionalOnMissingBean` 配合 `RedisFileContentCache`（Plan 2 中实现）的 `@ConditionalOnProperty(name = "redis.enabled", havingValue = "true")`，实现自动降级：Redis 启用时使用 Redis 实现，否则使用 NoOp。

---

### 步骤 4：重构 `AbstractMessageHandler`

**文件**: `AgentX/src/main/java/org/xhy/application/conversation/service/message/AbstractMessageHandler.java`

#### 4a. 注入 OssDownloadService

```java
// 在字段声明区域添加
protected final OssDownloadService ossDownloadService;

// 修改构造函数签名
public AbstractMessageHandler(LLMServiceFactory llmServiceFactory,
        MessageDomainService messageDomainService,
        HighAvailabilityDomainService highAvailabilityDomainService,
        SessionDomainService sessionDomainService,
        UserSettingsDomainService userSettingsDomainService,
        LLMDomainService llmDomainService,
        BuiltInToolRegistry builtInToolRegistry,
        BillingService billingService,
        AccountDomainService accountDomainService,
        ChatSessionManager chatSessionManager,
        OssDownloadService ossDownloadService) {              // ← 新增
    // ... 现有赋值 ...
    this.ossDownloadService = ossDownloadService;              // ← 新增
}
```

#### 4b. 在 `chat()` 模板方法中增加文件富化步骤

**位置**: 约 L127-L135

```java
public <T> T chat(ChatContext chatContext, MessageTransport<T> transport) {
    T connection = transport.createConnection(CONNECTION_TIMEOUT);
    onChatStart(chatContext);
    checkBalanceBeforeChat(chatContext.getUserId(), transport, connection);

    // 4. 创建消息实体（此时保存原始用户文本到 DB）
    MessageEntity llmMessageEntity = createLlmMessage(chatContext);
    MessageEntity userMessageEntity = createUserMessage(chatContext);
    onUserMessageProcessed(chatContext, userMessageEntity);

    // 4.5 富化当前消息：下载文件内容，合并到 chatContext.userMessage
    //     必须在 initMemory / buildHistoryMessage 之前，因为 buildHistoryMessage
    //     不再处理文件内容（仅处理历史消息文本）
    enrichCurrentMessageWithFiles(chatContext, transport, connection);

    // 6. 初始化聊天内存
    MessageWindowChatMemory memory = initMemory();

    // 7. 构建历史消息（不含文件内容下载）
    buildHistoryMessage(chatContext, memory);

    // 8-9 不变
    ToolProvider toolProvider = provideTools(chatContext);
    if (chatContext.isStreaming()) {
        processStreamingChat(chatContext, connection, transport, userMessageEntity,
                llmMessageEntity, memory, toolProvider);
    } else {
        processSyncChat(chatContext, connection, transport, userMessageEntity,
                llmMessageEntity, memory, toolProvider);
    }
    return connection;
}
```

#### 4c. 新增 `enrichCurrentMessageWithFiles()` 方法

```java
/**
 * 富化当前用户消息：下载文件内容并合并到 chatContext.userMessage 中
 * 
 * 此方法取代了原有的"临时 MessageEntity 混入 messageHistory → buildHistoryMessage
 * → convertFileUrlsToContents → downloadTextFile" 的复杂链路。
 * 
 * 文件内容直接附加到当前消息文本尾部，使得 Agent 在一次 chat() 调用中
 * 同时看到用户文本和文件内容，语义连贯。
 *
 * @param chatContext 对话上下文
 * @param transport   消息传输器（用于发送文件下载失败的用户提示）
 * @param connection  连接对象
 */
private void enrichCurrentMessageWithFiles(ChatContext chatContext,
        MessageTransport<?> transport, Object connection) {
    List<String> fileUrls = chatContext.getFileUrls();
    if (fileUrls == null || fileUrls.isEmpty()) {
        return;
    }

    // 1. 下载文本文件内容
    Map<String, String> downloaded = ossDownloadService.downloadTextFiles(fileUrls);

    // 2. 构建富化消息
    String originalText = chatContext.getUserMessage();
    String enrichedMessage = ossDownloadService.buildEnrichedMessage(originalText, fileUrls, downloaded);

    // 3. 更新 chatContext 中的消息（DB 中的 userMessageEntity 仍保留原始文本）
    chatContext.setUserMessage(enrichedMessage);

    // 4. 检查是否有下载失败的文本文件，通知用户
    List<String> failedTextFiles = fileUrls.stream()
            .filter(url -> {
                String ext = OssDownloadService.getExtension(url);
                return Set.of("txt", "md", "csv", "log", "json", "xml", "yml", "yaml",
                        "html", "htm", "js", "ts", "java", "py", "css", "sql",
                        "sh", "bat", "ini", "cfg", "conf").contains(ext);
            })
            .filter(url -> !downloaded.containsKey(url))
            .collect(Collectors.toList());

    if (!failedTextFiles.isEmpty()) {
        logger.error("部分文本文件下载失败: {}", failedTextFiles);
        // 通过 SSE 发送提示给前端
        if (transport != null && connection != null) {
            transport.sendMessage(connection,
                    AgentChatResponse.buildWarning(
                            "部分文件读取失败，Agent 可能无法完整感知这些文件: "
                                    + failedTextFiles.stream()
                                    .map(u -> u.substring(u.lastIndexOf('/') + 1))
                                    .collect(Collectors.joining(", "))));
        }
    }
}
```

> **注意**: `AgentChatResponse.buildWarning()` 需要确认是否存在。若不存在，需要新增一个静态工厂方法或直接在 `MessageType` 中增加 `WARNING` 类型。如果不方便，可暂时使用 `logger.error` 记录 + Agent 消息内的降级提示，SSE 提示作为后续优化。

#### 4d. 简化 `buildHistoryMessage()`

**位置**: 约 L511-L561

**改动**: 移除文件内容下载逻辑，历史消息仅处理文本内容。

```java
protected void buildHistoryMessage(ChatContext chatContext, MessageWindowChatMemory memory) {
    // ... 摘要、系统提示词构建（不变）...

    List<MessageEntity> messageHistory = chatContext.getMessageHistory();
    for (MessageEntity messageEntity : messageHistory) {
        if (messageEntity.isUserMessage()) {
            String textContent = messageEntity.getContent();
            List<String> historyFileUrls = messageEntity.getFileUrls();

            if (StringUtils.isNotEmpty(textContent)) {
                // 历史消息有文本内容
                if (historyFileUrls != null && !historyFileUrls.isEmpty()) {
                    // 若该消息原本包含文件附件，附加简短提示
                    String fileHint = historyFileUrls.stream()
                            .map(u -> u.substring(u.lastIndexOf('/') + 1))
                            .collect(Collectors.joining(", "));
                    memory.add(new UserMessage(textContent
                            + "\n（该消息包含文件附件: " + fileHint + "）"));
                } else {
                    memory.add(new UserMessage(textContent));
                }
            } else if (historyFileUrls != null && !historyFileUrls.isEmpty()) {
                // 历史消息仅有文件无文本（文件内容已在当时发送给 LLM）
                String fileHint = historyFileUrls.stream()
                        .map(u -> u.substring(u.lastIndexOf('/') + 1))
                        .collect(Collectors.joining(", "));
                memory.add(new UserMessage("用户上传了文件: " + fileHint));
            }
        } else if (messageEntity.isAIMessage()) {
            memory.add(new AiMessage(messageEntity.getContent()));
        } else if (messageEntity.isSystemMessage()) {
            memory.add(new SystemMessage(messageEntity.getContent()));
        }
    }
}
```

> **注意**: 当前代码中 `buildHistoryMessage` 还处理了 `ImageContent.from(url)` 和 `TextContent.from(...)` 的多模态内容。文本文件内容已经通过 `enrichCurrentMessageWithFiles` 处理为纯文本附加到 `chatContext.userMessage` 中了。**图片文件**仍需通过 langchain4j 的 `ImageContent.from(url)` 方式传递，不在本次重构范围内（当前代码中图片已经可以正常工作）。

#### 4e. 删除废弃方法

移除以下方法（职责已转移到 `OssDownloadService`）：
- `downloadTextFile(String)` — 约 L616-L653
- `convertFileUrlsToContents(List<String>)` — 约 L565-L598
- `getFileExtension(String)` — 约 L601-L613（保留，标记为 `@Deprecated` 或直接删除）

> **检查**: 确认这三个方法是否为 `private`，非 `protected`/`public`。根据之前阅读的代码，它们都是 `private`，直接删除不影响子类。

---

### 步骤 5：修改 4 个子类 Handler 构造函数

所有子类构造函数仅需增加 `OssDownloadService` 参数并透传给 `super()`。

**涉及文件**:
1. `AgentMessageHandler.java`
2. `ChatMessageHandler.java`
3. `PreviewMessageHandler.java`
4. `RagMessageHandler.java`

**修改模式（以 AgentMessageHandler 为例）**:

```java
// === 修改前 ===
public AgentMessageHandler(LLMServiceFactory llmServiceFactory,
        MessageDomainService messageDomainService,
        HighAvailabilityDomainService highAvailabilityDomainService,
        SessionDomainService sessionDomainService,
        UserSettingsDomainService userSettingsDomainService,
        LLMDomainService llmDomainService,
        BuiltInToolRegistry builtInToolRegistry,
        BillingService billingService,
        AccountDomainService accountDomainService,
        ChatSessionManager chatSessionManager,
        TraceCollector traceCollector,
        AgentToolManager agentToolManager) {
    super(llmServiceFactory, messageDomainService, highAvailabilityDomainService,
            sessionDomainService, userSettingsDomainService, llmDomainService,
            builtInToolRegistry, billingService, accountDomainService, chatSessionManager);
    this.agentToolManager = agentToolManager;
}

// === 修改后 ===
public AgentMessageHandler(LLMServiceFactory llmServiceFactory,
        MessageDomainService messageDomainService,
        HighAvailabilityDomainService highAvailabilityDomainService,
        SessionDomainService sessionDomainService,
        UserSettingsDomainService userSettingsDomainService,
        LLMDomainService llmDomainService,
        BuiltInToolRegistry builtInToolRegistry,
        BillingService billingService,
        AccountDomainService accountDomainService,
        ChatSessionManager chatSessionManager,
        OssDownloadService ossDownloadService,                          // ← 新增
        TraceCollector traceCollector,
        AgentToolManager agentToolManager) {
    super(llmServiceFactory, messageDomainService, highAvailabilityDomainService,
            sessionDomainService, userSettingsDomainService, llmDomainService,
            builtInToolRegistry, billingService, accountDomainService,
            chatSessionManager, ossDownloadService);                    // ← 新增透传
    this.agentToolManager = agentToolManager;
}
```

**`TracingMessageHandler`（抽象中间类）**: 也需同步修改构造函数，增加 `OssDownloadService` 参数。检查当前已有子类（`AgentMessageHandler` 等）是否直接继承它。

---

### 步骤 6：清理 `ConversationAppService` + 修复 `ChatRequest.@NotBlank`

#### 6a. 移除临时实体创建

**文件**: `AgentX/src/main/java/org/xhy/application/conversation/service/ConversationAppService.java`

**位置**: `setupContextAndHistory()` 约 L343-L350

```java
// === 删除以下代码块 ===
// 特殊处理当前对话的文件，因为在后续的对话中无法发送文件
List<String> fileUrls = chatRequest.getFileUrls();
if (!fileUrls.isEmpty()) {
    MessageEntity messageEntity = new MessageEntity();
    messageEntity.setRole(Role.USER);
    messageEntity.setFileUrls(fileUrls);
    messageEntities.add(messageEntity);
}
```

#### 6b. 移除 null ID 过滤逻辑

**位置**: `saveMessageAndUpdateContext()` 约 L321-L323

```java
// === 修改前 ===
List<String> activeMessages = chatContext.getMessageHistory().stream()
        .filter(Objects::nonNull)
        .sorted(Comparator.comparing(MessageEntity::getCreatedAt,
            Comparator.nullsFirst(Comparator.naturalOrder())))
        .map(MessageEntity::getId)
        .collect(Collectors.toList());

// === 修改后（移除 .filter(m -> m.getId() != null && !m.getId().startsWith("temp-file-"))） ===
// 由于临时实体已被移除，所有历史消息 ID 均有效，无需额外过滤
List<String> activeMessages = chatContext.getMessageHistory().stream()
        .filter(Objects::nonNull)
        .sorted(Comparator.comparing(MessageEntity::getCreatedAt,
            Comparator.nullsFirst(Comparator.naturalOrder())))
        .map(MessageEntity::getId)
        .collect(Collectors.toList());
```

#### 6c. 修复 `@NotBlank`

**文件**: `AgentX/src/main/java/org/xhy/application/conversation/dto/ChatRequest.java`

```java
// === 修改前 ===
@NotBlank(message = "消息内容不可为空")
private String message;

// === 修改后 ===
/** 消息内容（与 fileUrls 至少一个非空，业务校验在 ConversationAppService.chat() 中完成） */
private String message;
```

**文件**: `ConversationAppService.java` `chat()` 方法入口

```java
public SseEmitter chat(ChatRequest chatRequest, String userId) {
    // 业务校验：message 和 fileUrls 至少一个非空
    if (StringUtils.isBlank(chatRequest.getMessage())
            && (chatRequest.getFileUrls() == null || chatRequest.getFileUrls().isEmpty())) {
        throw new BusinessException("消息内容不可为空");
    }
    // ... 其余不变
}
```

> `AgentPreviewRequest` 使用 `userMessage` 字段且无 `@NotBlank`，不受影响。

---

## 三、编译顺序与依赖关系

```
步骤 1 (pom.xml)                          ← 必须先执行（OSS SDK 依赖）
    │
步骤 2 (OssDownloadService)               ← 依赖 OSS SDK
    │
步骤 3 (FileContentCache + NoOpCache)     ← 独立，不依赖其他新增
    │
步骤 4 + 5 (AbstractMessageHandler + 子类) ← 依赖步骤 2、3
    │
步骤 6 (ConversationAppService清理)       ← 依赖步骤 4（需先删除 downloadTextFile）
```

推荐顺序：1 → 2 → 3 → 4 → 5 → 6，每个步骤完成后 `mvn compile` 验证。

---

## 四、测试计划

### 4.1 单元测试

**`OssDownloadServiceTest`**:
- `extractObjectKey` 正常 URL → 正确提取 key
- `extractObjectKey` 含查询参数 URL → 正确提取 key
- `extractObjectKey` 非 OSS URL → 返回 null
- `getExtension` txt/pdf/jpg/无扩展 → 正确返回
- `downloadTextFiles` 空列表 → 返回空 Map
- `downloadTextFiles` OSS 不可达 → 不抛异常，返回空 Map
- `buildEnrichedMessage` 仅有文本 → 返回原文
- `buildEnrichedMessage` 文本+文件内容 → 正确合并
- `buildEnrichedMessage` 仅文件 → 仅含文件内容块
- `buildEnrichedMessage` 文件下载失败 → 含错误提示

**`AbstractMessageHandlerTest`**:
- `enrichCurrentMessageWithFiles` 无文件 → userMessage 不变
- `enrichCurrentMessageWithFiles` 有文件 → userMessage 被富化且包含文件内容

### 4.2 集成测试

1. 启动完整服务栈
2. 上传 .txt 文件到 Agent 聊天框，发送消息 → Agent 回复正确引用文件内容
3. 仅上传文件不输入文字，发送 → 正常处理
4. 上传图片文件 → 仍正常（图片走 langchain4j ImageContent 路径，不受影响）
5. 多轮对话 → 历史消息中文件附件显示"该消息包含文件附件"提示

---

## 五、风险与回滚

| 风险 | 等级 | 缓解措施 |
|------|------|---------|
| OSS SDK 依赖版本冲突 | 低 | aliyun-sdk-oss 是独立 SDK，依赖项少，不易冲突 |
| 删除方法被其他处引用 | 低 | 步骤前使用 IDE "Find Usages" 确认 `downloadTextFile` / `convertFileUrlsToContents` / `getFileExtension` 未被外部调用 |
| processSyncChat 未使用文件内容 | 低 | processSyncChat 使用 `chatContext.getUserMessage()`（已富化），路径一致 |
| `buildEnrichedMessage` 中图片路径未正确处理 | 低 | 当前图片走 langchain4j `ImageContent.from(url)`，不在 `buildEnrichedMessage` 文本附加中处理 |

**回滚方式**: `git revert` 对应 commit。架构重构不涉及数据库 schema 变更，纯代码改动。
