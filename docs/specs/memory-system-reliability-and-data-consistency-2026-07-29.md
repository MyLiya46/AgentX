# Spec: 记忆系统可靠性与数据一致性改进

- **创建日期**: 2026-07-29
- **状态**: 待实施
- **关联 Plans**:
  - [memory-write-path-error-handling-and-transaction-2026-07-29](../plans/memory-write-path-error-handling-and-transaction-2026-07-29.md)

---

## 一、问题陈述

用户在 AgentX 中通过前端页面的 `/settings/memory` 手动添加记忆，页面提示"创建记忆成功"，但刷新后列表为空，且基于该记忆创建的 Agent 进行对话时完全未引用该记忆内容。经全链路排查，定位到以下问题：

| # | 缺陷 | 严重程度 | 类型 |
|---|------|----------|------|
| 1 | `MemoryDomainService.saveMemories()` 中 `memoryItemRepository.insert()` 异常被静默吞掉 | 🔴 严重 | 错误处理缺陷 |
| 2 | `memoryItemRepository.insert()` 与 `memoryEmbeddingStore.add()` 无事务边界 | 🔴 严重 | 数据一致性缺陷 |
| 3 | `searchRelevant()` 回查 `memory_items` 时未过滤 `status=1` | 🟡 中等 | 查询逻辑缺陷 |
| 4 | `memory_vector_store` 存在孤儿记录（ITEM_ID 无对应 memory_items 记录），无清理机制 | 🟡 中等 | 运维缺陷 |

### 现象链路

```
用户手动创建记忆 (POST /api/portal/memory/items)
  → memoryItemRepository.insert(toSave) 失败（异常被静默吞掉）
  → memoryEmbeddingStore.add() 成功（代码继续执行）
  → 结果：memory_vector_store 有数据，memory_items 为空
  → GET /api/portal/memory/items 查询（pageMemories 过滤 status=1 + userId）
  → 返回空列表（因为 memory_items 无记录）

Agent 对话检索记忆：
  → searchRelevant() 向量检索命中（memory_vector_store 中有向量）
  → 回查 memory_items（WHERE id IN itemIds）
  → itemMap 为空 → 所有匹配被 continue 跳过
  → 返回空列表 → Agent 收不到任何记忆
```

## 二、现有架构分析

### 2.1 记忆双表存储模型

```
┌─────────────────────────┐     ┌──────────────────────────────┐
│    memory_items         │     │  memory_vector_store         │
│    (业务主表)            │     │  (PgVector 向量表)            │
├─────────────────────────┤     ├──────────────────────────────┤
│ id (PK, UUID)           │     │ id (UUID, 向量库自生成)        │
│ user_id                 │◄────│ metadata.ITEM_ID ────────────┤
│ type (PROFILE/TASK/...) │     │ metadata.USER_ID              │
│ text                    │     │ metadata.MEMORY_TYPE          │
│ importance (0.0~1.0)    │     │ metadata.TAGS                 │
│ tags (TEXT[])           │     │ metadata.STATUS               │
│ data (JSONB)            │     │ embedding (vector(1024))      │
│ source_session_id       │     │ text (TextSegment)            │
│ dedupe_hash (SHA-256)   │     │                               │
│ status (1=active/0=归档)│     │                               │
│ created_at / updated_at │     │                               │
└─────────────────────────┘     └──────────────────────────────┘
```

- 两表通过向量 metadata 中的 `ITEM_ID` 做逻辑关联，**无数据库外键约束**
- 写入顺序：先 `memory_items` → 后 `memory_vector_store`
- 检索顺序：先 `memory_vector_store`（向量相似度）→ 后 `memory_items`（回查完整数据）

### 2.2 当前 saveMemories() 流程

```java
// MemoryDomainService.saveMemories() — 当前实现（简化）
for (CandidateMemory c : candidates) {
    // 1. 去重检查
    MemoryItemEntity existed = memoryItemRepository.selectOne(...);

    if (existed == null) {
        toSave = new MemoryItemEntity();
        // ... 设置字段 ...
        try {
            memoryItemRepository.insert(toSave);   // ← 异常被吞！
        } catch (Exception e) {
            e.printStackTrace();                    // ← 仅打印，继续执行
        }
    } else {
        // 合并逻辑
        memoryItemRepository.updateById(toSave);
    }

    // 2. 向量入库（无论 insert 是否成功都会执行）
    memoryEmbeddingStore.add(emb, segment);         // ← 孤儿记录产生点
}
```

**问题 A — 静默吞异常**：`insert` 失败时仅 `printStackTrace()`，不抛异常、不记日志、不阻止后续向量写入。调用方（Controller/AppService）感知不到失败，永远返回成功。

**问题 B — 无事务边界**：`PgVectorEmbeddingStore` 使用独立 JDBC 连接，与 Spring 管理的 `memoryItemRepository` 不在同一事务中。即使 insert 成功，若向量写入失败，业务表已写入无法回滚。

