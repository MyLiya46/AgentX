# AgentX 记忆与对话上下文管理技术方案

## 1. 背景与问题

### 1.1 LLM API 的无状态特性

大语言模型（LLM）的 API（如 OpenAI、Anthropic 等）是**完全无状态**的。每次 API 调用都是独立的推理过程：

```
f(messages[]) → response
```

同一个程序、同一个地址、同一个模型调用两次，第二次调用不会"记得"第一次说了什么。服务端不维护任何会话状态。

这意味着：**任何有状态的对话应用，都必须在应用层自行维护对话历史**。

### 1.2 核心挑战

构建有记忆的 Agent 系统需要解决三个层次的问题：

| 层次 | 挑战 | AgentX 方案 |
|------|------|-------------|
| 单次调用 | 如何组装完整的 messages[] 发给 LLM | `AbstractMessageHandler.buildHistoryMessage()` |
| 会话上下文 | 对话变长后如何控制 Token 不超限 | 滑动窗口 / 摘要策略 |
| 跨会话记忆 | 如何让 Agent 记住跨会话的信息 | 异步抽取 + 向量检索 + 注入 System Prompt |

---

## 2. 系统架构概览

```
┌──────────────────────────────────────────────────────────────────┐
│                     记忆与上下文管理架构                           │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ 第三层：长期记忆 (Long-term Memory)                         │ │
│  │ 存储：PostgreSQL (memory_items) + PgVector 向量库          │ │
│  │ 写入：对话完成后 LLM 抽取要点 → 向量化 → 持久化             │ │
│  │ 读取：语义检索 Top-5 → 注入 System Prompt                   │ │
│  │ 核心类：MemoryExtractorService / MemoryDomainService        │ │
│  └────────────────────────────────────────────────────────────┘ │
│                            ↑↓                                    │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ 第二层：会话上下文 (Session Context)                        │ │
│  │ 存储：PostgreSQL (messages + context 表)                    │ │
│  │ 策略1 - 滑动窗口：Token 超限时丢弃旧消息                     │ │
│  │ 策略2 - 摘要压缩：旧消息 → LLM 摘要，保留最新 N 条          │ │
│  │ 核心类：TokenDomainService / TokenOverflowStrategy 系列      │ │
│  └────────────────────────────────────────────────────────────┘ │
│                            ↑↓                                    │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ 第一层：单次请求 (In-Memory)                                │ │
│  │ 存储：JVM 内存 (InMemoryChatMemoryStore)                    │ │
│  │ 机制：从 DB 加载历史 → 组装 messages[] → 发送 LLM           │ │
│  │ 核心类：AbstractMessageHandler / ConversationAppService     │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

---

## 3. 数据库设计

### 3.1 会话表 `sessions`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR | 会话唯一ID (UUID) |
| title | VARCHAR | 会话标题（首次对话后由 LLM 自动生成） |
| user_id | VARCHAR | 用户ID |
| agent_id | VARCHAR | 关联的 Agent ID |

### 3.2 消息表 `messages`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR | 消息唯一ID (UUID) |
| session_id | VARCHAR | 所属会话ID |
| role | VARCHAR | USER / ASSISTANT / SYSTEM / SUMMARY |
| content | TEXT | 消息内容 |
| token_count | INTEGER | 总 Token 数 |
| body_token_count | INTEGER | 本体 Token 数（不含上下文部分） |
| provider | VARCHAR | 服务提供商 |
| model | VARCHAR | 使用的模型标识 |
| file_urls | JSON | 文件URL列表（多模态支持） |
| created_at | TIMESTAMP | 创建时间 |

### 3.3 上下文表 `context`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR | 上下文唯一ID |
| session_id | VARCHAR | 所属会话ID |
| active_messages | JSON | **活跃消息ID列表**（核心字段，定义当前上下文窗口） |
| summary | TEXT | 摘要内容（使用摘要策略时填充） |

### 3.4 记忆表 `memory_items`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR | 记忆条目ID |
| user_id | VARCHAR | 所属用户 |
| type | VARCHAR | PROFILE / TASK / FACT / EPISODIC |
| text | TEXT | 记忆文本 |
| data | JSON | 额外结构化数据 |
| importance | FLOAT | 重要性 0.0-1.0 |
| tags | JSON | 标签列表 |
| source_session_id | VARCHAR | 来源会话ID |
| dedupe_hash | VARCHAR | 去重哈希 (SHA-256) |
| status | INTEGER | 1=active, 0=archived |

### 3.5 记忆向量存储

使用 PgVector 扩展，表名 `public.memory_vector_store`。Metadata 包含 `USER_ID`、`ITEM_ID`、`MEMORY_TYPE`、`TAGS`、`STATUS`，用于过滤和检索。

---

## 4. 对话历史管理（短期记忆）

### 4.1 核心数据流

```
用户请求
  │
  ▼
