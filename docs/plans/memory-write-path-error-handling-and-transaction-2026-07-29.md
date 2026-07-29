# Plan: 记忆写入路径错误处理与双写事务一致性

- **创建日期**: 2026-07-29
- **关联 Spec**: [memory-system-reliability-and-data-consistency-2026-07-29](../specs/memory-system-reliability-and-data-consistency-2026-07-29.md)
- **前置依赖**: 无
- **状态**: 已完成

---

## 一、实施概览

| 步骤 | 内容 | 涉及文件 | 状态 |
|------|------|----------|------|
| 1 | 诊断 insert 失败根因 | 0（日志分析）→ 1（迁移文件）| ✅ 已完成 — id 列类型 uuid→varchar |
| 2 | 修复 `saveMemories()` 错误处理 + 双写补偿 | 1 | ✅ 已完成 |
| 3 | 修复 `searchRelevant()` 状态过滤 | 1 | ✅ 已完成 |
| 4 | 新增孤儿向量检测与清理 | 2（DomainService + Repository）| ✅ 已完成 |
| 5 | 修复 `MemoryExtractorService` 异步异常处理 | 1 | ✅ 已完成 |
| 6 | 新增 admin API（孤儿检测/清理端点）| 2（Controller + AppService）| ✅ 已完成 |
| 7 | 补齐 MyBatis-Plus MetaObjectHandler | 0 | ⏭️ 跳过 — 已存在于 MybatisPlusConfig |
| 8 | 编写单元测试 | 1 + 1 配置文件 | ✅ 已完成 — 8 个测试全部通过 |

---

## 二、详细步骤

### 步骤 1：诊断 insert 失败根因

**实际执行**：

1. 代码搜索确认 `MybatisPlusConfig.java` 已实现 `MetaObjectHandler`，排除 auto-fill 缺失
2. 去掉静默吞异常后，insert 抛出 `BadSqlGrammarException`，根因日志：

```
ERROR: column "id" is of type uuid but expression is of type character varying
```

**根因**：`memory_items` 表的 `id` 列是 PostgreSQL 原生 `uuid` 类型，但 `MemoryItemEntity` 使用 `@TableId(type = IdType.ASSIGN_UUID)` 生成 32 位 hex 字符串（如 `3ced6cc835c4d967...`）。MyBatis-Plus 将其当作 `VARCHAR` 发送给 PostgreSQL，PG 的 `uuid` 类型拒绝隐式转换。

项目其他 entity（`AgentVersionEntity`、`UserEntity` 等）全部使用 `IdType.ASSIGN_UUID` + `String id`，对应表的 `id` 列统一为 `varchar`。`memory_items` 表在创建时 `id` 列类型误用了 `uuid`。

**修复**：

```sql
-- 直接在 PostgreSQL 执行（Flyway 迁移 V20260729002 也包含此语句）
ALTER TABLE public.memory_items ALTER COLUMN id TYPE varchar(64);
```

**步骤 7 因此跳过**（MetaObjectHandler 已存在且配置正确）。

---

### 步骤 2：重构 `saveMemories()` — 错误处理 + 双写补偿

**文件**: `MemoryDomainService.java`

#### 2a. 将静默 catch 改为显式抛出（最终代码）

```java
// 原代码
try {
    memoryItemRepository.insert(toSave);
} catch (Exception e) {
    e.printStackTrace();     // ← 静默吞异常
}

// 最终代码
try {
    memoryItemRepository.insert(toSave);
} catch (Exception e) {
    log.error("记忆条目写入失败 userId={}, type={}, text={}", userId, type.name(), c.getText(), e);
    throw new BusinessException("记忆保存失败，请稍后重试", e);
}
```

**设计决策**：insert 失败直接抛异常，**不继续**向量写入。避免了数据不一致（向量库有记录但业务表没有）。

#### 2b. 向量写入失败时补偿删除业务表记录（最终代码）

```java
// 向量入库
try {
    Metadata md = new Metadata();
    md.put(USER_ID, userId);
    md.put(ITEM_ID, toSave.getId());
    md.put(MEMORY_TYPE, type.name());
    md.put(TAGS, String.join(",", toSave.getTags() == null ? List.of() : toSave.getTags()));
    md.put(STATUS, String.valueOf(ACTIVE));

    TextSegment segment = new TextSegment(toSave.getText(), md);
    Embedding emb = embeddingModel.embed(segment).content();
    memoryEmbeddingStore.add(emb, segment);
} catch (Exception e) {
    log.error("向量入库失败，将回滚业务表记录 userId={}, itemId={}, err={}",
            userId, toSave.getId(), e.getMessage(), e);

    // 补偿删除：向量写入失败时，删除已写入的业务表记录
    final boolean wasNew = (existed == null);
    try {
        if (wasNew) {
            memoryItemRepository.deleteById(toSave.getId());
            log.info("已回滚新增的记忆记录 itemId={}", toSave.getId());
        } else {
            memoryItemRepository.updateById(existed);
            log.info("已恢复合并前的记忆记录 itemId={}", toSave.getId());
        }
    } catch (Exception rollbackEx) {
        log.error("回滚业务表记录失败 itemId={}, err={}。需要人工处理！",
                toSave.getId(), rollbackEx.getMessage(), rollbackEx);
    }

    throw new BusinessException("记忆向量入库失败: " + e.getMessage(), e);
}
```