**问题 C — 查询未过滤状态**：`searchRelevant()` 第 171-172 行回查 `memory_items` 时：
```java
memoryItemRepository.selectList(
    Wrappers.<MemoryItemEntity>lambdaQuery().in(MemoryItemEntity::getId, itemIds));
```
没有 `eq(MemoryItemEntity::getStatus, ACTIVE)` 条件，可能将已归档记忆注入 Agent 上下文。

### 2.3 insert 失败根因推断

`MemoryItemEntity` 继承 `BaseEntity`，后者使用 `@TableField(fill = FieldFill.INSERT/INSERT_UPDATE)` 标注 `createdAt`/`updatedAt`。需要 MyBatis-Plus `MetaObjectHandler` 全局配置自动填充。若未配置或未生效，这两个字段为 null。

此外，迁移 `V20260729001` 为 `memory_items` 添加了 `deleted_at` 列（`ALTER TABLE ADD COLUMN IF NOT EXISTS`），但 `MemoryItemEntity` 无此字段映射，不过该列无 NOT NULL 约束，不应导致 insert 失败。

**最终确认需查看应用启动日志和 insert 时的具体异常信息**。

## 三、目标架构

### 3.1 设计原则

1. **错误必须可见**：任何持久化失败必须向上传播异常，不允许静默吞掉
2. **双写一致性**：业务表 + 向量表写入应有事务或补偿机制，避免孤儿记录
3. **查询完整性**：检索路径必须正确过滤已归档/已删除记录
4. **可观测性**：关键路径必须有结构化日志（成功/失败/耗时）
5. **可运维性**：提供孤儿记录检测和清理能力

### 3.2 改进后的写入流程

```
saveMemories(userId, sessionId, candidates)
  │
  ├─ 1. 去重检查 (不变)
  │
  ├─ 2. 写入 memory_items
  │    ├─ 新增 → memoryItemRepository.insert(toSave)
  │    │         失败 → throw BusinessException("记忆保存失败", e) + 记录日志
  │    │                → 不继续向量写入
  │    └─ 合并 → memoryItemRepository.updateById(toSave)
  │
  ├─ 3. 写入向量库
  │    └─ memoryEmbeddingStore.add(emb, segment)
  │       失败 → throw BusinessException("记忆向量入库失败", e) + 记录日志
  │              → 补偿：删除步骤2写入的 memory_items 记录（或标记为异常状态）
  │
  ├─ 4. 记录成功日志（含 itemId、type、userId）
  │
  └─ 5. 返回 itemIds
```

### 3.3 改进后的检索流程

```java
// searchRelevant() 回查 memory_items — 增加 status 过滤
memoryItemRepository.selectList(
    Wrappers.<MemoryItemEntity>lambdaQuery()
        .in(MemoryItemEntity::getId, itemIds)
        .eq(MemoryItemEntity::getStatus, ACTIVE));  // ← 新增过滤
```

### 3.4 新增：孤儿记录检测与清理

提供 `MemoryDomainService` 中的检测方法和管理 API（admin 专用）：

- **检测**：查询 `memory_vector_store` 中 `ITEM_ID` 不在 `memory_items`（status=1）中的记录
- **清理**：批量删除孤儿向量记录（使用 `PgVectorEmbeddingStore.removeAll(filter)`）

## 四、影响范围

| 文件 | 操作 | 风险 |
|------|------|------|
| `MemoryDomainService.java` | 重构 `saveMemories()` 错误处理 + `searchRelevant()` 增加状态过滤 + 新增孤儿清理方法 | 中 — 修改记忆核心路径 |
| `MemoryExtractorService.java` | `extractAndPersistAsync()` 增加异常日志（异步方法中异常被线程池吞掉） | 低 |
| `MemoryAppService.java` | 无需修改（异常由 DomainService 向上抛，Controller 层全局异常处理） | 无风险 |
| `PortalMemoryController.java` | 可选：新增 `GET /admin/memory/orphans` 和 `DELETE /admin/memory/orphans` | 低 |
| MyBatis-Plus 配置类 | 新增/修复 `MetaObjectHandler`（若缺失导致 insert 失败） | 低 |

## 五、验收标准

1. 手动创建记忆 → 返回成功 → 刷新列表可看到新记忆
2. 手动创建记忆 → Agent 对话中能检索并引用该记忆
3. insert 失败时 → 返回明确错误信息（非静默成功）→ 向量库无孤儿记录
4. 向量写入失败时 → 返回错误 → `memory_items` 中无对应记录（补偿删除）
5. 已归档（status=0）的记忆 → 不在 Agent 对话检索结果中出现
6. 日志中可追踪每次记忆保存的完整链路（去重/新增/合并/向量化/耗时）
7. 提供 admin API 可检测并清理孤儿向量记录
8. 单元测试覆盖：insert 异常传播、向量写入失败补偿、status 过滤、孤儿检测