ConversationAppService.chat()
  │
  ├─ 1. prepareEnvironment()          准备对话环境
  │    ├─ 加载 Session、Agent 信息
  │    ├─ 确定模型和服务商（含高可用/降级）
  │    └─ setupContextAndHistory()     加载上下文和历史
  │         ├─ contextDomainService.findBySessionId()   从 DB 加载 Context
  │         ├─ messageDomainService.listByIds()         根据 activeMessages 加载消息
  │         └─ applyTokenOverflowStrategy()             执行 Token 策略裁剪
  │
  ▼
AbstractMessageHandler.chat()          消息处理模板方法
  │
  ├─ 2. initMemory()                  创建 InMemoryChatMemoryStore
  ├─ 3. buildHistoryMessage()         组装 messages[] 数组
  │    ├─ 注入历史摘要 (AiMessage)
  │    ├─ 注入系统提示词 (SystemMessage) + 长期记忆
  │    └─ 遍历历史消息，逐条转为 ChatMessage
  │
  ├─ 4. provideTools()                子类决定是否提供工具
  │
  ├─ 5. 调用 LLM (流式或同步)
  │    ├─ saveMessageAndUpdateContext()   保存用户消息到 DB
  │    ├─ agent.chat()                    调用 LLM
  │    └─ saveMessageAndUpdateContext()   保存 AI 回复到 DB
  │
  └─ 6. onChatCompleted()             对话完成钩子
       └─ memoryExtractorService.extractAndPersistAsync()  异步抽取记忆
```

### 4.2 消息组装详解

`buildHistoryMessage()` 是每次调用 LLM 前组装完整 messages[] 的核心方法，位于 `AbstractMessageHandler.java`:

```java
protected void buildHistoryMessage(ChatContext chatContext, MessageWindowChatMemory memory) {
    // 步骤1：注入历史摘要（如果存在）
    // 摘要作为 AiMessage 出现在历史最前面，让 LLM 知道"之前聊了什么"
    String summary = getSummaryFromHistory(chatContext.getMessageHistory());
    if (StringUtils.isNotEmpty(summary)) {
        memory.add(new AiMessage(summary));
    }

    // 步骤2：构建完整的 System Prompt
    // = Agent 的系统提示词 + 工具预设参数 + 长期记忆要点
    String memorySection = buildMemorySection(chatContext);  // ← 长期记忆注入点
    String fullSystemPrompt = agent.getSystemPrompt()
        + "\n" + presetToolPrompt
        + (memorySection.isEmpty() ? "" : "\n" + memorySection);
    memory.add(new SystemMessage(fullSystemPrompt));

    // 步骤3：遍历所有活跃消息，按角色逐条添加到 memory
    for (MessageEntity msg : messageHistory) {
        if (msg.isUserMessage()) {
            // 多模态：文件URL → ImageContent 或 TextContent
            List<Content> fileContents = convertFileUrlsToContents(msg.getFileUrls());
            // 合并文本 + 文件内容为一条 UserMessage
            UserMessage userMsg = buildUserMessage(msg.getContent(), fileContents);
            memory.add(userMsg);
        } else if (msg.isAIMessage()) {
            memory.add(new AiMessage(msg.getContent()));
        } else if (msg.isSystemMessage()) {
            memory.add(new SystemMessage(msg.getContent()));
        }
        // 注意：SUMMARY 角色的消息不重复添加（已在步骤1处理）
    }
}
```

### 4.3 消息保存机制

消息在发送 LLM 请求**之前**就保存到数据库：

```java
// 先保存用户消息
this.saveMessageAndUpdateContext(chatContext, userEntity);

// 再发起 LLM 调用
TokenStream tokenStream = agent.chat(chatContext.getUserMessage());

// LLM 响应后保存 AI 消息
messageDomainService.saveMessageAndUpdateContext(
    Collections.singletonList(llmEntity), chatContext.getContextEntity());