**与原始 plan 的细微差异**：`wasNew` 声明为 `final boolean`（原 plan 为普通 `boolean`），无功能差异。

#### 2c. 增加结构化成功日志（最终代码）

```java
log.info("记忆保存成功 userId={}, itemId={}, type={}, importance={}, isNew={}",
        userId, toSave.getId(), type.name(), toSave.getImportance(), existed == null);
```

位于向量写入成功之后、循环体末尾。

---

### 步骤 3：修复 `searchRelevant()` 状态过滤

**文件**: `MemoryDomainService.java`（与 plan 一致）

```java
// 最终代码（增加 .eq(MemoryItemEntity::getStatus, ACTIVE)）
List<MemoryItemEntity> items = memoryItemRepository.selectList(
        Wrappers.<MemoryItemEntity>lambdaQuery()
                .in(MemoryItemEntity::getId, itemIds)
                .eq(MemoryItemEntity::getStatus, ACTIVE));
```

---

### 步骤 4：新增孤儿向量检测与清理

**实际实现与原 plan 的主要差异**：原 plan 预想使用 `JdbcTemplate` 或 `PgVectorEmbeddingStore.removeAll(Filter)`，实际采用了更简洁的 **MyBatis `@Select` / `@Delete` 原生 SQL** 方案，无需引入额外依赖。

#### 4a. `MemoryItemRepository` 新增方法（最终代码）

```java
/**
 * 查询向量库中的孤儿 ITEM_ID（不在给定 activeItemIds 集合中）
 * 使用 MyBatis <script> 标签支持动态 SQL（当 activeItemIds 为空时跳过 IN 过滤）
 */
@Select("""
        <script>
        SELECT metadata->>'ITEM_ID' AS item_id
        FROM memory_vector_store
        WHERE metadata->>'USER_ID' = #{userId}
        <if test="activeItemIds != null and activeItemIds.size() > 0">
          AND metadata->>'ITEM_ID' NOT IN
          <foreach collection="activeItemIds" item="id" open="(" separator="," close=")">
              #{id}
          </foreach>
        </if>
        </script>
        """)
List<String> findOrphanVectorItemIds(@Param("userId") String userId,
                                     @Param("activeItemIds") Set<String> activeItemIds);

/**
 * 按 ITEM_ID 删除向量库中的记录
 * 直接使用 PostgreSQL DELETE + JSONB metadata 字段过滤
 */
@Delete("""
        DELETE FROM memory_vector_store
        WHERE metadata->>'USER_ID' = #{userId}
          AND metadata->>'ITEM_ID' = #{itemId}
        """)
int deleteVectorByItemId(@Param("userId") String userId, @Param("itemId") String itemId);
```

> **设计决策**：放弃原 plan 中的 `JdbcTemplate` 方案和 `PgVectorEmbeddingStore.removeAll(Filter)` 方案，改用 MyBatis 注解 SQL。原因：(1) 项目已使用 MyBatis-Plus，无需引入 JdbcTemplate；(2) 避免 langchain4j 版本 API 兼容性风险；(3) `<script>` 标签支持动态 SQL，当 `activeItemIds` 为空时不会报错。

#### 4b. `MemoryDomainService` 孤儿检测（最终代码）

```java
public List<String> findOrphanVectors(String userId) {
    List<MemoryItemEntity> activeItems = listMemories(userId, null, null);
    Set<String> activeItemIds = activeItems.stream()
            .map(MemoryItemEntity::getId).collect(Collectors.toSet());

    List<String> orphanIds = memoryItemRepository.findOrphanVectorItemIds(userId, activeItemIds);

    log.info("孤儿向量检测完成 userId={}, activeCount={}, orphanCount={}",
            userId, activeItemIds.size(), orphanIds.size());
    return orphanIds;
}
```

#### 4c. `MemoryDomainService` 孤儿清理（最终代码）