```

这意味着即使 LLM 调用中途崩溃或超时，用户消息也不会丢失。

### 4.4 多模态文件处理

`convertFileUrlsToContents()` 方法根据文件类型做不同处理：

| 文件类型 | 扩展名 | 处理方式 |
|---------|--------|---------|
| 图片 | png, jpg, jpeg, gif, webp, bmp, svg, ico | 直接传 URL 给模型服务器（ImageContent） |
| 文本文件 | txt, md, csv, json, java, py, js, ts 等 | 后端下载内容（最大2MB），截断至50000字符，直接作为文本传给模型 |
| 其他文档 | pdf, docx 等 | 仅告知模型文件链接和格式，暂不做内容提取 |

---

## 5. Token 溢出策略

### 5.1 策略体系

系统提供三种策略，通过 `TokenOverflowStrategy` 接口统一，工厂模式创建：

```java
public interface TokenOverflowStrategy {
    TokenProcessResult process(List<TokenMessage> messages, TokenOverflowConfig config);
    boolean needsProcessing(List<TokenMessage> messages);
    String getName();
}
```

| 策略 | 枚举值 | 触发条件 | 行为 |
|------|--------|---------|------|
| 无策略 | NONE | 不触发 | 所有消息原样保留 |
| 滑动窗口 | SLIDING_WINDOW | 总 Token > maxTokens | 按时间倒序保留，丢弃旧消息 |
| 摘要压缩 | SUMMARIZE | 消息数量 > summaryThreshold | 旧消息→LLM摘要，保留摘要+最新N条 |

### 5.2 配置参数

策略配置存储在 Agent 的 `LLMModelConfig` 中：

| 参数 | 适用策略 | 说明 |
|------|---------|------|
| strategyType | 全部 | 策略枚举值 |
| maxTokens | 滑动窗口 | 窗口大小上限 |
| reserveRatio | 滑动窗口 | 为新消息预留的缓冲比例，默认10% |
| summaryThreshold | 摘要 | 触发摘要的消息数量阈值 |

### 5.3 滑动窗口策略

按消息的 `body_token_count` 累加，从最新消息开始保留，直到达到 `maxTokens × (1 - reserveRatio)` 的阈值。超出窗口的旧消息被直接丢弃，不回写摘要。

**适用场景**：简单问答、不需要长期上下文的聊天。

### 5.4 摘要策略

核心流程：

1. 将消息按时间升序排列
2. 前 N-threshold 条旧消息 → 提交给 LLM 生成摘要
3. 最近 threshold 条消息原样保留
4. 摘要作为 `Role.SUMMARY` 的消息插入列表最前面（`createdAt` 设为最早消息时间的前一秒）
5. 更新 `context.summary` 字段

摘要生成的 Prompt 设计要点：
- 明确要求"只基于提供的对话内容生成客观摘要"
- 特别关注用户问题、关键信息和重要事实
- 去除寒暄、表情符号和情感表达
- 已有的旧摘要内容**必须全部保留要点**（增量合并）
- 使用简洁的第三人称陈述句

### 5.5 策略执行的触发时机

在 `ConversationAppService.setupContextAndHistory()` 中，加载历史消息后**立即执行**策略。策略处理后的消息列表才是最终送入 `MessageWindowChatMemory` 的内容。

```java
// 伪代码
List<MessageEntity> history = loadFromDB(sessionId);
history = applyTokenOverflowStrategy(history);  // ← 裁剪在这里
chatContext.setMessageHistory(history);          //   裁剪后的结果用于后续
```

---

## 6. 长期记忆系统

### 6.1 设计理念

长期记忆解决"跨会话信息保留"问题。AgentX 采用 **"抽取 → 存储 → 检索 → 注入"** 四步模型：

```
对话完成 → 异步抽取要点 → 去重+向量化存储 → 下次对话时语义检索 → 注入 System Prompt
```

### 6.2 记忆类型

| 类型 | 含义 | 有效期 | 示例 |
|------|------|--------|------|
| PROFILE | 用户偏好/习惯/格式要求 | 长期 | "以后都用中文回答" |
| TASK | 中长期目标/计划 | 中期 | "这周要完成 Agent 项目" |
| FACT | 稳定不变的事实 | 长期 | "主要语言是 Python，在上海办公" |
| EPISODIC | 短期上下文 | 3-5 轮 | 某次对话中的重要决策 |

### 6.3 记忆抽取（写入）

**时机**：每次对话完成后，通过 `onChatCompleted()` 钩子异步触发。

**实现**：`MemoryExtractorService.extractAndPersistAsync()`

**抽取规则**（通过精心设计的 System Prompt 实现）：

- 仅从用户当轮发言中抽取
- 重要性 ≥ 0.8 才保留（EPISODIC 需 ≥ 0.9）
- 过滤一次性操作（命令、工具调用、浏览操作等）
- 过滤隐私信息（身份证号、银行卡、密钥等）
- 相同语义合并为 1 条，最多输出 1-3 条
- 输出格式为结构化 XML：

```xml
<memories>
  <memory>
    <type>PROFILE</type>
    <text>用户偏好简体中文回答，并偏好附带 bash 示例</text>
    <importance>0.9</importance>
    <tags><tag>preference</tag></tags>
  </memory>