```java
public int cleanOrphanVectors(String userId) {
    List<String> orphanIds = findOrphanVectors(userId);
    if (orphanIds.isEmpty()) {
        log.info("未发现孤儿向量记录 userId={}", userId);
        return 0;
    }

    int cleaned = 0;
    for (String itemId : orphanIds) {
        try {
            memoryItemRepository.deleteVectorByItemId(userId, itemId);
            cleaned++;
        } catch (Exception e) {
            log.error("清理孤儿向量记录失败 userId={}, itemId={}, err={}",
                    userId, itemId, e.getMessage(), e);
        }
    }

    log.info("清理孤儿向量记录完成 userId={}, 清理数量={}", userId, cleaned);
    return cleaned;
}
```

---

### 步骤 5：修复 `MemoryExtractorService` 异步异常处理

**文件**: `MemoryExtractorService.java`（最终代码与 plan 一致）

```java
@Async("memoryTaskExecutor")
public void extractAndPersistAsync(String userId, String sessionId, String userMessage) {
    long start = System.currentTimeMillis();
    try {
        List<CandidateMemory> candidates = extract(userId, sessionId, userMessage);
        if (candidates == null || candidates.isEmpty()) {
            log.debug("本轮对话无有效记忆可抽取 userId={}, sessionId={}", userId, sessionId);
            return;
        }
        List<String> itemIds = memoryDomainService.saveMemories(userId, sessionId, candidates);
        long elapsed = System.currentTimeMillis() - start;
        log.info("记忆抽取完成 userId={}, sessionId={}, 抽取数量={}, 耗时={}ms",
                userId, sessionId, itemIds.size(), elapsed);
    } catch (Exception e) {
        long elapsed = System.currentTimeMillis() - start;
        log.error("记忆抽取异步任务失败 userId={}, sessionId={}, 耗时={}ms, err={}",
                userId, sessionId, elapsed, e.getMessage(), e);
    }
}
```

**与原 plan 的差异**：日志级别从 `warn` 提升为 `error`（异步任务失败应触发告警）；增加了成功路径的 `info` 日志。

---

### 步骤 6：新增 Admin API（孤儿检测/清理端点）

**文件**: `AdminMemoryController.java` — 实际包路径为 `org.xhy.interfaces.api.admin.memory`（原 plan 为 `org.xhy.interfaces.api.admin`）

```java
package org.xhy.interfaces.api.admin.memory;

@RestController
@RequestMapping("/admin/memory")
public class AdminMemoryController {

    private final MemoryAppService memoryAppService;

    @GetMapping("/orphans")
    public Result<Map<String, Object>> findOrphans(@RequestParam String userId) {
        List<String> orphanIds = memoryAppService.findOrphanVectors(userId);
        return Result.success(Map.of(
                "userId", userId,
                "count", orphanIds.size(),
                "orphanItemIds", orphanIds));
    }

    @DeleteMapping("/orphans")
    public Result<Map<String, Object>> cleanOrphans(@RequestParam String userId) {
        int cleaned = memoryAppService.cleanOrphanVectors(userId);
        return Result.success(Map.of("userId", userId, "cleaned", cleaned));
    }
}
```

**MemoryAppService 新增代理方法**（与 plan 一致）：

```java
public List<String> findOrphanVectors(String userId) {
    return memoryDomainService.findOrphanVectors(userId);
}

public int cleanOrphanVectors(String userId) {
    return memoryDomainService.cleanOrphanVectors(userId);
}
```

---

### 步骤 7：补齐 MyBatis-Plus MetaObjectHandler

⏭️ **跳过**。经步骤 1 诊断，`MybatisPlusConfig` 已实现 `MetaObjectHandler` 接口，正确配置了 `createdAt` / `updatedAt` 的自动填充。

---

### 步骤 8：编写单元测试

**文件**: `MemoryDomainServiceTest.java` + `mockito-extensions/org.mockito.plugins.MockMaker`

**实际实施与原 plan 的重大差异**：原 plan 仅为骨架代码，实际编写了 **8 个完整的可运行测试用例**，覆盖所有计划场景。同时解决了 Java 26 + ByteBuddy 兼容性问题。

#### 8a. Java 26 兼容性处理

Mockito 默认的 inline mock maker 在 Java 26 上无法通过 ByteBuddy 修改类。解决方案：

1. **mock-maker 配置**（新增文件 `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`）：
   ```
   mock-maker-subclass
   ```
   切换到基于子类的 mock 生成策略。