</memories>
```

**去重机制**：
- 对文本做归一化处理（去多余空白、转小写）
- 计算 SHA-256 哈希
- 按 `userId + dedupeHash` 查询已有记录
- 存在则合并（importance 取 max，tags 合并去重，text 取更长者）

**双重存储**：
- PostgreSQL `memory_items` 表：存储元数据和管理信息
- PgVector 向量库：存储文本的 Embedding 向量，用于语义检索

### 6.4 记忆检索（读取）

**时机**：每次构建 LLM 请求的 System Prompt 时。

**实现**：`MemoryDomainService.searchRelevant()`

**检索流程**：

1. 用户当前消息 → Embedding 模型向量化
2. PgVector 搜索（filter: `USER_ID = userId`, `minScore = 0.3`, `candidates = topK × 3`）
3. 加权打分：`score = 0.7 × 语义相似度 + 0.3 × 重要性`
4. 返回 Top-K 条结果

### 6.5 记忆注入

检索到的记忆以 `[记忆要点]` 段落的格式拼接到 System Prompt 尾部：

```
你是一个专业的编程助手...

[模型预设工具参数...]

[记忆要点]
- [PROFILE] 用户偏好简体中文回答，并偏好附带 bash 示例
- [FACT] 用户主要使用 Python，常驻上海办公
- [TASK] 用户本周目标：完成 Agent 项目搭建并补齐文档
```

注入位置在 System Prompt 的末尾，确保 LLM 在处理请求时能参考这些信息。

### 6.6 异步架构

记忆抽取使用 Spring 的 `@Async("memoryTaskExecutor")` 异步执行，不阻塞主对话流程：

```java
// AbstractMessageHandler.onChatCompleted()
try {
    memoryExtractorService.extractAndPersistAsync(userId, sessionId, userText);
} catch (Exception ignore) {
    // 异步任务调度异常不影响主流程
}
```

注意：RAG 对话和公开访问的 Widget 对话会跳过记忆抽取。

---

## 7. 会话管理

### 7.1 智能会话命名

首次对话完成后，系统自动生成会话标题：

1. 使用用户默认模型
2. 发送 System Prompt（`AgentPromptTemplates.getStartConversationPrompt()`）+ 用户首条消息
3. LLM 返回的文本作为会话标题
4. 新线程异步执行，不阻塞响应

### 7.2 会话中断

`ChatSessionManager` 管理活跃的 SSE 连接，支持中断正在进行的 LLM 调用。

---

## 8. 关键类关系

```
ConversationAppService (应用层编排)
  │
  ├── setupContextAndHistory()     加载上下文 + Token 裁剪
  ├── applyTokenOverflowStrategy() 调度策略引擎
  │
  ▼
AbstractMessageHandler (消息处理模板)
  │
  ├── initMemory()                 → MessageWindowChatMemory
  ├── buildHistoryMessage()        → 组装 SystemMessage + 历史 + 记忆
  │   └── buildMemorySection()     → MemoryDomainService.searchRelevant()
  ├── convertFileUrlsToContents()  → 多模态文件转换
  ├── saveMessageAndUpdateContext()→ 持久化消息 + 更新 activeMessages
  └── onChatCompleted()            → MemoryExtractorService 异步抽取
  │
  ▼
具体处理器: AgentMessageHandler / ChatMessageHandler / RagMessageHandler
  │
  ▼