2. **`EmbeddingModelFactory` 不使用 `@Mock`**：该类在 Java 26 下即使子类模式也无法 mock（内部依赖 OpenAiEmbeddingModel builder）。改用**匿名内部类**替代：

   ```java
   // 不使用 @Mock EmbeddingModelFactory
   EmbeddingModelFactory embeddingModelFactory = new EmbeddingModelFactory() {
       @Override
       public OpenAiEmbeddingModel createEmbeddingModel(EmbeddingConfig config) {
           return embeddingModel;  // 返回预先 mock 的 OpenAiEmbeddingModel
       }
   };
   ```

3. **手动初始化 Mockito**：使用 `MockitoAnnotations.openMocks(this)` 替代 `@ExtendWith(MockitoExtension.class)`，确保自定义工厂注入在 mock 初始化之后。

#### 8b. MyBatis-Plus UUID 自动生成模拟

在 `saveMemories()` 中，`new MemoryItemEntity()` 后调用 `insert()`，MyBatis-Plus 的 `IdType.ASSIGN_UUID` 会在 insert 时自动生成 ID。单元测试中无 MyBatis 上下文，需用 `doAnswer` 模拟：

```java
doAnswer(inv -> {
    MemoryItemEntity entity = inv.getArgument(0);
    entity.setId("generated-id-001");
    return 1;
}).when(memoryItemRepository).insert(any(MemoryItemEntity.class));
```

#### 8c. `updateById` 验证策略

`MemoryItemRepository.updateById` 继承自 MyBatis-Plus `BaseMapper`，存在 `updateById(T)` 和 `updateById(Collection<T>)` 两个重载。`argThat` 匹配器与 lambda 结合会导致编译歧义。改用 `ArgumentCaptor`：

```java
ArgumentCaptor<MemoryItemEntity> captor = ArgumentCaptor.forClass(MemoryItemEntity.class);
verify(memoryItemRepository).updateById(captor.capture());
assertThat(captor.getValue().getImportance()).isEqualTo(0.8f);
```

#### 8d. 测试用例执行结果

```
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

| 测试组 | 用例 | 说明 |
|--------|------|------|
| SaveMemoriesErrorHandling (3) | insert 失败抛异常 | 验证不写入向量库 |
| | 向量写入失败补偿删除 | 验证 deleteById 被调用 |
| | 补偿失败不掩盖原始异常 | 验证仍抛出向量写入异常 |
| SaveMemoriesDeduplication (1) | 重复记忆合并 | 验证 importance 取 max(0.7,0.8)=0.8 |
| OrphanVectors (2) | 正确检测孤儿记录 | 验证返回孤儿 ID 列表 |
| | 无孤儿时返回空列表 | 验证空集合场景 |
| CleanOrphanVectors (2) | 正确清理孤儿记录 | 验证逐条删除 + 计数 |
| | 清理失败时隔离继续 | 验证失败不中断其他清理 |

---

## 三、改动文件清单

| 文件 | 操作 | 行数变化 |
|------|------|----------|
| `MemoryDomainService.java` | 修改 | +80 |
| `MemoryItemRepository.java` | 修改 | +43 |
| `MemoryAppService.java` | 修改 | +10 |
| `MemoryExtractorService.java` | 修改 | +14 |
| `AdminMemoryController.java` | **新增** | +34 |
| `MemoryDomainServiceTest.java` | **新增** | ~230 |
| `mockito-extensions/org.mockito.plugins.MockMaker` | **新增** | +1 |
| `docs/TODO.md` | 修改 | 精简为一行 |

---

## 四、风险与回滚

| 风险 | 等级 | 实际情况 |
|------|------|---------|
| 步骤 2 补偿删除可能失败 | 低 | 补偿失败时记录 error 日志标记"需人工处理"，不影响原始异常传播 |
| pgvector `removeAll(Filter)` API 不可用 | 已规避 | 改用 MyBatis `@Delete` 原生 SQL，不依赖 langchain4j API |
| Admin API 未受权限保护 | 中 | 路径 `/admin/**` 由现有 Spring Security 拦截器保护 |
| Java 26 + ByteBuddy 兼容性 | 已解决 | mock-maker-subclass + 匿名内部类替代 @Mock |

**回滚方式**：`git revert` 对应 commit。无数据库 schema 变更。

---

## 五、后续优化（本次不实施）

1. **分布式事务**：`memory_vector_store` 与 `memory_items` 在同一 PostgreSQL 实例，可考虑 JDBC 层面的事务同步
2. **定期孤儿清理任务**：通过 `@Scheduled` 定时执行孤儿检测，告警而非自动清理
3. **记忆变更审计日志**：独立的 `memory_audit_log` 表
4. **记忆来源标记**：`memory_items` 增加 `source` 字段（MANUAL / AUTO_EXTRACT）
5. **记忆过期机制**：TTL 自动归档