LLM (通过 LLMServiceFactory 获取 StreamingChatModel 或 ChatModel)
```

### 8.1 完整文件清单

**对话/上下文管理**：
- `AgentX/src/main/java/org/xhy/application/conversation/service/ConversationAppService.java`
- `AgentX/src/main/java/org/xhy/application/conversation/service/message/AbstractMessageHandler.java`
- `AgentX/src/main/java/org/xhy/domain/conversation/model/ContextEntity.java`
- `AgentX/src/main/java/org/xhy/domain/conversation/model/MessageEntity.java`
- `AgentX/src/main/java/org/xhy/domain/conversation/model/SessionEntity.java`
- `AgentX/src/main/java/org/xhy/domain/conversation/service/MessageDomainService.java`
- `AgentX/src/main/java/org/xhy/domain/conversation/service/ContextDomainService.java`

**Token 溢出策略**：
- `AgentX/src/main/java/org/xhy/domain/token/service/TokenOverflowStrategy.java`
- `AgentX/src/main/java/org/xhy/domain/token/service/TokenOverflowStrategyFactory.java`
- `AgentX/src/main/java/org/xhy/domain/token/service/TokenDomainService.java`
- `AgentX/src/main/java/org/xhy/domain/token/service/impl/SlidingWindowTokenOverflowStrategy.java`
- `AgentX/src/main/java/org/xhy/domain/token/service/impl/SummarizeTokenOverflowStrategy.java`
- `AgentX/src/main/java/org/xhy/domain/token/service/impl/NoTokenOverflowStrategy.java`
- `AgentX/src/main/java/org/xhy/domain/agent/model/LLMModelConfig.java`
- `AgentX/src/main/java/org/xhy/domain/shared/enums/TokenOverflowStrategyEnum.java`

**长期记忆**：
- `AgentX/src/main/java/org/xhy/domain/memory/service/MemoryDomainService.java`
- `AgentX/src/main/java/org/xhy/domain/memory/service/MemoryExtractorService.java`
- `AgentX/src/main/java/org/xhy/domain/memory/model/MemoryItemEntity.java`
- `AgentX/src/main/java/org/xhy/domain/memory/model/MemoryType.java`
- `AgentX/src/main/java/org/xhy/domain/memory/model/MemoryResult.java`
- `AgentX/src/main/java/org/xhy/domain/memory/model/CandidateMemory.java`
- `AgentX/src/main/java/org/xhy/application/memory/service/MemoryAppService.java`
- `AgentX/src/main/java/org/xhy/infrastructure/memory/config/MemoryEmbeddingConfig.java`

---

## 9. 设计决策与权衡

### 9.1 为什么每一轮都要传完整历史？

这是目前 LLM API 的**唯一标准做法**。LLM 没有服务端会话状态，每次请求必须自包含所有上下文。替代方案（如 OpenAI Assistants API）的底层原理相同，只是把历史管理封装到了服务端。

### 9.2 为什么消息先保存再发 LLM？

防止 LLM 调用失败时丢失用户消息，确保数据完整性。

### 9.3 为何摘要策略优于简单截断？

- 截断（滑动窗口）：简单快速，无额外成本，但信息永久丢失
- 摘要：需要额外 LLM 调用，有延迟和成本，但保留了关键信息的压缩版本

AgentX 提供两种选择，让用户根据场景自行决策。

### 9.4 为什么记忆抽取仅基于用户发言？

当前设计只传入 `userMessage`（用户当轮发言），不传入 AI 回复。这是"仅用户抽取策略"——简单可控，但可能遗漏 AI 回复中隐含的用户偏好信息。未来可考虑基于完整 user+assistant 对话对来抽取。

### 9.5 为什么记忆注入到 System Prompt 而不是独立消息？

System Prompt 在 LLM 处理中具有最高优先级。将记忆放在 System Prompt 尾部，确保 LLM 在生成回复前"看到"这些信息，同时不干扰对话流程。

---

## 10. 未来优化方向

1. **自适应策略**：根据对话特征自动选择/切换 Token 策略
2. **分组摘要**：将早期消息按主题分组后分别摘要，而非全部混合在一起
3. **记忆过期机制**：EPISODIC 记忆应在多轮后自动衰减或归档
4. **全轮次记忆抽取**：基于完整 user+assistant 对话对抽取，捕获更丰富的上下文
5. **记忆重要性动态调整**：被频繁检索的记忆自动提升 importance
6. **可视化工具**：提供 Token 使用和记忆状态的可视化界面
